---
name: java-cache-performance
description: Use when implementing or reviewing octomem transport, off-heap memory, concurrency and performance on primary OpenJDK 25; OpenJDK 21 is a contingency only.
---

# Java Cache Performance

Read docs/java-platform.md, the architecture, protocol contract and assigned task. This skill grants no permissions and starts no orchestration.

- Compile the primary server with --release 25 and verify on OpenJDK 25. OpenJDK 21 is a contingency requiring an ADR, adapted code and a separately tested artifact, not an automatic fallback. No Java 17, preview/incubator or vendor-internal APIs.
- No third-party Java/native runtime libraries, Spring Boot or Netty. Pin build/test tools and exclude them from the server artifact.
- Resolve contradictory contracts before implementation. Test unsigned numbers, fragmented input, partial writes, cancellation, expiry, CAS and concurrent mutation of pinned records.
- Use bounded pages and queues. Stable FFM Arena/MemorySegment is allowed on 25; choose the allocator backend by prototype before T-009. Arena closure must respect record leases and concurrent readers. Block reuse is not page deallocation; no Unsafe or reflective Cleaner. Account for heap, native memory, pinned records and RSS separately.
- Read docs/ref-registry-research.md before choosing infrastructure or sending source code to external services. Do not infer Russian hosting, rights or payment compliance from the r37b name. Missing evidence blocks the affected external operation, not public-document research.
- Preserve event-loop fairness and backpressure. Define ownership, record lifetime, lock ordering and happens-before relationships.
- Profile with JFR before optimizing. Compare throughput, p99, allocations, GC, CPU and RSS. Use JMH when justified, plus end-to-end tests. No speculative lock-free rewrites or version-specific allocators.
- Document public contracts, especially resource ownership, concurrency and unsigned semantics, in Javadoc. Use java-docs when available.
- Record OpenJDK distribution/update, JVM flags, OS/CPU, artifact hash, harness identity, warmup, offered load, errors and raw results. Never infer speed from a version number or library count.
