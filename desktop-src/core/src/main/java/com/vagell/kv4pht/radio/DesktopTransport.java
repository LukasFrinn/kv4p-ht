package com.vagell.kv4pht.radio;

/**
 * Byte transport between the desktop app and the radio (USB serial today).
 * Desktop-only; named so it can't clash with a future upstream RadioTransport.
 */
interface DesktopTransport extends AutoCloseable {
    interface Listener {
        void onBytes(byte[] bytes);
        void onReady();
        void onDisconnected();
        void onError(Exception error);
    }

    void start(Listener listener);

    @Override
    void close();

    boolean isReady();
    void writeAsync(byte[] bytes);
    String getName();
}
