package ru.octomem.cli;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import ru.octomem.config.ConfigException;
import ru.octomem.config.ConfigLoader;
import ru.octomem.config.ServerConfig;
import ru.octomem.stats.SafeLogger;

/**
 * Точка входа исполняемого JAR octomem.
 *
 * <p>Справка и проверка конфигурации не открывают сеть. Обычный запуск ожидает остановки сервера.
 *
 * @since 1.0
 */
public final class Main {

    /**
     * Версия сервера. Должна совпадать со строкой {@code VERSION 1.0.0 (octomem)},
     * описанной в {@code docs/04} §6.2, и с версией артефакта сборки.
     */
    public static final String VERSION = "1.0.0";

    private Main() {
    }

    /** Testable lifecycle boundary; implementations own their cleanup. */
    public interface ServerLifecycle extends AutoCloseable {
        /** Starts the server.
         * @throws IOException if startup fails
         */
        void start() throws IOException;
        /** Waits for shutdown and performs externally owned failure cleanup.
         * @throws InterruptedException if waiting is interrupted
         * @throws IllegalStateException if the running server failed; no raw failure text is required
         */
        void awaitTermination() throws InterruptedException;
        /** Stops and releases owned resources. */
        @Override void close();
    }

    /** Creates a server only for normal execution, never for validation/help. */
    @FunctionalInterface
    public interface ServerFactory {
        /** Creates an unstarted server.
         * @param config validated settings
         * @return owned lifecycle
         * @throws IOException if preparation fails
         */
        ServerLifecycle create(ServerConfig config) throws IOException;
    }

    /**
     * Выполняет команду и завершает процесс с её кодом возврата.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Выполняет команду без завершения JVM; обычный запуск открывает сеть.
     *
     * @param args аргументы команды
     * @param out поток справки, версии и успешной проверки
     * @param err поток ошибок; значения параметров и пути туда не выводятся
     * @return 0 при успехе, 2 при ошибке аргументов/конфигурации, 1 при недоступном запуске
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return runInternal(args,out,err,null);
    }

    /** Executes with an injected lifecycle factory for no-network CLI tests.
     * @param args command arguments
     * @param out informational output
     * @param err safe diagnostic output
     * @param factory normal-run server factory
     * @return 0 on completion, 2 on invalid configuration, 1 on startup/runtime failure
     */
    public static int run(String[] args, PrintStream out, PrintStream err, ServerFactory factory) {
        return runInternal(args,out,err,java.util.Objects.requireNonNull(factory));
    }

    private static int runInternal(String[] args, PrintStream out, PrintStream err, ServerFactory factory) {
        if (args == null) { return argumentError(err); }
        String mode = "start";
        Path file = null;
        Map<String, String> overrides = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) { return argumentError(err); }
            switch (arg) {
                case "--help", "--version", "--check-config" -> {
                    if (!mode.equals("start")) { return argumentError(err); }
                    mode = arg;
                }
                case "--config" -> {
                    if (file != null || ++i == args.length || args[i] == null || args[i].isBlank()
                            || args[i].startsWith("--")) { return argumentError(err); }
                    try { file = Path.of(args[i]); }
                    catch (InvalidPathException ex) { return argumentError(err); }
                }
                default -> {
                    if (arg.startsWith("--config=")) {
                        String path = arg.substring("--config=".length());
                        if (file != null || path.isBlank()) { return argumentError(err); }
                        try { file = Path.of(path); }
                        catch (InvalidPathException ex) { return argumentError(err); }
                        continue;
                    }
                    int equals = arg.indexOf('=');
                    if (!arg.startsWith("--") || equals < 3) { return argumentError(err); }
                    String key = arg.substring(2, equals);
                    if (!ConfigLoader.isKnownKey(key)
                            || overrides.putIfAbsent(key, arg.substring(equals + 1)) != null) {
                        return argumentError(err);
                    }
                }
            }
        }
        if (mode.equals("--help") || mode.equals("--version")) {
            if (file != null || !overrides.isEmpty()) { return argumentError(err); }
            if (mode.equals("--help")) { printHelp(out); }
            else { out.println("octomem " + VERSION); }
            return 0;
        }
        ServerConfig config;
        try { config = ConfigLoader.load(file, overrides); }
        catch (ConfigException ex) {
            err.println(ex.getMessage());
            return 2;
        }
        if (mode.equals("--check-config")) {
            try{ru.octomem.transport.TransportSecurity.load(config.values(),java.net.InetAddress.ofLiteral(config.listenAddress()).isLoopbackAddress());}
            catch(IllegalArgumentException invalid){err.println("Некорректные параметры защиты транспорта.");return 2;}
            out.println("Конфигурация корректна. Сервер не запущен.");
            return 0;
        }
        Thread shutdownHook = null;
        var clock=new ru.octomem.util.SystemClock();
        SafeLogger logger = new SafeLogger(config.logLevel(), (level, message) -> {
            if (level == SafeLogger.Level.INFO || level == SafeLogger.Level.DEBUG) out.println(message);
            else err.println(message);
        },clock::nanoTime);
        try (ServerLifecycle server = factory==null
                ? new CacheServer(config,new java.net.InetSocketAddress(java.net.InetAddress.ofLiteral(config.listenAddress()),config.listenPort()),clock,logger)
                : factory.create(config)) {
            shutdownHook = new Thread(server::close, "octomem-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            boolean externalLifecycle = !(server instanceof CacheServer);
            if (externalLifecycle && !java.net.InetAddress.ofLiteral(config.listenAddress()).isLoopbackAddress())
                logger.log(SafeLogger.Event.BROAD_BIND);
            server.start();
            if (externalLifecycle) {
                logger.configuration(config.memoryLimit(), config.itemMaxSize(), config.transportWorkers());
                logger.log(SafeLogger.Event.STARTED);
            }
            server.awaitTermination();
            if (externalLifecycle) logger.log(SafeLogger.Event.STOPPED);
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.log(SafeLogger.Event.START_FAILURE);
            return 1;
        } catch (IOException | RuntimeException failure) {
            logger.log(SafeLogger.Event.START_FAILURE);
            return 1;
        } finally {
            if (shutdownHook != null) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException shuttingDown) { /* JVM already owns hook execution. */ }
            }
        }
    }

    private static int argumentError(PrintStream err) {
        err.println("Ошибка аргументов. Используйте --help; каждый параметр задаётся только один раз.");
        return 2;
    }

    private static void printHelp(PrintStream out) {
        out.println("octomem " + VERSION + "\nСервер оперативного кеширования. Основная платформа: OpenJDK 25.");
        out.println("\nКоманды:\n  java -jar octomem-1.0.0.jar [--config ПУТЬ] [--ключ=значение]");
        out.println("  java -jar octomem-1.0.0.jar --check-config [--config ПУТЬ] [--ключ=значение]");
        out.println("  java -jar octomem-1.0.0.jar --help\n  java -jar octomem-1.0.0.jar --version");
        out.println("Справка и версия используются отдельно. Повторы и неизвестные параметры запрещены.");
        out.println("Файл задаётся как --config ПУТЬ или --config=ПУТЬ, только один раз.");
        out.println("\nПриоритет: аргументы > файл > значения по умолчанию. Без --config файл не читается.");
        out.println("Файл UTF-8: ключ=значение, одна строка на ключ; комментарии начинаются с # или !.");
        out.println("Подстановки, включения файлов и продолжения строк не поддерживаются.");
        out.println("\nКлючи и значения по умолчанию (параметр CLI: --ключ=значение):");
        new TreeMap<>(ConfigLoader.defaults()).forEach((key, value) -> out.println("  --" + key + "=" + value));
        out.println("Дополнительно: --auth.mode=none|sasl|text, --auth.file=ПУТЬ, --tls.keyStore=PKCS12, --tls.keyStorePasswordFile=ПУТЬ. Пароли в аргументах не принимаются.");
        out.println("Локальные транспорты: --listen.unixSocket=АБСОЛЮТНЫЙ_ПУТЬ вместо TCP; --listen.udpPort=ПОРТ только loopback без auth/TLS.");
        out.println("transport.workers: 2 × доступные процессоры, не менее 2; выше показано вычисленное значение.");
        out.println("Размеры: байты или суффиксы k/K, m/M, g/G с множителями 1024, 1048576, 1073741824.");
        out.println("Целые числа без знака, дробей и пробелов; item.maxSize <= memory.limit.");
        out.println("listen.port: 1..65535. memory.limit: 1..9223372036854775807 байт.");
        out.println("item.maxSize и protocol.maxLineBytes: 1..2147483647 байт.");
        out.println("protocol.maxChunkBytes и connections.pendingWriteBytes: 2..2147483647 байт.");
        out.println("connections.max, transport.workers, connections.maxInflightCommands: 1..2147483647.");
        out.println("expiry.reclaimIntervalSeconds: 1..9223372036 секунд.");
        out.println("connections.idleTimeoutSeconds: 0..9223372036 секунд; 0 отключает тайм-аут.");
        out.println("listen.address: числовой IPv4/IPv6 без зоны и скобок; DNS не используется.");
        out.println("IPv4: ровно четыре десятичных октета от 0 до 255, разделённых точками.");
        out.println("log.level: error, warn, info, debug (регистр не важен). eviction.policy: только lru.");
        out.println("\nПример проверки:\n  java -jar octomem-1.0.0.jar --check-config --config design/config-example.properties --memory.limit=128m");
        out.println("\nКоды возврата: 0 успех; 2 ошибка аргументов или конфигурации; 1 ошибка запуска.");
        out.println("Ограничения: аутентификации нет; не открывайте доступ из недоверенных сетей.");
        out.println("Проверка не открывает порт. Обычный запуск работает до остановки процесса; Ctrl+C освобождает ресурсы.");
        out.println("Полная схема: design/config-schema.md. Данные кеша не сохраняются на диск.");
    }
}
