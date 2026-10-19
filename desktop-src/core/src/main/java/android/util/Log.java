package android.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Desktop stand-in for android.util.Log so shared upstream sources compile
 * unchanged. Messages go to stderr and to any registered {@link Sink}.
 */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    /** Receives every log line (the desktop app uses this for its log view and log file). */
    public interface Sink {
        void log(int priority, String tag, String message, Throwable error);
    }

    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static volatile int minStderrPriority = INFO;

    private Log() {}

    public static void addSink(Sink sink) { SINKS.add(sink); }
    public static void removeSink(Sink sink) { SINKS.remove(sink); }
    public static void setMinStderrPriority(int priority) { minStderrPriority = priority; }

    public static int v(String tag, String msg) { return println(VERBOSE, tag, msg, null); }
    public static int v(String tag, String msg, Throwable tr) { return println(VERBOSE, tag, msg, tr); }
    public static int d(String tag, String msg) { return println(DEBUG, tag, msg, null); }
    public static int d(String tag, String msg, Throwable tr) { return println(DEBUG, tag, msg, tr); }
    public static int i(String tag, String msg) { return println(INFO, tag, msg, null); }
    public static int i(String tag, String msg, Throwable tr) { return println(INFO, tag, msg, tr); }
    public static int w(String tag, String msg) { return println(WARN, tag, msg, null); }
    public static int w(String tag, String msg, Throwable tr) { return println(WARN, tag, msg, tr); }
    public static int w(String tag, Throwable tr) { return println(WARN, tag, "", tr); }
    public static int e(String tag, String msg) { return println(ERROR, tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return println(ERROR, tag, msg, tr); }

    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        StringWriter sw = new StringWriter();
        tr.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static String priorityLabel(int priority) {
        switch (priority) {
            case VERBOSE: return "V";
            case DEBUG: return "D";
            case INFO: return "I";
            case WARN: return "W";
            case ERROR: return "E";
            default: return "?";
        }
    }

    private static int println(int priority, String tag, String msg, Throwable tr) {
        if (priority >= minStderrPriority) {
            System.err.println(priorityLabel(priority) + "/" + tag + ": " + msg);
            if (tr != null) tr.printStackTrace();
        }
        for (Sink sink : SINKS) {
            try {
                sink.log(priority, tag, msg, tr);
            } catch (RuntimeException ignored) {
                // A broken sink must never break radio code.
            }
        }
        return 0;
    }
}
