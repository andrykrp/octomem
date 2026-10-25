/**
 * LRU и вытеснение записей.
 *
 * <p>Разрешённые зависимости по {@code docs/03} §3: {@code storage}, {@code stats}.
 * Политика по умолчанию — {@code lru} ({@code docs/03} §8).
 *
 * <p>Eviction respects whole-command visibility and never rewrites pinned
 * immutable records. Retired blocks remain charged until final release. LRU
 * metadata follows segment and allocator locks; eviction preflight is outside
 * the captured segment set, protects mutation targets, then requires complete
 * recapture. No util dependency or runtime implementation is introduced here.
 *
 * @since 1.0
 */
package ru.octomem.eviction;
