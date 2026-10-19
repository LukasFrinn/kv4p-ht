package com.vagell.kv4pht.radio;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Desktop counterpart of the connection/handshake/RX part of the Android
 * RadioAudioService. Protocol framing and desired-state bookkeeping are done
 * by the shared upstream classes (Protocol, RadioModuleController); this
 * class only wires them to a serial port and a listener.
 * <p>
 * Threads: radio state and controls run on "kv4p-radio" (like the Android
 * service's Handler). Incoming bytes are parsed on "kv4p-rx", which also
 * decodes RX audio and applies flow-control window updates directly, so a
 * sender blocked on the window (microphone audio) can never deadlock the
 * radio thread. Listener callbacks come from kv4p-radio, except onRxAudio
 * (kv4p-rx).
 */
public final class DesktopRadioSession implements AutoCloseable {
    private static final String TAG = DesktopRadioSession.class.getSimpleName();
    private static final String FIRMWARE_TAG = "Firmware";

    /**
     * Oldest firmware this app speaks to. Keep in sync with
     * FirmwareUtils.PACKAGED_FIRMWARE_VER in the Android app (that class has
     * Android dependencies, so it isn't shared).
     */
    public static final int MIN_FIRMWARE_VER = 17;

    /** Same as the released Android app: Opus, 40 ms frames at 48 kHz mono. */
    public static final int AUDIO_SAMPLE_RATE = 48000;
    public static final int AUDIO_FRAME_SAMPLES = 1920;

    /** Same safety limit as the Android app: PTT is released after 3 minutes. */
    public static final int RUNAWAY_TX_TIMEOUT_SEC = 180;

    private static final long FIRST_HELLO_WAIT_MS = 4000;
    private static final long HELLO_TIMEOUT_MS = 12000;

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        HANDSHAKE,
        CONNECTED,
        OUTDATED_FIRMWARE,
        NO_RESPONSE,
        RADIO_MODULE_NOT_FOUND,
        ERROR
    }

    public interface Listener {
        default void onConnectionState(ConnectionState state, String detail) {}
        default void onHello(Protocol.FirmwareVersion version) {}
        default void onDeviceState(Protocol.DeviceState state, int sMeter, boolean squelched, boolean txActive) {}
        default void onRxAudio(float[] pcm, int samples) {}
        default void onAx25Packet(byte[] packet) {}
        /** PTT state changed: by the app, the board's PTT button, or the runaway timer. */
        default void onTxChanged(boolean transmitting, String reason) {}
    }

    /** Band edges where transmitting is allowed (MHz). */
    public static final class TxLimits {
        public final float min2m, max2m, min70cm, max70cm;

        public TxLimits(float min2m, float max2m, float min70cm, float max70cm) {
            this.min2m = min2m;
            this.max2m = max2m;
            this.min70cm = min70cm;
            this.max70cm = max70cm;
        }

        /** IARU Region 1 (Europe, incl. Slovakia): 144-146 and 430-440 MHz. */
        public static final TxLimits IARU_REGION_1 = new TxLimits(144f, 146f, 430f, 440f);
        /** The Android app's defaults (US band plan): 144-148 and 420-450 MHz. */
        public static final TxLimits US = new TxLimits(144f, 148f, 420f, 450f);
    }

    private final ScheduledExecutorService radioThread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kv4p-radio");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.ExecutorService rxThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kv4p-rx");
        t.setDaemon(true);
        return t;
    });
    private final RadioModuleController radioModule = new RadioModuleController();
    private final Protocol.KissParser parser = new Protocol.KissParser(this::handleCommand, this::handleAx25);
    private final float[] rxPcm = new float[AUDIO_FRAME_SAMPLES];
    private final OpusUtils.OpusDecoderWrapper opusDecoder =
        new OpusUtils.OpusDecoderWrapper(AUDIO_SAMPLE_RATE, AUDIO_FRAME_SAMPLES);
    private final Listener listener;

    private DesktopTransport transport;
    private volatile DesktopTransport activeTransport;
    private volatile Protocol.Sender sender;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private ScheduledFuture<?> helloRetry;
    private ScheduledFuture<?> helloTimeout;
    private boolean waitingForHello;
    private volatile boolean transmitting;
    private ScheduledFuture<?> runawayTxTimer;
    private volatile TxLimits txLimits = TxLimits.IARU_REGION_1;
    private final float[] txPcm = new float[AUDIO_FRAME_SAMPLES];
    private final byte[] txFrame = new byte[2048];
    private final OpusUtils.OpusEncoderWrapper opusEncoder =
        new OpusUtils.OpusEncoderWrapper(AUDIO_SAMPLE_RATE, AUDIO_FRAME_SAMPLES);
    private volatile MicGainBoost micGainBoost = MicGainBoost.NONE;
    private float tunedTxFreq;

    public DesktopRadioSession(Listener listener) {
        this.listener = listener;
    }

    /** Serial ports that might be a kv4p HT, likely matches first. */
    public static List<SerialPortTransport.PortInfo> listPorts() {
        return SerialPortTransport.listPorts();
    }

    // ------------------------------------------------------------------ connection

    public void connect(String portName) {
        connectTransport(new SerialPortTransport(portName, rxThread), portName);
    }

    /** Package-private so tests can drive the session with a fake transport. */
    void connectTransport(DesktopTransport t, String description) {
        radioThread.execute(() -> {
            closeTransport();
            setState(ConnectionState.CONNECTING, description);
            transport = t;
            activeTransport = t;
            t.start(new DesktopTransport.Listener() {
                @Override
                public void onBytes(byte[] bytes) {
                    // Runs on kv4p-rx (or the caller's thread in tests).
                    if (activeTransport == t) parser.processBytes(bytes);
                }

                @Override
                public void onReady() {
                    radioThread.execute(() -> {
                        if (transport == t) startHandshake(t);
                    });
                }

                @Override
                public void onDisconnected() {
                    radioThread.execute(() -> {
                        if (transport == t) {
                            Log.i(TAG, "Radio unplugged");
                            closeTransport();
                            setState(ConnectionState.DISCONNECTED, "Radio unplugged");
                        }
                    });
                }

                @Override
                public void onError(Exception error) {
                    radioThread.execute(() -> {
                        if (transport == t) {
                            Log.w(TAG, "Transport error", error);
                            closeTransport();
                            setState(ConnectionState.ERROR, error.getMessage());
                        }
                    });
                }
            });
        });
    }

    public void disconnect() {
        radioThread.execute(() -> {
            if (sender != null) {
                radioModule.stop(); // Ask firmware to close audio/PTT before we go.
            }
            closeTransport();
            setState(ConnectionState.DISCONNECTED, "");
        });
    }

    @Override
    public void close() {
        disconnect();
        radioThread.shutdown();
        rxThread.shutdown();
        try {
            radioThread.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startHandshake(DesktopTransport t) {
        sender = new Protocol.Sender(new com.hoho.android.usbserial.util.SerialInputOutputManager(t::writeAsync));
        radioModule.attachSender(sender);
        waitingForHello = true;
        setState(ConnectionState.HANDSHAKE, "Waiting for HELLO from firmware");
        // Firmware sends HELLO once, right after boot, so reset the board.
        resetBoard(t);
        cancelHelloTimers();
        helloRetry = radioThread.schedule(() -> {
            if (waitingForHello && transport == t) {
                Log.w(TAG, "No HELLO yet, resetting the board again");
                resetBoard(t);
            }
        }, FIRST_HELLO_WAIT_MS, TimeUnit.MILLISECONDS);
        helloTimeout = radioThread.schedule(() -> {
            if (waitingForHello && transport == t) {
                waitingForHello = false;
                Log.w(TAG, "No HELLO after " + HELLO_TIMEOUT_MS + " ms");
                setState(ConnectionState.NO_RESPONSE,
                    "No answer from the radio. Is this the right port, and is kv4p firmware installed?");
            }
        }, HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static void resetBoard(DesktopTransport t) {
        if (t instanceof SerialPortTransport) {
            ((SerialPortTransport) t).resetBoard();
        }
    }

    /** Runs a task on the radio thread and waits for it (tests only). */
    void runOnRadioThreadAndWait(Runnable task) throws Exception {
        radioThread.submit(task).get(5, TimeUnit.SECONDS);
    }

    private void cancelHelloTimers() {
        if (helloRetry != null) helloRetry.cancel(false);
        if (helloTimeout != null) helloTimeout.cancel(false);
        helloRetry = null;
        helloTimeout = null;
    }

    private void closeTransport() {
        cancelHelloTimers();
        if (transmitting) {
            stopTx("connection closed");
        }
        waitingForHello = false;
        radioModule.detachSender();
        sender = null;
        activeTransport = null;
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    private void setState(ConnectionState next, String detail) {
        state = next;
        Log.i(TAG, "Connection state: " + next + (detail == null || detail.isEmpty() ? "" : " - " + detail));
        listener.onConnectionState(next, detail);
    }

    public ConnectionState getState() {
        return state;
    }

    // ------------------------------------------------------------------ incoming frames

    /** Called on kv4p-rx with the parser's reusable buffer: copy before handing off. */
    private void handleCommand(Protocol.RcvCommand cmd, ByteBuffer param, int offset, int len) {
        switch (cmd) {
            case COMMAND_DEBUG_INFO: Log.i(FIRMWARE_TAG, text(param, offset, len)); break;
            case COMMAND_DEBUG_DEBUG: Log.d(FIRMWARE_TAG, text(param, offset, len)); break;
            case COMMAND_DEBUG_ERROR: Log.e(FIRMWARE_TAG, text(param, offset, len)); break;
            case COMMAND_DEBUG_WARN: Log.w(FIRMWARE_TAG, text(param, offset, len)); break;
            case COMMAND_DEBUG_TRACE: Log.v(FIRMWARE_TAG, text(param, offset, len)); break;
            case COMMAND_RX_AUDIO: handleRxAudio(param, offset, len); break;
            case COMMAND_WINDOW_UPDATE:
                Protocol.WindowUpdate.from(param, offset, len).ifPresent(w -> {
                    Protocol.Sender s = sender;
                    if (s != null) s.enlargeFlowControlWindow(w.getSize());
                });
                break;
            case COMMAND_HELLO: {
                Optional<Protocol.Hello> hello = Protocol.Hello.from(param, offset, len);
                radioThread.execute(() -> handleHello(hello));
                break;
            }
            case COMMAND_DEVICE_STATE:
                Protocol.DeviceState.from(param, offset, len)
                    .ifPresent(ds -> radioThread.execute(() -> handleDeviceState(ds)));
                break;
            default:
                break;
        }
    }

    private void handleHello(Optional<Protocol.Hello> hello) {
        if (sender == null) {
            return; // Stale frame from a connection that is already closed.
        }
        waitingForHello = false;
        cancelHelloTimers();
        if (!hello.isPresent()) {
            Log.e(TAG, "HELLO with unexpected payload; firmware is probably too old");
            setState(ConnectionState.OUTDATED_FIRMWARE, "Firmware too old for this app (needs v" + MIN_FIRMWARE_VER + "+)");
            return;
        }
        Protocol.Hello h = hello.get();
        Protocol.FirmwareVersion version = h.getVersion();
        Log.i(TAG, "HELLO: " + h);
        if (version.getVer() < MIN_FIRMWARE_VER) {
            setState(ConnectionState.OUTDATED_FIRMWARE,
                "Firmware v" + version.getVer() + " is too old, needs v" + MIN_FIRMWARE_VER
                    + ". Update it with the Android app or the web flasher at kv4p.com.");
            return;
        }
        radioModule.seedFirmwareVersion(version);
        listener.onHello(version);
        if (Protocol.RadioStatus.RADIO_STATUS_NOT_FOUND.equals(version.getRadioModuleStatus())) {
            setState(ConnectionState.RADIO_MODULE_NOT_FOUND, "The ESP32 answered but can't see the SA818 radio module");
            return;
        }
        sender.setFlowControlWindow(version.getWindowSize());
        radioModule.seedFromDeviceState(h.getDeviceState());
        radioModule.markTransportReady();
        radioModule.openAudio();
        handleDeviceState(h.getDeviceState());
        setState(ConnectionState.CONNECTED, String.format(Locale.US, "Firmware v%d, %s",
            version.getVer(), version.getModuleType() == Protocol.RfModuleType.RF_SA818_UHF ? "UHF" : "VHF"));
    }

    private void handleDeviceState(Protocol.DeviceState deviceState) {
        radioModule.updateDeviceState(deviceState);
        if (radioModule.isAppliedStateInSync() && radioModule.getTxFrequency() > 0) {
            updateTxAllowed(radioModule.getTxFrequency());
        }
        listener.onDeviceState(deviceState, transmitting ? 0 : radioModule.getSMeter9Value(),
            radioModule.isSquelched(), radioModule.isDeviceTxActive());
        // The board's own PTT button, same handling as the Android app.
        if (radioModule.didPhysPttChange()) {
            if (radioModule.isPhysPttDown()) {
                if (!transmitting) startTx("PTT button on the radio");
            } else if (transmitting) {
                stopTx("PTT button on the radio released");
            }
        }
    }

    private void handleRxAudio(ByteBuffer param, int offset, int len) {
        if (param == null || !param.hasArray() || offset < 0 || len <= 0 || param.limit() < offset + len) {
            return;
        }
        int decoded = opusDecoder.decode(param.array(), offset, len, rxPcm);
        if (decoded > 0) {
            listener.onRxAudio(rxPcm, decoded);
        }
    }

    private void handleAx25(ByteBuffer param, int offset, int len) {
        if (param == null || !param.hasArray() || len < 1 || offset < 0 || param.limit() < offset + len) {
            return;
        }
        byte[] packet = new byte[len];
        System.arraycopy(param.array(), offset, packet, 0, len);
        radioThread.execute(() -> listener.onAx25Packet(packet));
    }

    private static String text(ByteBuffer param, int offset, int len) {
        if (param == null || !param.hasArray() || offset < 0 || len <= 0 || param.limit() < offset + len) {
            return "";
        }
        return new String(param.array(), offset, len, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ transmit

    /** Press PTT. Ignored (with a log line) when not connected or TX isn't allowed on this frequency. */
    public void pttDown() {
        radioThread.execute(() -> startTx("app"));
    }

    /** Release PTT. */
    public void pttUp() {
        radioThread.execute(() -> {
            if (transmitting) stopTx("app");
        });
    }

    public boolean isTransmitting() {
        return transmitting;
    }

    /** Whether the current frequency is inside the TX limits (and the firmware knows it). */
    public boolean isTxAllowed() {
        return radioModule.isTxAllowed();
    }

    public void setTxLimits(TxLimits limits) {
        radioThread.execute(() -> {
            txLimits = limits;
            float tx = radioModule.getTxFrequency();
            if (tx > 0) updateTxAllowed(tx);
        });
    }

    public TxLimits getTxLimits() {
        return txLimits;
    }

    /** "None", "Low", "Med" or "High", same as the Android settings. */
    public void setMicGainBoost(String label) {
        micGainBoost = MicGainBoost.parse(label);
    }

    /**
     * Sends one 40 ms frame of microphone audio (AUDIO_FRAME_SAMPLES samples,
     * 48 kHz, -1..1). Call from the microphone thread; may block briefly on
     * the firmware's flow-control window. Dropped when not transmitting.
     */
    public void sendTxAudio(float[] samples) {
        Protocol.Sender s = sender;
        if (!transmitting || s == null || samples.length < AUDIO_FRAME_SAMPLES) {
            return;
        }
        float gain = micGainBoost.getGain();
        for (int i = 0; i < AUDIO_FRAME_SAMPLES; i++) {
            txPcm[i] = Math.max(-1f, Math.min(1f, samples[i] * gain));
        }
        int len = opusEncoder.encode(txPcm, txFrame);
        if (len > 0) {
            s.txAudio(txFrame, len);
        }
    }

    private void startTx(String reason) {
        if (transmitting) {
            return;
        }
        if (sender == null || state != ConnectionState.CONNECTED) {
            Log.w(TAG, "PTT ignored: radio not connected");
            return;
        }
        if (!radioModule.isTxAllowed()) {
            Log.w(TAG, String.format(Locale.US,
                "PTT ignored: %.4f MHz is outside the allowed TX range",
                radioModule.getTxFrequency() > 0 ? radioModule.getTxFrequency() : tunedTxFreq));
            listener.onTxChanged(false, "TX not allowed on this frequency");
            return;
        }
        transmitting = true;
        radioModule.pttDown();
        if (runawayTxTimer != null) runawayTxTimer.cancel(false);
        runawayTxTimer = radioThread.schedule(() -> {
            if (transmitting) {
                Log.w(TAG, "Runaway TX timeout (" + RUNAWAY_TX_TIMEOUT_SEC + " s), PTT released");
                stopTx("TX time limit reached");
            }
        }, RUNAWAY_TX_TIMEOUT_SEC, TimeUnit.SECONDS);
        Log.i(TAG, "TX on (" + reason + ")");
        listener.onTxChanged(true, reason);
    }

    private void stopTx(String reason) {
        transmitting = false;
        if (runawayTxTimer != null) {
            runawayTxTimer.cancel(false);
            runawayTxTimer = null;
        }
        if (sender != null) {
            radioModule.pttUp();
        }
        Log.i(TAG, "TX off (" + reason + ")");
        listener.onTxChanged(false, reason);
    }

    private void updateTxAllowed(float txFrequency) {
        TxLimits l = txLimits;
        boolean uhf = radioModule.getRfModuleType() == Protocol.RfModuleType.RF_SA818_UHF;
        float min = uhf ? l.min70cm : l.min2m;
        float max = uhf ? l.max70cm : l.max2m;
        float half = radioModule.getHalfBandwidthMhz();
        // Same rule as the Android app: the whole channel must fit inside the band.
        radioModule.setTxAllowed(txFrequency >= min + half && txFrequency <= max - half);
    }

    // ------------------------------------------------------------------ controls

    /** Simplex tune, same as the Android app's frequency entry (no tones, no memory). */
    public void tuneSimplex(float freqMhz) {
        radioThread.execute(() -> {
            radioModule.beginUpdate();
            try {
                radioModule.setMemoryId(-1);
                radioModule.setTxFrequency(freqMhz);
                radioModule.setRxFrequency(freqMhz);
                radioModule.setTxTone((byte) 0);
                radioModule.setRxTone((byte) 0);
                tunedTxFreq = freqMhz;
                updateTxAllowed(freqMhz);
            } finally {
                radioModule.endUpdate();
            }
        });
    }

    public void setSquelch(int squelch) {
        radioThread.execute(() -> radioModule.setSquelch(Math.max(0, Math.min(8, squelch))));
    }

    /** "25kHz" or "12.5kHz", same strings as the Android settings. */
    public void setBandwidth(String bandwidth) {
        radioThread.execute(() -> radioModule.setBandwidth(bandwidth));
    }

    public void setFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        radioThread.execute(() -> radioModule.setFilters(emphasis, highpass, lowpass));
    }

    public void setHighPower(boolean high) {
        radioThread.execute(() -> radioModule.setHighPower(high));
    }

    public float getMinRadioFreq() {
        return radioModule.getMinRadioFreq();
    }

    public float getMaxRadioFreq() {
        return radioModule.getMaxRadioFreq();
    }
}
