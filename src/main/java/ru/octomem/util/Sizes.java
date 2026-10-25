package ru.octomem.util;

import java.util.Objects;

/**
 * Разбор размеров в байтах из конфигурации.
 *
 * <p>Принимается строгая ASCII-грамматика: положительное десятичное число из цифр
 * {@code 0}–{@code 9} с необязательным односимвольным суффиксом двоичного множителя.
 *
 * <ul>
 *   <li>суффикс {@code k}/{@code K} — {@code ×1024};
 *   <li>суффикс {@code m}/{@code M} — {@code ×1024²};
 *   <li>суффикс {@code g}/{@code G} — {@code ×1024³}.
 * </ul>
 *
 * <p>Знак, пробелы, разделители групп, дробная часть, экспонента, не-ASCII цифры,
 * лишние символы после суффикса и значение {@code 0} отклоняются. Ведущие нули
 * допустимы, пока значение положительно ({@code "00012k"} равно {@code "12k"}).
 * Пробел или любой другой нецифровой символ перед цифрами считается ошибкой.
 *
 * <p><b>Переполнение.</b> Результат, который не помещается в знаковый {@code long},
 * отклоняется: {@code 1k} для {@code Long.MAX_VALUE / 1024} проходит, а на единицу
 * больше — нет. Проверка выполняется до умножения, поэтому промежуточного переполнения
 * не возникает.
 *
 * <p><b>Ошибки.</b> {@code null} даёт {@link NullPointerException}; любое нарушение
 * грамматики или переполнение — {@link IllegalArgumentException} без обёртки в другое
 * исключение.
 *
 * <p><b>Потоки и владение.</b> Утилита без состояния, безопасна для параллельного
 * использования, не создаётся (закрытый конструктор) и не удерживает ресурсов.
 *
 * @since 1.0
 */
public final class Sizes {
    private static final int KILO_SHIFT = 10;
    private static final int MEGA_SHIFT = 20;
    private static final int GIGA_SHIFT = 30;

    private Sizes() {
        // Утилитный класс: экземпляры не создаются.
    }

    /**
     * Разбирает размер с двоичным суффиксом в число байт.
     *
     * @param text непустая ASCII-строка с положительным десятичным числом и
     *             необязательным суффиксом {@code k}, {@code m} или {@code g}
     *             в любом регистре
     * @return положительный размер в байтах, помещающийся в знаковый {@code long}
     * @throws NullPointerException     если {@code text} равен {@code null}
     * @throws IllegalArgumentException если строка пуста, содержит нецифровой символ,
     *                                  значение равно нулю или результат переполняет
     *                                  знаковый {@code long}
     */
    public static long parseBytes(String text) {
        Objects.requireNonNull(text, "text");
        int length = text.length();
        if (length == 0) {
            throw new IllegalArgumentException("empty size");
        }
        int shift;
        int end = length;
        switch (text.charAt(length - 1)) {
            case 'k', 'K' -> {
                shift = KILO_SHIFT;
                end = length - 1;
            }
            case 'm', 'M' -> {
                shift = MEGA_SHIFT;
                end = length - 1;
            }
            case 'g', 'G' -> {
                shift = GIGA_SHIFT;
                end = length - 1;
            }
            default -> shift = 0;
        }
        if (end == 0) {
            throw new IllegalArgumentException("size has no digits: " + text);
        }
        long value = 0L;
        for (int i = 0; i < end; i++) {
            int digit = text.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                throw new IllegalArgumentException("size has a non-digit character: " + text);
            }
            if (value > (Long.MAX_VALUE - digit) / 10L) {
                throw new IllegalArgumentException("size overflows a signed long: " + text);
            }
            value = value * 10L + digit;
        }
        if (value == 0L) {
            throw new IllegalArgumentException("size must be positive: " + text);
        }
        if (shift == 0) {
            return value;
        }
        if (value > (Long.MAX_VALUE >>> shift)) {
            throw new IllegalArgumentException("size overflows a signed long: " + text);
        }
        return value << shift;
    }
}
