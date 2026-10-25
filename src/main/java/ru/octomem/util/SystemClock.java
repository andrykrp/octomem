package ru.octomem.util;

/**
 * Системная реализация {@link Clock} поверх стандартной библиотеки OpenJDK.
 *
 * <p>Это <b>единственное</b> место в основной кодовой базе, которому разрешено напрямую
 * вызывать {@link System#currentTimeMillis()} и {@link System#nanoTime()}. Любая другая
 * точка чтения системного времени отклоняется архитектурной проверкой T-003.
 *
 * <p><b>Потоки и владение.</b> Класс не хранит состояния, поэтому безопасен для
 * параллельного использования и не требует внешней синхронизации. Экземпляр не
 * управляет ресурсами и не требует закрытия; создание объекта дешёво.
 *
 * @since 1.0
 */
public final class SystemClock implements Clock {
    /**
     * Создаёт системные часы.
     *
     * <p>Конструктор без аргументов явно оставлен открытым: независимые тесты и
     * исполняемая точка входа создают адаптер обычным {@code new SystemClock()}.
     */
    public SystemClock() {
        // Состояния нет; адаптер полностью не хранит данных.
    }

    /** {@inheritDoc} */
    @Override
    public long currentTimeSeconds() {
        return Math.floorDiv(System.currentTimeMillis(), 1000L);
    }

    /** {@inheritDoc} */
    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
