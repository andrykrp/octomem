package ru.octomem.util;

import java.util.Objects;

/**
 * Операции над беззнаковыми 64-битными значениями, хранимыми в {@code long}.
 *
 * <p>Спецификация Memcached трактует токен {@code cas}, счётчик версий и дельту
 * {@code incr}/{@code decr} как беззнаковые 64-битные числа. В памяти Java такое
 * значение занимает {@code long} в виде битового шаблона: {@link #parse(String)}
 * возвращает шаблон, {@link #format(long)} печатает его как беззнаковое десятичное.
 *
 * <p>Разбор строго ограничен ASCII-цифрами {@code 0}–{@code 9}; ведущие нули
 * допустимы. Знаки, пробелы, не-ASCII цифры, разделители, буквы и значения больше
 * {@code 18446744073709551615} отклоняются.
 *
 * <p><b>Ошибки.</b> {@code null} даёт {@link NullPointerException}; любое нарушение
 * грамматики или переполнение — {@link IllegalArgumentException} без обёртки.
 *
 * <p><b>Потоки и владение.</b> Утилита без состояния, безопасна для параллельного
 * использования, не создаётся (закрытый конструктор), ресурсов не удерживает.
 *
 * @since 1.0
 */
public final class Unsigned64 {
    /** {@code floor((2^64 - 1) / 10)} = 1844674407370955161. */
    private static final long MAX_DIV_10 = Long.divideUnsigned(-1L, 10L);
    /** {@code (2^64 - 1) % 10} = 5. */
    private static final int MAX_REMAINDER = (int) Long.remainderUnsigned(-1L, 10L);

    private Unsigned64() {
        // Утилитный класс: экземпляры не создаются.
    }

    /**
     * Разбирает беззнаковое десятичное значение в битовый шаблон {@code long}.
     *
     * @param text непустая ASCII-строка из цифр {@code 0}–{@code 9}
     * @return битовый шаблон значения в диапазоне {@code 0..18446744073709551615}
     * @throws NullPointerException     если {@code text} равен {@code null}
     * @throws IllegalArgumentException если строка пуста, содержит нецифровой символ
     *                                  или значение превышает {@code 2^64 - 1}
     */
    public static long parse(String text) {
        Objects.requireNonNull(text, "text");
        int length = text.length();
        if (length == 0) {
            throw new IllegalArgumentException("empty unsigned value");
        }
        long value = 0L;
        for (int i = 0; i < length; i++) {
            int digit = text.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                throw new IllegalArgumentException("unsigned value has a non-digit character: " + text);
            }
            if (Long.compareUnsigned(value, MAX_DIV_10) > 0
                    || (value == MAX_DIV_10 && digit > MAX_REMAINDER)) {
                throw new IllegalArgumentException("unsigned value exceeds 2^64 - 1: " + text);
            }
            value = value * 10L + digit;
        }
        return value;
    }

    /**
     * Форматирует битовый шаблон как беззнаковое десятичное число.
     *
     * @param value битовый шаблон беззнакового значения
     * @return десятичная запись в диапазоне {@code 0..18446744073709551615}
     */
    public static String format(long value) {
        return Long.toUnsignedString(value);
    }

    /**
     * Складывает два беззнаковых значения по модулю {@code 2^64}.
     *
     * @param left  левый битовый шаблон
     * @param right правый битовый шаблон
     * @return {@code (left + right) mod 2^64} в виде битового шаблона
     */
    public static long addModulo(long left, long right) {
        return left + right;
    }

    /**
     * Вычитает беззнаковые значения с насыщением на нуле.
     *
     * @param left  уменьшаемое, беззнаковое
     * @param right вычитаемое, беззнаковое
     * @return {@code left - right}, если {@code left >= right} беззнаково, иначе {@code 0}
     */
    public static long subtractFloorZero(long left, long right) {
        return Long.compareUnsigned(left, right) >= 0 ? left - right : 0L;
    }
}
