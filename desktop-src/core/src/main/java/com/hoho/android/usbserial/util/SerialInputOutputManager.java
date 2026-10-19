package com.hoho.android.usbserial.util;

import java.util.function.Consumer;

/**
 * Desktop stand-in for usb-serial-for-android's SerialInputOutputManager.
 * Protocol.Sender in the released Android app writes frames through this
 * class, so the desktop app provides one that forwards to its own transport.
 */
public class SerialInputOutputManager {
    private final Consumer<byte[]> writer;

    public SerialInputOutputManager(Consumer<byte[]> writer) {
        this.writer = writer;
    }

    public void writeAsync(byte[] data) {
        writer.accept(data);
    }
}
