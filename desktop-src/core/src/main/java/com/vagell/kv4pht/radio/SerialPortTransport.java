package com.vagell.kv4pht.radio;

import android.util.Log;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * USB serial transport for Linux and macOS (desktop counterpart of
 * UsbSerialRadioTransport on Android), built on jSerialComm.
 */
public final class SerialPortTransport implements DesktopTransport {
    private static final String TAG = SerialPortTransport.class.getSimpleName();
    static final int BAUD_RATE = 115200; // Matches Serial.begin() in the firmware.

    private final String portName;
    private final Executor callbackExecutor;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kv4p-serial-writer");
        t.setDaemon(true);
        return t;
    });
    private volatile SerialPort port;
    private volatile Listener listener;

    /**
     * @param portName         system port path, e.g. /dev/ttyUSB0 or /dev/cu.usbserial-0001
     * @param callbackExecutor executor that all listener callbacks are posted to
     */
    SerialPortTransport(String portName, Executor callbackExecutor) {
        this.portName = portName;
        this.callbackExecutor = callbackExecutor;
    }

    /** Describes a serial port the user can pick. */
    public static final class PortInfo {
        public final String systemPath;
        public final String description;
        public final boolean likelyKv4p;

        PortInfo(String systemPath, String description, boolean likelyKv4p) {
            this.systemPath = systemPath;
            this.description = description;
            this.likelyKv4p = likelyKv4p;
        }

        @Override
        public String toString() {
            return systemPath + " (" + description + ")";
        }
    }

    /**
     * Lists serial ports, best guesses first. kv4p HT boards use a CP2102N
     * (Silicon Labs, VID 0x10C4); older dev-board builds may use a CH340 (VID 0x1A86).
     */
    public static List<PortInfo> listPorts() {
        List<PortInfo> result = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            String path = p.getSystemPortPath();
            // On macOS each device shows up as both tty.* and cu.*; cu.* is the one to open.
            if (path.startsWith("/dev/tty.") ) {
                continue;
            }
            int vid = p.getVendorID();
            boolean likely = vid == 0x10C4 || vid == 0x1A86 || vid == 0x303A;
            String desc = p.getDescriptivePortName();
            if (vid > 0) {
                desc += String.format(Locale.US, " [%04x:%04x]", vid, p.getProductID());
            }
            result.add(new PortInfo(path, desc, likely));
        }
        result.sort((a, b) -> Boolean.compare(b.likelyKv4p, a.likelyKv4p));
        return result;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        SerialPort p = SerialPort.getCommPort(portName);
        p.setComPortParameters(BAUD_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        p.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 2000);
        if (!p.openPort()) {
            int err = p.getLastErrorCode();
            String hint = err == 13 || err == 1
                ? " (permission denied - on Linux add your user to the 'dialout' group and log in again)"
                : "";
            post(l -> l.onError(new java.io.IOException(
                "Could not open " + portName + ", error " + err + hint)));
            return;
        }
        port = p;
        p.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                    post(Listener::onDisconnected);
                    return;
                }
                SerialPort current = port;
                if (current == null) {
                    return;
                }
                int available = current.bytesAvailable();
                if (available <= 0) {
                    return;
                }
                byte[] buf = new byte[available];
                int read = current.readBytes(buf, buf.length);
                if (read > 0) {
                    byte[] data = read == buf.length ? buf : java.util.Arrays.copyOf(buf, read);
                    post(l -> l.onBytes(data));
                } else if (read < 0) {
                    post(l -> l.onError(new java.io.IOException("Serial read failed on " + portName)));
                }
            }
        });
        Log.i(TAG, "Opened " + portName + " at " + BAUD_RATE + " baud");
        post(Listener::onReady);
    }

    /**
     * Resets the ESP32 through the board's DTR/RTS auto-reset circuit (same
     * sequence as esptool's hard reset). The firmware only sends HELLO right
     * after boot, so a desktop host has to reset the board to start a session.
     */
    void resetBoard() {
        SerialPort p = port;
        if (p == null) {
            return;
        }
        Log.i(TAG, "Resetting ESP32 via RTS");
        p.clearDTR();           // keep IO0 high -> normal boot, not bootloader
        p.setRTS();             // EN low -> hold in reset
        sleep(120);
        p.clearRTS();           // release reset
    }

    @Override
    public void writeAsync(byte[] bytes) {
        SerialPort p = port;
        if (p == null || writer.isShutdown()) {
            return;
        }
        writer.execute(() -> {
            SerialPort current = port;
            if (current == null) {
                return;
            }
            int written = current.writeBytes(bytes, bytes.length);
            if (written != bytes.length) {
                post(l -> l.onError(new java.io.IOException(
                    "Serial write failed (" + written + "/" + bytes.length + ")")));
            }
        });
    }

    @Override
    public boolean isReady() {
        return port != null;
    }

    @Override
    public String getName() {
        return "USB serial " + portName;
    }

    @Override
    public void close() {
        listener = null;
        writer.shutdownNow();
        SerialPort p = port;
        port = null;
        if (p != null) {
            try {
                p.removeDataListener();
                p.closePort();
            } catch (RuntimeException ignored) {
                // Best-effort teardown.
            }
        }
    }

    private interface ListenerCall {
        void call(Listener l);
    }

    private void post(ListenerCall call) {
        callbackExecutor.execute(() -> {
            Listener l = listener;
            if (l != null) {
                call.call(l);
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
