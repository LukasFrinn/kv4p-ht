package com.vagell.kv4pht.desktop;

import android.util.Log;

import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.APRSTypes;
import com.vagell.kv4pht.aprs.parser.Digipeater;
import com.vagell.kv4pht.aprs.parser.InformationField;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.ObjectField;
import com.vagell.kv4pht.aprs.parser.Parser;
import com.vagell.kv4pht.aprs.parser.Position;
import com.vagell.kv4pht.aprs.parser.PositionField;
import com.vagell.kv4pht.aprs.parser.ThirdPartyField;
import com.vagell.kv4pht.aprs.parser.WeatherField;
import com.vagell.kv4pht.javAX25.ax25.Packet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Desktop APRS logic, ported from the released Android app (RadioAudioService +
 * MainActivity.handleChatPacket). The firmware does the AFSK modem work; this
 * class parses received AX.25 frames with the shared upstream parser, keeps
 * track of chat messages and acks, sends messages and position beacons, and
 * optionally acts as a WIDE1 fill-in digipeater, all like the Android app.
 */
public final class AprsController {
    private static final String TAG = AprsController.class.getSimpleName();

    /** Same path the Android app uses. */
    public static final List<Digipeater> DEFAULT_DIGIPEATERS =
        List.of(new Digipeater("WIDE1-1"), new Digipeater("WIDE2-1"));
    private static final int MAX_MESSAGE_NUM = 99999;
    private static final long DIGIPEAT_DEDUP_MS = 28_000L;

    public enum Kind { POSITION, MESSAGE, ACK, WEATHER, OBJECT, BEACON_SENT, OTHER }

    /** One line in the APRS list. Fields not relevant for a kind are null. */
    public static final class Entry {
        public final long timeMillis = System.currentTimeMillis();
        public Kind kind = Kind.OTHER;
        public String from;
        public String to;
        public String relay;
        public String text;
        public String comment;
        public String objectName;
        public Double lat;
        public Double lon;
        public String weather;
        public int msgNum = -1;
        public boolean outgoing;
        public boolean toMe;
        public volatile boolean acked;

        @Override
        public String toString() {
            return kind + " " + from + (to != null ? " > " + to : "") + (text != null ? ": " + text : "")
                + (comment != null ? " (" + comment + ")" : "");
        }
    }

    /** Where frames go out and entries go to. */
    public interface Sink {
        /** Transmit an AX.25 frame (no CRC). Returns false if the radio can't transmit right now. */
        boolean sendAx25(byte[] frameWithoutCrc);
        void onEntry(Entry entry);
        /** An existing entry changed (e.g. an outgoing message got acked). */
        default void onEntryUpdated(Entry entry) {}
    }

    private final Sink sink;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> digipeatDedup = new HashMap<>();
    private final List<Entry> pendingOutgoing = new ArrayList<>();
    private int messageNumber = new Random().nextInt(MAX_MESSAGE_NUM);
    private long ackDelayMs = 1000;

    private volatile String callsign = "";
    private volatile boolean digipeat;
    private volatile Double myLat;
    private volatile Double myLon;
    private volatile boolean approximatePosition;
    private volatile char symbolCode = '-'; // house; the desktop isn't a phone

    public AprsController(Sink sink) {
        this.sink = sink;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kv4p-aprs");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------ settings

    public void setCallsign(String callsign) {
        this.callsign = callsign == null ? "" : callsign.trim().toUpperCase(Locale.ROOT);
    }

    public String getCallsign() {
        return callsign;
    }

    public void setDigipeat(boolean enabled) {
        this.digipeat = enabled;
    }

    public void setMyPosition(Double lat, Double lon) {
        this.myLat = lat;
        this.myLon = lon;
    }

    public void setApproximatePosition(boolean approximate) {
        this.approximatePosition = approximate;
    }

    /** APRS primary-table symbol code, e.g. '-' house, '>' car, '[' person. */
    public void setSymbolCode(char code) {
        this.symbolCode = code;
    }

    void setAckDelayMs(long ms) {
        this.ackDelayMs = ms;
    }

    public void close() {
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ receive

    /** Handle one AX.25 frame from the firmware. Call from a single thread. */
    public synchronized void handleAx25(byte[] frame) {
        APRSPacket packet;
        try {
            packet = Parser.parseAX25(frame, 0, frame.length);
        } catch (Exception e) {
            Log.d(TAG, "Unable to parse an APRS packet, skipping.");
            return;
        }
        String dedupKey = packet.getSourceCall() + "|" + packet.getDestinationCall() + "|"
            + Base64.getEncoder().encodeToString(packet.getPayload().getRawBytes());
        if (isRecentlyDigipeated(dedupKey)) {
            return; // Our own retransmission (or a duplicate of one) heard back.
        }
        if (digipeat && !callsign.isEmpty()) {
            maybeDigipeat(packet, dedupKey);
        }
        Entry entry = toEntry(packet);
        if (entry == null) {
            return;
        }
        if (entry.kind == Kind.ACK) {
            markAcked(entry);
            return;
        }
        sink.onEntry(entry);
        if (entry.kind == Kind.MESSAGE && entry.toMe && entry.msgNum != -1) {
            String to = entry.from.toUpperCase(Locale.ROOT);
            String num = String.valueOf(entry.msgNum);
            scheduler.schedule(() -> sendAck(to, num), ackDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    private Entry toEntry(APRSPacket raw) {
        Entry e = new Entry();
        InformationField info = raw.getPayload();
        APRSPacket packet = raw;

        ThirdPartyField thirdParty = (ThirdPartyField) info.getAprsData(APRSTypes.T_THIRDPARTY);
        if (thirdParty != null) {
            e.relay = raw.getSourceCall();
            APRSPacket inner = thirdParty.getInnerPacket();
            if (inner == null || inner.hasFault()) {
                e.from = raw.getSourceCall();
                e.comment = "Raw: " + new String(info.getRawBytes(), StandardCharsets.UTF_8);
                return e;
            }
            packet = inner;
            info = inner.getPayload();
        }

        e.from = packet.getSourceCall();
        WeatherField wx = (WeatherField) info.getAprsData(APRSTypes.T_WX);
        PositionField pos = (PositionField) info.getAprsData(APRSTypes.T_POSITION);
        ObjectField obj = (ObjectField) info.getAprsData(APRSTypes.T_OBJECT);

        if (pos != null) {
            e.kind = Kind.POSITION;
            e.lat = pos.getPosition().getLatitude();
            e.lon = pos.getPosition().getLongitude();
        }

        String comment = packet.getComment();
        if (isBlank(comment)) comment = info.getComment();
        if (isBlank(comment) && pos != null) comment = pos.getComment();
        if (isBlank(comment) && obj != null) comment = obj.getComment();
        if (isBlank(comment) && wx != null) comment = wx.getComment();
        if (!isBlank(comment)) e.comment = comment.trim();

        if (wx != null) {
            e.kind = Kind.WEATHER;
            e.weather = describeWeather(wx);
        } else if (info.getDataTypeIdentifier() == ':') {
            MessagePacket msg = new MessagePacket(info.getRawBytes(), packet.getDestinationCall());
            e.to = msg.getTargetCallsign() == null ? null : msg.getTargetCallsign().trim();
            e.msgNum = parseMsgNum(msg.getMessageNumber());
            if (msg.isAck()) {
                if (e.msgNum == -1) {
                    return null;
                }
                e.kind = Kind.ACK;
                return e;
            }
            e.kind = Kind.MESSAGE;
            e.text = msg.getMessageBody();
            e.toMe = !callsign.isEmpty() && e.to != null && e.to.equalsIgnoreCase(callsign);
        } else if (info.getDataTypeIdentifier() == ';') {
            e.kind = Kind.OBJECT;
            if (obj != null) e.objectName = obj.getObjectName();
        }

        if (packet.hasFault() || (e.kind == Kind.OTHER && isBlank(e.comment))) {
            e.comment = "Raw: " + new String(info.getRawBytes(), StandardCharsets.UTF_8);
        }
        return e;
    }

    private void markAcked(Entry ack) {
        synchronized (pendingOutgoing) {
            for (Entry out : pendingOutgoing) {
                if (!out.acked && out.msgNum == ack.msgNum && ack.from.equalsIgnoreCase(out.to)) {
                    out.acked = true;
                    Log.i(TAG, "Message #" + out.msgNum + " to " + out.to + " acknowledged");
                    sink.onEntryUpdated(out);
                }
            }
            pendingOutgoing.removeIf(o -> o.acked);
        }
    }

    // ------------------------------------------------------------------ transmit

    /**
     * Sends a chat message; [to] empty means the BLN1CQ bulletin (like the Android app).
     * Returns the outgoing entry, or null if it could not be sent.
     */
    public Entry sendMessage(String to, String text) {
        if (callsign.isEmpty()) {
            Log.w(TAG, "Set your callsign before sending APRS messages");
            return null;
        }
        String target = (to == null || to.trim().isEmpty()) ? "BLN1CQ" : to.trim().toUpperCase(Locale.ROOT);
        String body = text.replace('|', ' ').replace('~', ' ').replace('{', ' ');
        int num;
        synchronized (this) {
            if (messageNumber > MAX_MESSAGE_NUM) messageNumber = 0;
            num = messageNumber++;
        }
        byte[] frame;
        try {
            APRSPacket p = new APRSPacket(callsign, DEFAULT_DIGIPEATERS,
                MessagePacket.createMessagePayload(target, body, String.valueOf(num)));
            frame = new Packet(p.toAX25Frame()).bytesWithoutCRC();
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "Could not build APRS message: " + ex.getMessage());
            return null;
        }
        if (!sink.sendAx25(frame)) {
            return null;
        }
        Entry e = new Entry();
        e.kind = Kind.MESSAGE;
        e.outgoing = true;
        e.from = callsign;
        e.to = target;
        e.text = body;
        e.msgNum = num;
        if (!target.startsWith("BLN")) {
            synchronized (pendingOutgoing) {
                pendingOutgoing.add(e);
            }
        }
        sink.onEntry(e);
        return e;
    }

    /** Sends a position beacon from the configured position. Returns null if not possible. */
    public Entry sendPositionBeacon(String comment) {
        Double lat = myLat;
        Double lon = myLon;
        if (callsign.isEmpty() || lat == null || lon == null) {
            Log.w(TAG, "Set your callsign and position before beaconing");
            return null;
        }
        if (approximatePosition) {
            lat = Math.round(lat * 100.0) / 100.0;
            lon = Math.round(lon * 100.0) / 100.0;
        }
        byte[] frame;
        try {
            Position pos = new Position(lat, lon, 0, '/', symbolCode);
            String text = "=" + pos.toCompressedString() + (comment == null ? "" : comment);
            PositionField field = new PositionField(text.getBytes(StandardCharsets.UTF_8), "", 1);
            APRSPacket p = new APRSPacket(callsign, DEFAULT_DIGIPEATERS, field.getRawBytes());
            p.getPayload().addAprsData(APRSTypes.T_POSITION, field);
            frame = new Packet(p.toAX25Frame()).bytesWithoutCRC();
        } catch (Exception ex) {
            Log.w(TAG, "Could not build APRS beacon", ex);
            return null;
        }
        if (!sink.sendAx25(frame)) {
            return null;
        }
        Log.i(TAG, String.format(Locale.US, "Beaconed position %.4f, %.4f", lat, lon));
        Entry e = new Entry();
        e.kind = Kind.BEACON_SENT;
        e.outgoing = true;
        e.from = callsign;
        e.lat = lat;
        e.lon = lon;
        e.comment = comment;
        sink.onEntry(e);
        return e;
    }

    private void sendAck(String to, String remoteMessageNum) {
        try {
            APRSPacket p = new APRSPacket(callsign, DEFAULT_DIGIPEATERS,
                MessagePacket.createMessagePayload(to, "ack" + remoteMessageNum, null));
            if (sink.sendAx25(new Packet(p.toAX25Frame()).bytesWithoutCRC())) {
                Log.i(TAG, "Acked message #" + remoteMessageNum + " from " + to);
            }
        } catch (Exception ex) {
            Log.w(TAG, "Could not send APRS ack", ex);
        }
    }

    // ------------------------------------------------------------------ digipeater (from the Android app)

    private boolean isRecentlyDigipeated(String key) {
        long now = System.currentTimeMillis();
        Long then = digipeatDedup.get(key);
        if (then != null && now - then < DIGIPEAT_DEDUP_MS) {
            return true;
        }
        digipeatDedup.entrySet().removeIf(x -> now - x.getValue() >= DIGIPEAT_DEDUP_MS);
        return false;
    }

    private void maybeDigipeat(APRSPacket packet, String dedupKey) {
        List<Digipeater> digis = packet.getDigipeaters();
        if (digis == null || digis.isEmpty()) {
            return;
        }
        int firstUnused = -1;
        for (int i = 0; i < digis.size(); i++) {
            if (!digis.get(i).isUsed()) {
                firstUnused = i;
                break;
            }
        }
        if (firstUnused < 0) {
            return;
        }
        Digipeater d = digis.get(firstUnused);
        String digiCall = d.getCallsign();
        String baseCall = APRSPacket.getBaseCall(digiCall);
        int ssid = -1;
        try {
            ssid = Integer.parseInt(APRSPacket.getSsid(d.toString()));
        } catch (NumberFormatException ignored) {
            // Not numeric.
        }
        boolean isOurCall = baseCall.equalsIgnoreCase(APRSPacket.getBaseCall(callsign));
        // Only WIDE1-n (local fill-in), like the Android app; never WIDE2.
        boolean isWide1 = baseCall.equalsIgnoreCase("WIDE1") && ssid >= 1 && ssid <= 2;
        if (!isOurCall && !isWide1) {
            return;
        }
        List<Digipeater> newDigis = new ArrayList<>(digis);
        if (isOurCall) {
            Digipeater marked = new Digipeater(digiCall);
            marked.setUsed(true);
            newDigis.set(firstUnused, marked);
        } else if (ssid == 1) {
            Digipeater ours = new Digipeater(callsign);
            ours.setUsed(true);
            newDigis.set(firstUnused, ours);
        } else {
            Digipeater decremented = new Digipeater(baseCall + "-1");
            decremented.setUsed(false);
            newDigis.set(firstUnused, decremented);
            Digipeater ours = new Digipeater(callsign);
            ours.setUsed(true);
            newDigis.add(firstUnused, ours);
        }
        try {
            APRSPacket out = new APRSPacket(packet.getSourceCall(), packet.getDestinationCall(),
                newDigis, packet.getPayload().getRawBytes());
            out.setComment(packet.getComment());
            if (sink.sendAx25(new Packet(out.toAX25Frame()).bytesWithoutCRC())) {
                digipeatDedup.put(dedupKey, System.currentTimeMillis());
                Log.i(TAG, "Digipeated packet from " + packet.getSourceCall());
            }
        } catch (Exception ex) {
            Log.w(TAG, "Failed to digipeat packet", ex);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Maidenhead locator (4 or 6 chars, e.g. JN88ne) to the centre of the square. */
    public static double[] locatorToLatLon(String locator) {
        String l = locator == null ? "" : locator.trim().toUpperCase(Locale.ROOT);
        if (!l.matches("[A-R]{2}[0-9]{2}([A-X]{2})?")) {
            return null;
        }
        double lon = (l.charAt(0) - 'A') * 20 - 180 + (l.charAt(2) - '0') * 2;
        double lat = (l.charAt(1) - 'A') * 10 - 90 + (l.charAt(3) - '0');
        if (l.length() == 6) {
            lon += (l.charAt(4) - 'A') / 12.0 + 1 / 24.0;
            lat += (l.charAt(5) - 'A') / 24.0 + 1 / 48.0;
        } else {
            lon += 1;
            lat += 0.5;
        }
        return new double[]{lat, lon};
    }

    /** Great-circle distance in km. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** APRS weather is in °F, mph, inches; the parser already turns pressure into hPa. */
    private static String describeWeather(WeatherField wx) {
        StringBuilder sb = new StringBuilder();
        if (wx.getTemp() != null) sb.append(String.format(Locale.US, "%.0f°C ", (wx.getTemp() - 32) * 5 / 9.0));
        if (wx.getHumidity() != null) sb.append(String.format(Locale.US, "%.0f%% ", wx.getHumidity()));
        if (wx.getPressure() != null) sb.append(String.format(Locale.US, "%.0f hPa ", wx.getPressure()));
        if (wx.getWindSpeed() != null) sb.append(String.format(Locale.US, "wind %.0f km/h ", wx.getWindSpeed() * 1.609));
        if (wx.getRainLast24Hours() != null && wx.getRainLast24Hours() > 0)
            sb.append(String.format(Locale.US, "rain %.1f mm ", wx.getRainLast24Hours() * 25.4)); // parser gives inches
        return sb.toString().trim();
    }

    private static int parseMsgNum(String s) {
        if (s == null || s.trim().isEmpty()) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
