/**
 * Срок жизни записей, активная очистка.
 *
 * <p>Allowed dependencies are storage, stats and util under ADR-014. All expiry
 * time comes from the existing {@link ru.octomem.util.Clock}; no second Clock
 * is defined here. Expiry and flush visibility respect the atomic command
 * snapshot boundary. Expiring a record retires it without changing retained
 * immutable bytes; actual reuse waits for the last pin. Implementation is deferred.
 *
 * @since 1.0
 */
package ru.octomem.expiry;
