package ru.octomem.eviction;

import ru.octomem.storage.StoredItem;

/** Bounded approximate LRU candidate selection within one segment and size class. */
public final class LruSelection {
    private StoredItem candidate;
    /** Considers a caller-protected owner without pinning or copying its bytes. */
    public void consider(StoredItem item) {
        if (candidate == null || Long.compareUnsigned(item.activityOrder(), candidate.activityOrder()) < 0) {
            candidate = item;
        }
    }
    /** Returns the oldest sampled owner, borrowed only within the current transaction. */
    public StoredItem candidate() { return candidate; }
}
