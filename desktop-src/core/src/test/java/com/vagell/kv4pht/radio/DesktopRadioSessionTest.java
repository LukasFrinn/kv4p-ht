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
    private final List<Integer> activeMemories = new CopyOnWriteArrayList<>();
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
            public void onActiveMemory(int memoryId) {
                activeMemories.add(memoryId);
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
        // connect -> onReady -> startHandshake hops through the radio thread
        // several times; wait until the handshake has actually started.
        long deadline = System.currentTimeMillis() + 5000;
        while (states.isEmpty() || last(states) != DesktopRadioSession.ConnectionState.HANDSHAKE) {
            assertTrue("handshake did not start", System.currentTimeMillis() < deadline);
            session.runOnRadioThreadAndWait(() -> { });
        }
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

    @Test
    public void tuneMemoryAppliesOffsetAndTones() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'f')));
        com.vagell.kv4pht.desktop.Memory m = new com.vagell.kv4pht.desktop.Memory();
        m.id = 7;
        m.frequency = "145.6000";
        m.offset = com.vagell.kv4pht.desktop.Memory.OFFSET_DOWN;
        m.offsetKhz = 600;
        m.txTone = "88.5";
        session.tuneMemory(m);
        session.runOnRadioThreadAndWait(() -> { });

        ByteBuffer d = ByteBuffer.wrap(lastDesiredState()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(7, d.getInt(4));               // memoryId
        assertEquals(145.0f, d.getFloat(11), 1e-4f); // freqTx with -600 kHz
        assertEquals(145.6f, d.getFloat(15), 1e-4f); // freqRx
        assertEquals(8, d.get(19));                  // ctcssTx code for 88.5
        assertEquals(0, d.get(21));                  // ctcssRx none
        assertEquals(List.of(7), activeMemories);
    }

    @Test
    public void scanAdvancesOnlyWhileSquelched() throws Exception {
        feed(vendorFrame(CMD_HELLO, hello(DesktopRadioSession.MIN_FIRMWARE_VER, 'f')));
        List<com.vagell.kv4pht.desktop.Memory> list = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            com.vagell.kv4pht.desktop.Memory m = new com.vagell.kv4pht.desktop.Memory();
            m.id = i;
            m.frequency = String.format(java.util.Locale.US, "145.%d000", i);
            m.skipDuringScan = i == 2;
            list.add(m);
        }
        session.startScan(list);
        session.runOnRadioThreadAndWait(() -> { });
        assertTrue(session.isScanning());
        assertEquals(1, ByteBuffer.wrap(lastDesiredState()).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        assertEquals("squelch 0 is raised to 1 while scanning", 1, lastDesiredState()[20]);

        // Firmware reports memory 1 applied with an open squelch (signal): stay.
        feed(vendorFrame(CMD_DEVICE_STATE, deviceState(1, false)));
        Thread.sleep(400);
        session.runOnRadioThreadAndWait(() -> { });
        assertEquals(1, ByteBuffer.wrap(lastDesiredState()).order(ByteOrder.LITTLE_ENDIAN).getInt(4));

        // Squelched: after 250 ms it moves on, skipping memory 2.
        feed(vendorFrame(CMD_DEVICE_STATE, deviceState(1, true)));
        Thread.sleep(400);
        session.runOnRadioThreadAndWait(() -> { });
        assertEquals(3, ByteBuffer.wrap(lastDesiredState()).order(ByteOrder.LITTLE_ENDIAN).getInt(4));

        session.pttDown(); // PTT stops the scan on the current channel
        session.runOnRadioThreadAndWait(() -> { });
        assertTrue(!session.isScanning());
        assertEquals("squelch restored", 0, lastDesiredState()[20]);
    }

    // ------------------------------------------------------------------ helpers

    private static final int CMD_DEVICE_STATE = 0x0B;

    /** A DEVICE_STATE as the firmware sends it after applying our latest desired state. */
    private byte[] deviceState(int memoryId, boolean squelched) {
        ByteBuffer want = ByteBuffer.wrap(lastDesiredState()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer b = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(want.getInt(0));                      // applied sequence
        b.putInt(memoryId);
        int flags = (want.getShort(8) & 0xFFFF) | (squelched ? Protocol.DEVICE_STATE_SQUELCHED : 0);
        b.putShort((short) flags);
        b.put(want.get(10));
        b.putFloat(want.getFloat(11));
        b.putFloat(want.getFloat(15));
        b.put(want.get(19)).put(want.get(20)).put(want.get(21));
        b.put((byte) 'f').put((byte) 1).put((byte) 0).put((byte) 0);
        return b.array();
    }

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
