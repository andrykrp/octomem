# P001 bounded risk prototype

Coordinator approved the design through the active dispatch ask on 2026-09-15.
This is disposable experimental code, not either of two production allocators.

Compare a bounded pool of direct ByteBuffer pages with stable FFM shared arenas,
one arena per page. A page contains one experimental version; suballocation and
mixed live/retired records within a slab page are deliberately not implemented.
The production extension must count every live block and lease before page closure.

An allocator monitor owns admission, page bookkeeping, recycling and shutdown.
An atomic version word combines the retired bit and reader count. Pin must win
before retire or fail; the final release of a retired version reclaims its page.
Lease close is idempotent; borrowed bytes cannot escape their lease lifetime.
Shutdown stops admission and retires published versions, but does not forcibly
close a pinned page. The owning reader must finish/cancel and release its lease.

Validate deterministic two-thread replacement/shutdown interleavings and bounded
admission. Measure actual six arrays and wrapper via a premain instrumentation
agent with compressed and uncompressed references; never install the agent in
production. Run three sequential JVM forks, <=60 seconds each, with fixed seed,
64 MiB native page cap, <=128 MiB heap and two active processors. Collect JFR,
latency samples, process CPU and OS working-set samples. A loopback selector echo
isolates 512-byte control-lane copy/selection cost, not cache protocol work.

Alternatives considered: JMH would improve statistical control but needs tool
provisioning; a full cache prototype would confound the allocator decision and
overlap later tasks. This bounded custom harness is the approved early risk probe.
No Memcached provisioning: known WSL executable is missing and relative release
M1/M2/M3 remain INCONCLUSIVE. No acceleration claim or release acceptance follows.
