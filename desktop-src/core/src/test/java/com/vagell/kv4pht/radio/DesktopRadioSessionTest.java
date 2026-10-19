package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives DesktopRadioSession with a fake transport that speaks the firmware
 * side of the KISS/kv4p protocol, so the handshake can be checked without hardware.
 */
public class DesktopRadioSessionTest {

    private static final int CMD_HELLO = 0x06;
    private static final int CMD_RX_AUDIO = 0x07; // Opus, release v2.0.0.1
    private static final int CMD_HOST_DESIRED_STATE = 0x0D;
    private static final int CMD_HOST_TX_AUDIO = 0x07; // release v2.0.0.1

    private final List<DesktopRadioSession.ConnectionState> states = new CopyOnWriteArrayList<>();
    private final List<Integer> rxAudioSamples = new CopyOnWriteArrayList<>();
    private final List<Boolean> txEvents = new CopyOnWriteArrayList<>();
    private volatile Protocol.FirmwareVersion helloVersion;
    private FakeTransport transport;
    private DesktopRadioSession session;

    @Before
    public void setUp() throws Exception {
        session = new DesktopRadioSession(new DesktopRadioSession.Listener() {
            @Override
            public void onConnectionState(DesktopRadioSession.ConnectionState state, String detail) {
                states.add(state);
            }

            @Override
            public void onHello(Protocol.FirmwareVersion version) {
                helloVersion = version;
            }

            @Override
            public void onTxChanged(boolean transmitting, String reason) {
                txEvents.add(transmitting);
            }

            @Override
            public void onRxAudio(float[] pcm, int samples) {
                rxAudioSamples.add(samples);
            }
        });
        transport = new FakeTransport();
        session.connectTransport(transport, "fake");
        session.runOnRadioThreadAndWait(() -> { });
    }

    @After
    public void tearDown() {
        session.close();
    }

    @Test
    public void handshakeWithCurrentFirmwareConnectsAndOpensAudio() throws Exception {
        assertEquals(DesktopRadioSession.ConnectionState.HANDSHAKE, last(states));

        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'f')));

        assertEquals(DesktopRadioSession.ConnectionState.CONNECTED, last(states));
        assertNotNull(helloVersion);
        assertEquals(Protocol.RfModuleType.RF_SA818_VHF, helloVersion.getModuleType());

        byte[] desired = lastDesiredState();
        int flags = ByteBuffer.wrap(desired).order(ByteOrder.LITTLE_ENDIAN).getShort(8) & 0xFFFF;
        assertTrue("RX audio should be opened", (flags & Protocol.HOST_STATE_RX_AUDIO_OPEN) != 0);
        assertTrue("TX must not be allowed by default", (flags & Protocol.HOST_STATE_TX_ALLOWED) == 0);
    }

    @Test
    public void tuneSendsFrequencyToFirmware() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'f')));
        session.tuneSimplex(146.52f);
        session.runOnRadioThreadAndWait(() -> { });

        ByteBuffer desired = ByteBuffer.wrap(lastDesiredState()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(146.52f, desired.getFloat(11), 0.0001f); // freqTx
        assertEquals(146.52f, desired.getFloat(15), 0.0001f); // freqRx
        assertEquals(-1, desired.getInt(4));                  // memoryId
    }

    @Test
    public void oldFirmwareIsRejected() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER - 1, 'f')));
        assertEquals(DesktopRadioSession.ConnectionState.OUTDATED_FIRMWARE, last(states));
    }

    @Test
    public void missingRadioModuleIsReported() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'x')));
        assertEquals(DesktopRadioSession.ConnectionState.RADIO_MODULE_NOT_FOUND, last(states));
    }

    @Test
    public void opusRxAudioIsDecoded() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'f')));
        float[] pcm = new float[DesktopRadioSession.AUDIO_FRAME_SAMPLES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (float) (0.25 * Math.sin(i * 2 * Math.PI * 1000 / DesktopRadioSession.AUDIO_SAMPLE_RATE));
        }
        OpusUtils.OpusEncoderWrapper encoder = new OpusUtils.OpusEncoderWrapper(
            DesktopRadioSession.AUDIO_SAMPLE_RATE, DesktopRadioSession.AUDIO_FRAME_SAMPLES);
        byte[] opus = new byte[1024];
        int len = encoder.encode(pcm, opus);

        // Split the frame across two reads, like a real serial port may.
        byte[] frame = vendorFrame(CMD_RX_AUDIO, java.util.Arrays.copyOf(opus, len));
        int half = frame.length / 2;
        feed(java.util.Arrays.copyOfRange(frame, 0, half));
        feed(java.util.Arrays.copyOfRange(frame, half, frame.length));

        assertEquals(List.of(DesktopRadioSession.AUDIO_FRAME_SAMPLES), rxAudioSamples);
    }

    @Test
    public void pttInsideBandRequestsTxAndReleases() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'f')));
        session.tuneSimplex(145.5f);
        session.pttDown();
        session.runOnRadioThreadAndWait(() -> { });
        assertTrue(session.isTransmitting());
        assertTrue("PTT flag sent", (desiredFlags() & Protocol.HOST_STATE_PTT_REQUESTED) != 0);
        assertEquals(List.of(true), txEvents);

        session.pttUp();
        session.runOnRadioThreadAndWait(() -> { });
        assertTrue(!session.isTransmitting());
        assertTrue("PTT flag cleared", (desiredFlags() & Protocol.HOST_STATE_PTT_REQUESTED) == 0);
    }

    @Test
    public void pttOutsideRegion1BandIsRefused() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'f')));
        session.tuneSimplex(147.0f); // allowed in the US, not in IARU Region 1
        session.pttDown();
        session.runOnRadioThreadAndWait(() -> { });
        assertTrue(!session.isTransmitting());
        assertTrue((desiredFlags() & Protocol.HOST_STATE_PTT_REQUESTED) == 0);
        assertTrue((desiredFlags() & Protocol.HOST_STATE_TX_ALLOWED) == 0);
    }

    @Test
    public void txAudioIsSentAsOpusWhileTransmitting() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'f')));
        session.tuneSimplex(145.5f);
        float[] mic = new float[DesktopRadioSession.AUDIO_FRAME_SAMPLES];
        session.sendTxAudio(mic);                 // not transmitting: dropped
        assertEquals(0, countFrames(CMD_HOST_TX_AUDIO));
        session.pttDown();
        session.runOnRadioThreadAndWait(() -> { });
        session.sendTxAudio(mic);
        session.sendTxAudio(mic);
        assertEquals(2, countFrames(CMD_HOST_TX_AUDIO));
    }

    // ------------------------------------------------------------------ helpers

    private int desiredFlags() {
        return ByteBuffer.wrap(lastDesiredState()).order(ByteOrder.LITTLE_ENDIAN).getShort(8) & 0xFFFF;
    }

    private int countFrames(int command) {
        int n = 0;
        for (byte[] frame : transport.writes) {
            byte[] raw = unescape(frame);
            if (raw.length > 6 && (raw[0] & 0xFF) == 0x06 && (raw[6] & 0xFF) == command) n++;
        }
        return n;
    }

    private void feed(byte[] bytes) throws Exception {
        session.runOnRadioThreadAndWait(() -> transport.listener.onBytes(bytes));
        session.runOnRadioThreadAndWait(() -> { });
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }

    /** Payload of the most recent COMMAND_HOST_DESIRED_STATE frame the host wrote. */
    private byte[] lastDesiredState() {
        byte[] found = null;
        for (byte[] frame : transport.writes) {
            byte[] raw = unescape(frame);
            // raw: 0x06 'K' 'V' '4' 'P' version cmd payload...
            if (raw.length > 7 && (raw[0] & 0xFF) == 0x06 && (raw[6] & 0xFF) == CMD_HOST_DESIRED_STATE) {
                found = java.util.Arrays.copyOfRange(raw, 7, raw.length);
            }
        }
        assertNotNull("host never sent a desired state", found);
        return found;
    }

    private static byte[] hello(int firmwareVer, char moduleStatus) {
        ByteBuffer b = ByteBuffer.allocate(17 + 26).order(ByteOrder.LITTLE_ENDIAN);
        // FirmwareVersion
        b.putShort((short) firmwareVer);
        b.put((byte) moduleStatus);
        b.putInt(4096);         // flow-control window
        b.put((byte) 0);        // RF_SA818_VHF
        b.putFloat(134.0f);
        b.putFloat(174.0f);
        b.put((byte) 0x03);     // has H/L switch + physical PTT
        // DeviceState: firmware has no radio config yet
        b.putInt(0);            // applied sequence
        b.putInt(-1);           // memory id
        b.putShort((short) 0);  // flags
        b.put((byte) 1);        // bw
        b.putFloat(0f);
        b.putFloat(0f);
        b.put((byte) 0).put((byte) 0).put((byte) 0);
        b.put((byte) moduleStatus);
        b.put((byte) 2);        // DEVICE_MODE_STOPPED
        b.put((byte) 0);        // last error
        b.put((byte) 0);        // rssi
        return b.array();
    }

    private static byte[] vendorFrame(int command, byte[] payload) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(0x06);
        raw.writeBytes(new byte[]{'K', 'V', '4', 'P', 0x01, (byte) command});
        raw.writeBytes(payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xC0);
        for (byte value : raw.toByteArray()) {
            int v = value & 0xFF;
            if (v == 0xC0) {
                out.write(0xDB);
                out.write(0xDC);
            } else if (v == 0xDB) {
                out.write(0xDB);
                out.write(0xDD);
            } else {
                out.write(v);
            }
        }
        out.write(0xC0);
        return out.toByteArray();
    }

    private static byte[] unescape(byte[] frame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean escape = false;
        for (byte value : frame) {
            int v = value & 0xFF;
            if (v == 0xC0) {
                continue;
            }
            if (escape) {
                out.write(v == 0xDC ? 0xC0 : 0xDB);
                escape = false;
            } else if (v == 0xDB) {
                escape = true;
            } else {
                out.write(v);
            }
        }
        return out.toByteArray();
    }

    private static final class FakeTransport implements DesktopTransport {
        final List<byte[]> writes = new CopyOnWriteArrayList<>();
        Listener listener;

        @Override public void start(Listener listener) {
            this.listener = listener;
            listener.onReady();
        }
        @Override public void close() { }
        @Override public boolean isReady() { return true; }
        @Override public void writeAsync(byte[] bytes) { writes.add(bytes); }
        @Override public String getName() { return "fake"; }
    }
}
