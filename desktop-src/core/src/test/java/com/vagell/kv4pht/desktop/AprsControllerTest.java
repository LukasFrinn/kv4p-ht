package com.vagell.kv4pht.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.Digipeater;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.Parser;
import com.vagell.kv4pht.javAX25.ax25.Packet;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AprsControllerTest {

    private final List<byte[]> sent = new CopyOnWriteArrayList<>();
    private final List<AprsController.Entry> entries = new CopyOnWriteArrayList<>();
    private final List<AprsController.Entry> updated = new CopyOnWriteArrayList<>();
    private volatile boolean canSend = true;
    private AprsController aprs;

    @Before
    public void setUp() {
        aprs = new AprsController(new AprsController.Sink() {
            @Override public boolean sendAx25(byte[] frame) {
                if (!canSend) return false;
                sent.add(frame);
                return true;
            }
            @Override public void onEntry(AprsController.Entry e) { entries.add(e); }
            @Override public void onEntryUpdated(AprsController.Entry e) { updated.add(e); }
        });
        aprs.setCallsign("om0tst");
        aprs.setAckDelayMs(0);
    }

    @After
    public void tearDown() {
        aprs.close();
    }

    /** AX.25 frame as the firmware would deliver it (no CRC). */
    private static byte[] frame(String from, List<Digipeater> path, byte[] payload) {
        return new Packet(new APRSPacket(from, "APRS", path, payload).toAX25Frame()).bytesWithoutCRC();
    }

    private static String decode(byte[] frame) throws Exception {
        APRSPacket p = Parser.parseAX25(frame, 0, frame.length);
        return p.getSourceCall() + ">" + p.getDestinationCall() + " "
            + new String(p.getPayload().getRawBytes(), StandardCharsets.UTF_8);
    }

    private static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    }

    @Test
    public void messageToMeIsShownAndAcked() throws Exception {
        aprs.handleAx25(frame("OK1ABC-7", AprsController.DEFAULT_DIGIPEATERS,
            MessagePacket.createMessagePayload("OM0TST", "ahoj", "42")));

        assertEquals(1, entries.size());
        AprsController.Entry e = entries.get(0);
        assertEquals(AprsController.Kind.MESSAGE, e.kind);
        assertEquals("OK1ABC-7", e.from);
        assertEquals("ahoj", e.text);
        assertTrue(e.toMe);
        assertEquals(42, e.msgNum);

        waitFor(() -> !sent.isEmpty());
        assertEquals(1, sent.size());
        String ack = decode(sent.get(0));
        assertTrue(ack, ack.startsWith("OM0TST>"));
        assertTrue(ack, ack.contains(":OK1ABC-7 :ack42")); // addressee padded to 9 chars
    }

    @Test
    public void messageToSomeoneElseIsNotAcked() throws Exception {
        aprs.handleAx25(frame("OK1ABC", AprsController.DEFAULT_DIGIPEATERS,
            MessagePacket.createMessagePayload("OM1XYZ", "hello", "7")));
        assertFalse(entries.get(0).toMe);
        Thread.sleep(100);
        assertTrue(sent.isEmpty());
    }

    @Test
    public void outgoingMessageIsMarkedWhenAcked() throws Exception {
        AprsController.Entry out = aprs.sendMessage("ok1abc", "test");
        assertNotNull(out);
        assertEquals("OK1ABC", out.to);
        String tx = decode(sent.get(0));
        assertTrue(tx, tx.contains(":OK1ABC   :test{" + out.msgNum));

        aprs.handleAx25(frame("OK1ABC", AprsController.DEFAULT_DIGIPEATERS,
            MessagePacket.createMessagePayload("OM0TST", "ack" + out.msgNum, null)));
        assertTrue(out.acked);
        assertEquals(List.of(out), updated);
        assertEquals("the ack itself is not listed", 1, entries.size());
    }

    @Test
    public void messageIsNotListedWhenRadioCannotSend() {
        canSend = false;
        assertNull(aprs.sendMessage("OK1ABC", "test"));
        assertTrue(entries.isEmpty());
    }

    @Test
    public void positionBeaconIsParsedBackAsOurPosition() throws Exception {
        double[] pos = AprsController.locatorToLatLon("JN88ne");
        aprs.setMyPosition(pos[0], pos[1]);
        AprsController.Entry e = aprs.sendPositionBeacon(" kv4p desktop");
        assertNotNull(e);

        aprs.setCallsign("OM9OTH"); // so our own beacon isn't treated specially
        aprs.handleAx25(sent.get(0));
        AprsController.Entry heard = entries.get(entries.size() - 1);
        assertEquals(AprsController.Kind.POSITION, heard.kind);
        assertEquals("OM0TST", heard.from);
        assertEquals(pos[0], heard.lat, 0.01);
        assertEquals(pos[1], heard.lon, 0.01);
    }

    @Test
    public void beaconNeedsPosition() {
        assertNull(aprs.sendPositionBeacon(null));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void digipeatsWide1Once() throws Exception {
        aprs.setDigipeat(true);
        byte[] in = frame("OK1ABC", List.of(new Digipeater("WIDE1-1"), new Digipeater("WIDE2-1")),
            ">status".getBytes(StandardCharsets.UTF_8));
        // Digipeater(String) marks the hop as used; the station sends it unused.
        APRSPacket p = Parser.parseAX25(in, 0, in.length);
        p.getDigipeaters().forEach(d -> d.setUsed(false));
        byte[] fresh = new Packet(p.toAX25Frame()).bytesWithoutCRC();

        aprs.handleAx25(fresh);
        assertEquals(1, sent.size());
        APRSPacket out = Parser.parseAX25(sent.get(0), 0, sent.get(0).length);
        assertEquals("OK1ABC", out.getSourceCall());
        assertEquals("OM0TST", out.getDigipeaters().get(0).getCallsign());
        assertTrue(out.getDigipeaters().get(0).isUsed());

        aprs.handleAx25(fresh); // heard again: deduplicated, not repeated
        assertEquals(1, sent.size());
    }

    @Test
    public void locatorConversion() {
        double[] ll = AprsController.locatorToLatLon("JN88");
        assertEquals(48.5, ll[0], 1e-9);
        assertEquals(17.0, ll[1], 1e-9);
        assertNull(AprsController.locatorToLatLon("not a locator"));
        assertEquals(0.0, AprsController.distanceKm(48, 17, 48, 17), 1e-9);
        assertEquals(111.2, AprsController.distanceKm(48, 17, 49, 17), 0.5);
    }
}
