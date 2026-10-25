package ru.octomem.engine;

/** Immutable unsigned result without a sentinel value. */
record ImmutableCounterResult(CounterResult.Status status, long value) implements CounterResult { }
