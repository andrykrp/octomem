package ru.octomem.stats;

import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Per-server filtered logging accepting only fixed events and numeric configuration.
 * Sink failures never escape into cache work; no process-wide logging settings change.
 * @since 1.0
 */
public final class SafeLogger {
    /** Threshold order from most severe to diagnostic. */
    public enum Level { ERROR, WARN, INFO, DEBUG }
    /** Safe operational categories, never exception messages, keys or payloads. */
    public enum Event {
        STARTED(Level.INFO, "Сервер запущен."),
        STOPPED(Level.INFO, "Сервер остановлен."),
        BROAD_BIND(Level.WARN, "Сервер доступен за пределами локального интерфейса. Аутентификации нет."),
        CAPACITY(Level.WARN, "Недостаточно ресурсов для операции кеша."),
        START_FAILURE(Level.ERROR, "Ошибка запуска или работы сервера."),
        INTERNAL_FAILURE(Level.ERROR, "Ошибка выполнения команды кеша."),
        TRANSPORT_FAILURE(Level.ERROR, "Ошибка транспорта."),
        EXPIRY_FAILURE(Level.ERROR, "Ошибка фоновой очистки кеша."),
        COMMAND(Level.DEBUG, "Обработка команды кеша.");
        private final Level level;
        private final String text;
        Event(Level level, String text) { this.level = level; this.text = text; }
    }
    /** Nonblocking trusted sink; failures are isolated from service and data. */
    @FunctionalInterface public interface Sink {
        /** Receives only a fixed safe message or bounded numeric configuration. */
        void log(Level level, String message);
    }
    private final Sink sink;
    private final Gate capacity = new Gate();
    private final Gate debug = new Gate();
    private final LongSupplier nanoTime;
    private volatile Level threshold;

    /** Uses a local stderr sink without changing global JDK logging configuration. */
    public SafeLogger(String level) {
        this(level, (severity, message) -> System.err.println(severity + " " + message));
    }
    /** Creates a legacy untimed logger: capacity/debug emit at most once each. */
    public SafeLogger(String level, Sink sink) {
        this(level,sink,null);
    }
    /** Creates a stderr logger driven by the composition's monotonic clock. */
    public SafeLogger(String level, LongSupplier nanoTime) {
        this(level,(severity,message)->System.err.println(severity+" "+message),nanoTime);
    }
    /** Creates an independently filtered logger; null clock uses first-event-only throttling. */
    public SafeLogger(String level, Sink sink, LongSupplier nanoTime) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.nanoTime=nanoTime;
        setLevel(level);
    }
    /** Sets one of the existing named thresholds, case insensitively. */
    public void setLevel(String level) {
        try { threshold = Level.valueOf(Objects.requireNonNull(level).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid log level"); }
    }
    /** Applies protocol verbosity: zero/error, one/info, two or higher/debug. */
    public void setVerbosity(int level) {
        threshold = level <= 0 ? Level.ERROR : level == 1 ? Level.INFO : Level.DEBUG;
    }
    /** Returns the protocol projection of the current named threshold. */
    public int verbosity() { return threshold == Level.DEBUG ? 2 : threshold == Level.INFO ? 1 : 0; }
    /** Emits a fixed event; repetitive capacity and debug summaries are bounded. */
    public void log(Event event) {
        if (event.level.ordinal() > threshold.ordinal()) return;
        if (event == Event.CAPACITY || event == Event.COMMAND) {
            long now;
            try { now=nanoTime==null ? 0 : nanoTime.getAsLong(); }
            catch(RuntimeException | Error ignored) { return; }
            if (!(event == Event.CAPACITY ? capacity : debug).admit(now,nanoTime!=null)) return;
        }
        emit(event.level, event.text);
    }
    private static final class Gate {
        private boolean initialized;
        private long last;
        synchronized boolean admit(long now,boolean timed) {
            // Signed subtraction tolerates monotonic wrap; backwards clocks suppress, not flood.
            if(initialized && (!timed || now-last<1_000_000_000L)) return false;
            initialized=true; last=now; return true;
        }
    }
    /** Logs only validated numeric effective settings, never raw configuration input. */
    public void configuration(long memoryBytes, int maximumItemBytes, int workers) {
        emit(Level.INFO, "Конфигурация: память=" + memoryBytes + ", запись=" + maximumItemBytes
                + ", потоки=" + workers + ".");
    }
    private void emit(Level level, String text) {
        if (level.ordinal() > threshold.ordinal()) return;
        try { sink.log(level, text); }
        catch (RuntimeException | Error ignored) { /* Logging is never part of the data transaction. */ }
    }
}
