package com.vagell.kv4pht.desktop;

import com.vagell.kv4pht.ui.ToneHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes channel memories as CSV.
 * <ul>
 *   <li><b>CHIRP</b> (read and write) - the common format for programming radios.
 *       The memory group is kept in CHIRP's Comment column.</li>
 *   <li><b>RepeaterBook</b> US and international exports (read), same parsing
 *       as the Android app's "Find repeaters".</li>
 * </ul>
 */
public final class MemoryCsv {
    private static final String[] CHIRP_HEADER = {
        "Location", "Name", "Frequency", "Duplex", "Offset", "Tone", "rToneFreq", "cToneFreq",
        "DtcsCode", "DtcsPolarity", "RxDtcsCode", "CrossMode", "Mode", "TStep", "Skip", "Power",
        "Comment", "URCALL", "RPT1CALL", "RPT2CALL", "DVCODE",
    };

    private MemoryCsv() {}

    /** Result of an import: the memories plus a human-readable note on what was skipped. */
    public static final class ImportResult {
        public final List<Memory> memories = new ArrayList<>();
        public String format = "unknown";
        public int skipped;
    }

    // ------------------------------------------------------------------ write

    public static String toChirpCsv(List<Memory> memories) {
        StringBuilder sb = new StringBuilder(String.join(",", CHIRP_HEADER)).append('\n');
        int location = 1;
        for (Memory m : memories) {
            boolean tx = !"None".equals(m.txTone);
            boolean rx = !"None".equals(m.rxTone);
            String toneMode = "";
            String rTone = "88.5";
            String cTone = "88.5";
            String crossMode = "Tone->Tone";
            if (tx && rx && m.txTone.equals(m.rxTone)) {
                toneMode = "TSQL";
                cTone = chirpTone(m.rxTone);
            } else if (tx && rx) {
                toneMode = "Cross";
                rTone = chirpTone(m.txTone);
                cTone = chirpTone(m.rxTone);
            } else if (tx) {
                toneMode = "Tone";
                rTone = chirpTone(m.txTone);
            } else if (rx) {
                toneMode = "Cross";
                crossMode = "->Tone";
                cTone = chirpTone(m.rxTone);
            }
            String duplex = m.offset == Memory.OFFSET_UP ? "+" : m.offset == Memory.OFFSET_DOWN ? "-" : "";
            String offset = String.format(Locale.US, "%.6f", m.offset == Memory.OFFSET_NONE ? 0.0 : m.offsetKhz / 1000.0);
            String[] row = {
                String.valueOf(location++), quote(m.name), String.format(Locale.US, "%.6f", m.rxFrequency()),
                duplex, offset, toneMode, rTone, cTone, "023", "NN", "023", crossMode, "FM", "12.50",
                m.skipDuringScan ? "S" : "", "", quote(m.group == null ? "" : m.group), "", "", "", "",
            };
            sb.append(String.join(",", row)).append('\n');
        }
        return sb.toString();
    }

    private static String chirpTone(String tone) {
        try {
            return String.format(Locale.US, "%.1f", Double.parseDouble(tone));
        } catch (NumberFormatException e) {
            return "88.5";
        }
    }

    private static String quote(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ------------------------------------------------------------------ read

    /**
     * Imports CHIRP or RepeaterBook CSV. Frequencies outside [minMhz, maxMhz]
     * (what this radio's module can do) are skipped, like in the Android app.
     */
    public static ImportResult parse(String csv, float minMhz, float maxMhz, String groupForImport) {
        ImportResult result = new ImportResult();
        List<String> records = splitRecords(csv.replace("\r", ""));
        if (records.isEmpty()) {
            return result;
        }
        String header = records.get(0).replace("﻿", "");
        if (header.startsWith("Location,Name,Frequency")) {
            result.format = "CHIRP";
            parseChirp(records, result, minMhz, maxMhz);
        } else if (header.startsWith("Freq,Input,Offset,Tone,Location")) {
            result.format = "RepeaterBook (US)";
            parseRepeaterBook(records, result, minMhz, maxMhz, groupForImport, true);
        } else if (header.startsWith("Output Freq,Input Freq,Offset,Uplink Tone")) {
            result.format = "RepeaterBook";
            parseRepeaterBook(records, result, minMhz, maxMhz, groupForImport, false);
        }
        return result;
    }

    private static void parseChirp(List<String> records, ImportResult result, float min, float max) {
        Map<String, Integer> col = new HashMap<>();
        String[] header = splitLine(records.get(0).replace("﻿", ""));
        for (int i = 0; i < header.length; i++) col.put(header[i].trim(), i);
        for (int r = 1; r < records.size(); r++) {
            if (records.get(r).trim().isEmpty()) continue;
            String[] c = splitLine(records.get(r));
            double freq = num(get(c, col, "Frequency"));
            String mode = get(c, col, "Mode");
            if (freq < min || freq > max || (!mode.isEmpty() && !mode.equals("FM") && !mode.equals("NFM"))) {
                result.skipped++;
                continue;
            }
            Memory m = new Memory();
            m.name = get(c, col, "Name");
            m.frequency = Memory.formatFrequency(freq);
            String duplex = get(c, col, "Duplex");
            double offsetMhz = num(get(c, col, "Offset"));
            if ("+".equals(duplex)) {
                m.offset = Memory.OFFSET_UP;
            } else if ("-".equals(duplex)) {
                m.offset = Memory.OFFSET_DOWN;
            } else if ("split".equals(duplex) && offsetMhz > 0) {
                // CHIRP "split": Offset is the TX frequency itself.
                double diff = offsetMhz - freq;
                m.offset = diff > 0 ? Memory.OFFSET_UP : diff < 0 ? Memory.OFFSET_DOWN : Memory.OFFSET_NONE;
                offsetMhz = Math.abs(diff);
            }
            m.offsetKhz = m.offset == Memory.OFFSET_NONE ? 600 : (int) Math.round(offsetMhz * 1000);
            String toneMode = get(c, col, "Tone");
            String rTone = ToneHelper.normalizeTone(get(c, col, "rToneFreq"));
            String cTone = ToneHelper.normalizeTone(get(c, col, "cToneFreq"));
            switch (toneMode) {
                case "Tone":
                    m.txTone = rTone;
                    break;
                case "TSQL":
                    m.txTone = cTone;
                    m.rxTone = cTone;
                    break;
                case "Cross": {
                    String cross = get(c, col, "CrossMode");
                    if (cross.startsWith("Tone->")) m.txTone = rTone;
                    if (cross.endsWith("->Tone")) m.rxTone = cTone;
                    break;
                }
                default:
                    break;
            }
            m.skipDuringScan = "S".equalsIgnoreCase(get(c, col, "Skip"));
            m.group = get(c, col, "Comment");
            result.memories.add(m);
        }
    }

    private static void parseRepeaterBook(List<String> records, ImportResult result, float min, float max,
                                          String group, boolean us) {
        for (int r = 1; r < records.size(); r++) {
            if (records.get(r).trim().isEmpty()) continue;
            String[] c = splitLine(records.get(r));
            if (c.length < (us ? 12 : 11)) {
                result.skipped++;
                continue;
            }
            double freq = num(c[0]);
            double offset = num(c[2]);
            if (freq < min || freq > max) {
                result.skipped++;
                continue;
            }
            String call = us ? c[7] : c[5];
            String location = (us ? c[4] : c[6]).replace("\n", " ");
            Memory m = new Memory();
            m.name = call + " • " + location; // same naming as the Android app
            m.frequency = Memory.formatFrequency(freq);
            m.offset = offset < 0 ? Memory.OFFSET_DOWN : offset > 0 ? Memory.OFFSET_UP : Memory.OFFSET_NONE;
            m.offsetKhz = offset == 0 ? 600 : Math.abs((int) Math.round(offset * 1000));
            m.txTone = ToneHelper.normalizeTone(c[3].trim());
            m.group = group == null ? "" : group;
            result.memories.add(m);
        }
    }

    // ------------------------------------------------------------------ CSV helpers (same rules as the Android app)

    static List<String> splitRecords(String data) {
        List<String> records = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder record = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (ch == '"') inQuotes = !inQuotes;
            if (ch == '\n' && !inQuotes) {
                records.add(record.toString());
                record.setLength(0);
            } else {
                record.append(ch);
            }
        }
        if (record.length() > 0) records.add(record.toString());
        return records;
    }

    static String[] splitLine(String line) {
        List<String> cols = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); // escaped quote
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                cols.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cols.add(cur.toString().trim());
        return cols.toArray(new String[0]);
    }

    private static String get(String[] cols, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        return i == null || i >= cols.length ? "" : cols[i].trim();
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
