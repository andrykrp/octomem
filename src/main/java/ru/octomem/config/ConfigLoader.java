package ru.octomem.config;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Stateless UTF-8 configuration loader with bounded input and no network or runtime allocation. */
public final class ConfigLoader {
    private static final int MAX_LINE_CHARS = 8192;
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final long MAX_SECONDS = Long.MAX_VALUE / 1_000_000_000L;
    private static final Set<String> SECURITY_KEYS=Set.of("auth.mode","auth.file","tls.keyStore","tls.keyStorePasswordFile","listen.unixSocket","listen.udpPort");
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("listen.address", "127.0.0.1"),
            Map.entry("listen.port", "11211"),
            Map.entry("memory.limit", "67108864"),
            Map.entry("item.maxSize", "1048576"),
            Map.entry("connections.max", "1024"),
            Map.entry("transport.workers", Long.toString(Math.max(2L, 2L * Runtime.getRuntime().availableProcessors()))),
            Map.entry("eviction.policy", "lru"),
            Map.entry("expiry.reclaimIntervalSeconds", "1"),
            Map.entry("protocol.maxLineBytes", "8192"),
            Map.entry("protocol.maxChunkBytes", "65536"),
            Map.entry("connections.pendingWriteBytes", "8388608"),
            Map.entry("connections.maxInflightCommands", "16"),
            Map.entry("connections.idleTimeoutSeconds", "0"),
            Map.entry("log.level", "info"));

    private ConfigLoader() { }

    /**
     * Returns the built-in configuration defaults.
     *
     * @return immutable exact default key set with canonical decimal numbers and byte sizes
     */
    public static Map<String, String> defaults() { return DEFAULTS; }
    /** Recognizes explicit optional security keys without changing legacy defaults. */
    public static boolean isKnownKey(String key){return key!=null && (DEFAULTS.containsKey(key)||SECURITY_KEYS.contains(key));}

    /**
     * Loads defaults, then a file, then command-line overrides, validating the effective values.
     * File syntax and keys are checked even when their values are overridden. No DNS resolution,
     * socket, worker thread, or cache allocation occurs; the reader is always closed.
     *
     * @param file optional file path; {@code null} means no file
     * @param overrides exact configuration keys and untrimmed values; must not be null
     * @return immutable complete canonical configuration
     * @throws ConfigException on invalid input or unreadable file, without raw values or I/O causes
     */
    public static ServerConfig load(Path file, Map<String, String> overrides) throws ConfigException {
        Map<String, String> values = new LinkedHashMap<>(DEFAULTS);
        if (file != null) {
            readFile(file, values);
        }
        if (overrides == null) {
            throw new ConfigException("Не задан набор параметров конфигурации.");
        }
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            requireKey(entry.getKey(), 0);
            values.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            entry.setValue(normalize(entry.getKey(), entry.getValue()));
        }
        if (Long.parseLong(values.get("item.maxSize")) > Long.parseLong(values.get("memory.limit"))) {
            throw invalid("item.maxSize", "размер записи не должен превышать memory.limit");
        }
        return new ServerConfig(values);
    }

    private static void readFile(Path file, Map<String, String> values) throws ConfigException {
        Set<String> seen = new HashSet<>();
        int lineNumber = 1;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new ConfigException("Файл конфигурации превышает допустимый объём.");
            }
            Reader reader = new StringReader(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            StringBuilder line = new StringBuilder();
            int total = 0;
            boolean afterCr = false;
            for (int ch; (ch = reader.read()) != -1;) {
                if (++total == 1 && ch == '\uFEFF') { continue; }
                if (ch == '\uFEFF') {
                    throw new ConfigException("Недопустимый BOM, строка " + lineNumber + ".");
                }
                if (afterCr) {
                    if (ch != '\n') {
                        throw new ConfigException("Неверный перевод строки конфигурации: " + lineNumber + ".");
                    }
                    afterCr = false;
                }
                if (ch == '\r') { afterCr = true; continue; }
                if (ch == '\n') {
                    parseLine(line.toString(), lineNumber++, seen, values);
                    line.setLength(0);
                } else {
                    if (line.length() >= MAX_LINE_CHARS) {
                        throw new ConfigException("Слишком длинная строка конфигурации: " + lineNumber + ".");
                    }
                    line.append((char) ch);
                }
            }
            if (afterCr) {
                throw new ConfigException("Неверный перевод строки конфигурации: " + lineNumber + ".");
            }
            if (!line.isEmpty()) {
                parseLine(line.toString(), lineNumber, seen, values);
            }
        } catch (IOException | SecurityException ex) {
            throw new ConfigException("Не удалось прочитать файл конфигурации. Проверьте путь, права доступа и кодировку UTF-8.");
        }
    }

    private static void parseLine(String text, int line, Set<String> seen, Map<String, String> values)
            throws ConfigException {
        text = trimAscii(text);
        if (text.isEmpty() || text.startsWith("#") || text.startsWith("!")) {
            return;
        }
        int equals = text.indexOf('=');
        if (equals <= 0) {
            throw new ConfigException("Неверный формат строки конфигурации: " + line + ". Ожидается ключ=значение.");
        }
        String key = trimAscii(text.substring(0, equals));
        requireKey(key, line);
        if (!seen.add(key)) {
            throw new ConfigException("Повтор ключа " + key + ", строка " + line + ".");
        }
        String value = trimAscii(text.substring(equals + 1));
        if (value.isEmpty() || value.indexOf('\\') >= 0 || value.contains("${") || value.indexOf('=') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new ConfigException("Неверный формат ключа " + key + ", строка " + line + ".");
        }
        values.put(key, value);
    }

    private static String trimAscii(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == ' ' || text.charAt(start) == '\t')) { start++; }
        while (end > start && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) { end--; }
        return text.substring(start, end);
    }

    private static void requireKey(String key, int line) throws ConfigException {
        if (!isKnownKey(key)) {
            throw new ConfigException(line == 0 ? "Неизвестный ключ конфигурации. См. --help."
                    : "Неизвестный ключ конфигурации, строка " + line + ". См. --help.");
        }
    }

    private static String normalize(String key, String text) throws ConfigException {
        if (text == null || text.isEmpty() || text.length() > MAX_LINE_CHARS) {
            throw invalid(key, "ожидается непустое значение допустимой длины");
        }
        switch (key) {
            case "auth.mode"->{if(!Set.of("none","sasl","text").contains(text))throw invalid(key,"допустимы none, sasl, text");return text;}
            case "auth.file","tls.keyStore","tls.keyStorePasswordFile","listen.unixSocket"->{
                if(text.chars().anyMatch(Character::isISOControl))throw invalid(key,"недопустимый путь");
                return text;
            }
            case "listen.address" -> {
                // ofLiteral never resolves DNS or contacts the network. Exclude scoped addresses.
                if (text.indexOf('%') >= 0 || text.indexOf('[') >= 0 || text.indexOf(']') >= 0
                        || !text.matches("[0-9A-Fa-f:.]+")) {
                    throw invalid(key, "ожидается числовой адрес IPv4 или IPv6 без зоны и скобок");
                }
                if (text.indexOf(':') < 0) {
                    if (!text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                        throw invalid(key, "IPv4 должен содержать четыре десятичных октета от 0 до 255");
                    }
                    for (String octet : text.split("\\.")) {
                        if (Integer.parseInt(octet) > 255) {
                            throw invalid(key, "IPv4 должен содержать четыре десятичных октета от 0 до 255");
                        }
                    }
                }
                try {
                    InetAddress.ofLiteral(text);
                } catch (IllegalArgumentException ex) {
                    throw invalid(key, "ожидается числовой адрес IPv4 или IPv6");
                }
                return text;
            }
            case "eviction.policy" -> {
                if (!text.equals("lru")) {
                    throw invalid(key, "допустимо только lru");
                }
                return text;
            }
            case "log.level" -> {
                if (!text.matches("[A-Za-z]+")
                        || !Set.of("error", "warn", "info", "debug").contains(text.toLowerCase(Locale.ROOT))) {
                    throw invalid(key, "допустимы error, warn, info, debug");
                }
                return text.toLowerCase(Locale.ROOT);
            }
            default -> {
                boolean size = switch (key) {
                    case "memory.limit", "item.maxSize", "protocol.maxLineBytes", "protocol.maxChunkBytes",
                            "connections.pendingWriteBytes" -> true;
                    default -> false;
                };
                long maximum = switch (key) {
                    case "listen.port" -> 65535L;
                    case "listen.udpPort" -> 65535L;
                    case "memory.limit" -> Long.MAX_VALUE;
                    case "expiry.reclaimIntervalSeconds", "connections.idleTimeoutSeconds" -> MAX_SECONDS;
                    default -> Integer.MAX_VALUE;
                };
                long minimum = switch (key) {
                    case "connections.idleTimeoutSeconds" -> 0L;
                    case "protocol.maxChunkBytes", "connections.pendingWriteBytes" -> 2L;
                    default -> 1L;
                };
                long value = parseNumber(key, text, size);
                if (value < minimum || value > maximum) {
                    throw invalid(key, "допустимый диапазон: " + minimum + ".." + maximum);
                }
                return Long.toString(value);
            }
        }
    }

    private static long parseNumber(String key, String text, boolean size) throws ConfigException {
        int end = text.length();
        long multiplier = 1;
        if (size) {
            multiplier = switch (text.charAt(end - 1)) {
                case 'k', 'K' -> 1024L;
                case 'm', 'M' -> 1024L * 1024;
                case 'g', 'G' -> 1024L * 1024 * 1024;
                default -> 1L;
            };
            if (multiplier != 1) { end--; }
        }
        if (end == 0) { throw invalid(key, "ожидается десятичное целое число"); }
        long value = 0;
        for (int i = 0; i < end; i++) {
            int digit = text.charAt(i) - '0';
            if (digit < 0 || digit > 9 || value > (Long.MAX_VALUE - digit) / 10) {
                throw invalid(key, "неверный формат числа или переполнение");
            }
            value = value * 10 + digit;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            throw invalid(key, "переполнение размера");
        }
        return value * multiplier;
    }

    private static ConfigException invalid(String key, String reason) {
        return new ConfigException("Ошибка конфигурации " + key + ": " + reason + ".");
    }
}
