package com.vagell.kv4pht.desktop;

import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Channel memories kept in a CHIRP CSV file (by default
 * ~/.kv4p-desktop/memories.csv), so the file itself can be opened in CHIRP or
 * a spreadsheet. Thread-safe; callers get copies.
 */
public final class MemoryStore {
    private static final String TAG = MemoryStore.class.getSimpleName();

    private final Path file;
    private final List<Memory> memories = new ArrayList<>();
    private int nextId = 1;

    public MemoryStore(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        memories.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String csv = Files.readString(file, StandardCharsets.UTF_8);
            MemoryCsv.ImportResult r = MemoryCsv.parse(csv, 0f, 10_000f, "");
            for (Memory m : r.memories) {
                m.id = nextId++;
                memories.add(m);
            }
            Log.i(TAG, "Loaded " + memories.size() + " memories from " + file);
        } catch (IOException e) {
            Log.e(TAG, "Could not read " + file, e);
        }
    }

    public synchronized List<Memory> all() {
        List<Memory> out = new ArrayList<>(memories.size());
        for (Memory m : memories) out.add(m.copy());
        return out;
    }

    public synchronized Memory byId(int id) {
        for (Memory m : memories) {
            if (m.id == id) return m.copy();
        }
        return null;
    }

    /** Distinct non-empty groups, sorted. */
    public synchronized List<String> groups() {
        TreeSet<String> g = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Memory m : memories) {
            if (m.group != null && !m.group.trim().isEmpty()) g.add(m.group.trim());
        }
        return new ArrayList<>(g);
    }

    /** Adds (id 0) or replaces (same id) a memory and saves. Returns the stored copy. */
    public synchronized Memory save(Memory memory) {
        Memory m = memory.copy();
        if (m.id == 0) {
            m.id = nextId++;
            memories.add(m);
        } else {
            boolean replaced = false;
            for (int i = 0; i < memories.size(); i++) {
                if (memories.get(i).id == m.id) {
                    memories.set(i, m);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) memories.add(m);
        }
        write();
        return m.copy();
    }

    public synchronized void addAll(List<Memory> list) {
        for (Memory memory : list) {
            Memory m = memory.copy();
            m.id = nextId++;
            memories.add(m);
        }
        write();
    }

    public synchronized void delete(int id) {
        memories.removeIf(m -> m.id == id);
        write();
    }

    /** Moves a memory one place up (-1) or down (+1); order is the scan order. */
    public synchronized void move(int id, int delta) {
        for (int i = 0; i < memories.size(); i++) {
            if (memories.get(i).id == id) {
                int j = i + delta;
                if (j >= 0 && j < memories.size()) {
                    Collections.swap(memories, i, j);
                    write();
                }
                return;
            }
        }
    }

    public synchronized String exportCsv() {
        return MemoryCsv.toChirpCsv(memories);
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MemoryCsv.toChirpCsv(memories), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.e(TAG, "Could not save memories to " + file, e);
        }
    }
}
