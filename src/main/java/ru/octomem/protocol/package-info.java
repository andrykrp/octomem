/**
 * Bounded text-protocol contracts governed by docs/04 and accepted ADR-013/015/016.
 *
 * <p>ADR-014 permits engine, storage, stats and util only. Command, Response,
 * decoder, writer, cursor and close belong to one connection event loop, never
 * concurrently shared. Shutdown schedules cancellation there. Response owns a
 * fully admitted RetrievalSnapshot; successful encode transfers cleanup to its
 * writer. Failed transfer closes with the current owner. COMPLETE/error/cancel/
 * disconnect/shutdown releases every pin and reservation exactly once.
 *
 * <p>Separate global heap budgets (MiB = 1024*1024) are mandatory:
 * body max(item.maxSize+32, min(memory.limit,64 MiB)); control
 * 544*2*connections.max; optional outbound burst min(memory.limit,64 MiB);
 * snapshot min(floor(memory.limit/16),16 MiB) with charge 512+512*N.
 * Every actual charge/live counter is checked long; atomically reserve using
 * charge &lt;= limit-used before addition/allocation. Ownership transfer preserves
 * accounting, and release is exactly once. Arbitrary JVM OOME recovery is not
 * promised. Native cache and actual RSS/layout measurements remain separate.
 * No public setting or any of the fourteen defaults changes.
 *
 * <p>The fifth pool admits line/descriptor slots before reading: for max line
 * length L, charge = 2L*L+256 (2 times L plus 256), per-connection limit =
 * satMul(maxInflight,charge), global limit = satMul(connections.max,perconn).
 * Only positive derived LIMIT multiplication saturates to Long.MAX_VALUE on
 * overflow; actual charges/counters never saturate. Theoretical limit overflow
 * must not reject configurations or shrink public limits. Allocation is lazy,
 * never by aggregate ceiling; real shortage is resource admission failure.
 * Raw line backing grows geometrically up to L with old+new payload at most 2L,
 * at most two 64-byte headers and 128 wrapper/cursor bytes within overhead 256.
 * One lazy exact-L array is also valid after admission. No shrink-copy, per-key
 * arrays/views/offset tables/List, recursion or quadratic rescans. Borrowed
 * KeySequence remains immutable through command release. Count N then reserve
 * snapshot then allocate six arrays then sequentially fill; both reservations
 * stay charged during handoff. Public defensive key copies are separately charged.
 *
 * <p>Body payload+32 is admitted in full before exact-size allocation/body read,
 * with no growth or hidden copy. Denial immediately selects OOM and drains the
 * declared body and CRLF through the private receive512 scratch lane before
 * error/pipeline continuation; oversize preserves its distinct error. Valid
 * noreply suppresses eligible errors, never retrieval replies; malformed framing
 * uses docs/04 recovery/close rules. Partial senders retain bounded full reserves
 * but never wait holding a partial reserve for the rest. No eager itemMax per
 * connection. Full pipeline disables OP_READ without awaiting a line slot while
 * holding a body reserve; previous responses continue via control lanes.
 *
 * <p>Before connection registration reserve two private byte[512] lanes, each
 * charged 544 including overhead32. Allocation is lazy; failure returns admission
 * and rejects the unregistered connection safely. Receive/discard and transmit
 * allow healthy connections progress independently of slow peers. All output,
 * including errors/stats/numbers, can use bounded formatting in transmit parts
 * no larger than min(512,maxChunkBytes), without optional burst. Payload queued
 * in either source obeys pendingWriteBytes. Ready connections and bounded waiters
 * receive fair round-robin finite work, no spin or unlimited callbacks. Own-socket
 * backpressure waits for OP_WRITE, never another connection's burst budget.
 *
 * <p>Sink total capacity and maxChunkBytes are at least 2. Temporary zero emits
 * nothing; one may emit an ordinary byte but not half a CRLF pair. Each accept
 * copies into admitted transport-owned storage, never retaining freed native
 * references after COMPLETE closes the snapshot. No whole-response/value copy.
 *
 * <p>The coordinator-approved ProtocolException has only (Kind,boolean), where
 * Kind is ERROR/CLIENT_ERROR/SERVER_ERROR; null kind is rejected, diagnostics are
 * fixed by kind, and arbitrary input/cause/suppression/stack attachments are absent.
 * It classifies failure; exact safe wire messages still follow docs/04 context.
 *
 * @since 1.0
 */
package ru.octomem.protocol;
