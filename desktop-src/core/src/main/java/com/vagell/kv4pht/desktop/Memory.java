package com.vagell.kv4pht.desktop;

import com.vagell.kv4pht.ui.ToneHelper;

import java.util.Locale;

/**
 * One channel memory. Same fields and meaning as the Android app's
 * ChannelMemory, so memories behave identically on both.
 */
public final class Memory {
    public static final int OFFSET_NONE = 0;
    public static final int OFFSET_DOWN = 1;
    public static final int OFFSET_UP = 2;

    /** Stable within one app run; assigned by MemoryStore. Sent to the firmware as memoryId. */
    public int id;
    public String name = "";
    /** RX frequency in "xxx.xxxx" format, like the Android app. */
    public String frequency = "";
    public int offset = OFFSET_NONE;
    public int offsetKhz = 600;
    /** "None" or a CTCSS tone like "82.5" (ToneHelper.VALID_TONE_STRINGS). */
    public String txTone = "None";
    public String rxTone = "None";
    public String group = "";
    public boolean skipDuringScan;

    public Memory copy() {
        Memory m = new Memory();
        m.id = id;
        m.name = name;
        m.frequency = frequency;
        m.offset = offset;
        m.offsetKhz = offsetKhz;
        m.txTone = txTone;
        m.rxTone = rxTone;
        m.group = group;
        m.skipDuringScan = skipDuringScan;
        return m;
    }

    public float rxFrequency() {
        try {
            return Float.parseFloat(frequency);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** TX frequency including the repeater offset, same rule as the Android app. */
    public float txFrequency() {
        float f = rxFrequency();
        if (offset == OFFSET_UP) return f + offsetKhz / 1000f;
        if (offset == OFFSET_DOWN) return f - offsetKhz / 1000f;
        return f;
    }

    /** CTCSS index for the SA818 (0 = none), via the shared upstream ToneHelper. */
    public byte txToneIndex() {
        return (byte) Math.max(0, ToneHelper.getToneIndex(txTone));
    }

    public byte rxToneIndex() {
        return (byte) Math.max(0, ToneHelper.getToneIndex(rxTone));
    }

    /** Short description like "+600 kHz · T 88.5". */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (offset != OFFSET_NONE) {
            sb.append(offset == OFFSET_UP ? "+" : "-").append(offsetKhz).append(" kHz");
        }
        if (!"None".equals(txTone)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("T ").append(txTone);
        }
        if (!"None".equals(rxTone)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("R ").append(rxTone);
        }
        return sb.toString();
    }

    public static String formatFrequency(double mhz) {
        return String.format(Locale.US, "%.4f", mhz);
    }

    @Override
    public String toString() {
        return name + " " + frequency + " " + summary();
    }
}
