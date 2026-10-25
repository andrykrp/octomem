package ru.octomem.engine;

import ru.octomem.storage.Item;
import ru.octomem.storage.ItemLease;

/** One independently owned single-key lease, including explicit misses. */
final class LeasedGetResult implements GetResult {
    private final ItemLease lease;
    private boolean closed;
    LeasedGetResult(ItemLease lease) { this.lease = lease; }
    private void requireOpen() { if (closed) throw new IllegalStateException("Result closed"); }
    @Override public boolean found() { requireOpen(); return lease != null; }
    @Override public Item item() { requireOpen(); return lease == null ? null : lease.item(); }
    @Override public void close() { if (!closed) { closed = true; if (lease != null) lease.close(); } }
}
