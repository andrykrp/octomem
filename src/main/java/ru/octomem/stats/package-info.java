/**
 * Счётчики и снимки статистики.
 *
 * <p>По {@code docs/03} §3 пакет не зависит ни от чего, кроме JDK: обратные зависимости
 * из других подсистем запрещены. Состав обязательных полей {@code stats} задан в
 * {@code docs/04} §6.1.
 *
 * <p>T-004 adds only the coordinator-approved immutable SlabClassStats interface:
 * classId, chunkSize, totalPages, totalChunks, usedChunks and freeChunks getters.
 * StatsRegistry, Counter, Gauge and Snapshot remain deferred to T-005. This
 * package has no util dependency; callers supply any required clock values.
 *
 * @since 1.0
 */
package ru.octomem.stats;
