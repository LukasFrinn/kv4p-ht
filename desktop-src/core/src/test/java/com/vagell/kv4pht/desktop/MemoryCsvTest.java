package com.vagell.kv4pht.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class MemoryCsvTest {

    private static Memory mem(String name, String freq, int offset, int khz, String tx, String rx, String group) {
        Memory m = new Memory();
        m.name = name;
        m.frequency = freq;
        m.offset = offset;
        m.offsetKhz = khz;
        m.txTone = tx;
        m.rxTone = rx;
        m.group = group;
        return m;
    }

    @Test
    public void repeaterOffsetsAndTones() {
        Memory r = mem("OM0KBA", "145.6000", Memory.OFFSET_DOWN, 600, "88.5", "None", "Repeaters");
        assertEquals(145.0f, r.txFrequency(), 0.0001f);
        assertEquals(8, r.txToneIndex());   // 88.5 Hz = SA818 CTCSS code 08 ("None" = 0)
        assertEquals(0, r.rxToneIndex());
        assertEquals("-600 kHz · T 88.5", r.summary());
    }

    @Test
    public void chirpRoundTripKeepsEverything() {
        List<Memory> in = List.of(
            mem("Simplex, calling", "145.5000", Memory.OFFSET_NONE, 600, "None", "None", ""),
            mem("Repeater T", "145.6000", Memory.OFFSET_DOWN, 600, "88.5", "None", "Bratislava"),
            mem("TSQL", "438.8000", Memory.OFFSET_DOWN, 7600, "71.9", "71.9", "UHF"),
            mem("Cross", "145.7000", Memory.OFFSET_UP, 600, "100", "123", ""),
            mem("RX only tone", "145.2000", Memory.OFFSET_NONE, 600, "None", "67", ""));
        in.get(4).skipDuringScan = true;

        String csv = MemoryCsv.toChirpCsv(in);
        assertTrue(csv, csv.startsWith("Location,Name,Frequency,Duplex,Offset,Tone"));
        MemoryCsv.ImportResult back = MemoryCsv.parse(csv, 0, 1000, "");
        assertEquals("CHIRP", back.format);
        assertEquals(in.size(), back.memories.size());
        for (int i = 0; i < in.size(); i++) {
            Memory a = in.get(i), b = back.memories.get(i);
            assertEquals(a.name, b.name);
            assertEquals(a.frequency, b.frequency);
            assertEquals(a.offset, b.offset);
            if (a.offset != Memory.OFFSET_NONE) assertEquals(a.offsetKhz, b.offsetKhz);
            assertEquals(a.name, a.txTone, b.txTone);
            assertEquals(a.name, a.rxTone, b.rxTone);
            assertEquals(a.group, b.group);
            assertEquals(a.skipDuringScan, b.skipDuringScan);
        }
    }

    @Test
    public void importsRealChirpExport() {
        String csv = "Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,DtcsCode,DtcsPolarity,RxDtcsCode,CrossMode,Mode,TStep,Skip,Power,Comment,URCALL,RPT1CALL,RPT2CALL,DVCODE\r\n"
            + "0,OM0KBA,145.600000,-,0.600000,Tone,88.5,88.5,023,NN,023,Tone->Tone,FM,12.50,,50W,,,,,\r\n"
            + "1,PMR1,446.006250,,0.000000,,88.5,88.5,023,NN,023,Tone->Tone,NFM,12.50,,0.5W,,,,,\r\n"
            + "2,DMR,439.000000,-,7.600000,,88.5,88.5,023,NN,023,Tone->Tone,DMR,12.50,,5W,,,,,\r\n";
        MemoryCsv.ImportResult r = MemoryCsv.parse(csv, 134, 174, ""); // VHF radio
        assertEquals(1, r.memories.size());
        assertEquals(2, r.skipped); // UHF out of range, and a DMR channel
        Memory m = r.memories.get(0);
        assertEquals("145.6000", m.frequency);
        assertEquals(Memory.OFFSET_DOWN, m.offset);
        assertEquals(600, m.offsetKhz);
        assertEquals("88.5", m.txTone);
        assertEquals("None", m.rxTone);
    }

    @Test
    public void importsRepeaterBookInternational() {
        String csv = "Output Freq,Input Freq,Offset,Uplink Tone,Downlink Tone,Call,Location,County,State,Status,Modes\n"
            + "145.6250,145.0250,-0.6,88.5,,OM0OBA,\"Bratislava, Kamzik\",,Bratislavsky,On-air,FM\n"
            + "438.9500,431.3500,-7.6,,,OM0OUS,Kosice,,Kosicky,On-air,FM\n";
        MemoryCsv.ImportResult r = MemoryCsv.parse(csv, 134, 174, "Repeaters");
        assertEquals("RepeaterBook", r.format);
        assertEquals(1, r.memories.size());
        Memory m = r.memories.get(0);
        assertEquals("OM0OBA • Bratislava, Kamzik", m.name);
        assertEquals(Memory.OFFSET_DOWN, m.offset);
        assertEquals(600, m.offsetKhz);
        assertEquals("88.5", m.txTone);
        assertEquals("Repeaters", m.group);
    }

    @Test
    public void unknownFormatImportsNothing() {
        MemoryCsv.ImportResult r = MemoryCsv.parse("a,b,c\n1,2,3\n", 0, 1000, "");
        assertEquals("unknown", r.format);
        assertTrue(r.memories.isEmpty());
    }

    @Test
    public void storePersistsAndReloads() throws Exception {
        Path dir = Files.createTempDirectory("kv4p-mem");
        Path file = dir.resolve("memories.csv");
        MemoryStore store = new MemoryStore(file);
        store.load();
        Memory a = store.save(mem("A", "145.5000", Memory.OFFSET_NONE, 600, "None", "None", "G1"));
        Memory b = store.save(mem("B", "145.6000", Memory.OFFSET_DOWN, 600, "88.5", "None", "G2"));
        store.move(b.id, -1);
        b.name = "B2";
        store.save(b);
        store.delete(a.id);
        store.save(mem("C", "145.7000", Memory.OFFSET_NONE, 600, "None", "None", "g1"));

        MemoryStore reloaded = new MemoryStore(file);
        reloaded.load();
        List<Memory> all = reloaded.all();
        assertEquals(2, all.size());
        assertEquals("B2", all.get(0).name);
        assertEquals("C", all.get(1).name);
        assertEquals(List.of("g1", "G2"), reloaded.groups());
        assertFalse(Files.exists(dir.resolve("memories.csv.tmp")));
    }
}
