package ru.octomem.engine;

import ru.octomem.storage.Item;
import ru.octomem.storage.ItemLease;

/** Owned result captured in the same transaction as its mutation; no second lookup.
 * Close releases the optional value lease exactly once, including quiet responses.
 * @since 1.0
 */
public final class ExtendedResult implements AutoCloseable {
    /** Wire-neutral outcomes, deliberately distinguishing conditional failures. */
    public enum Status { OK, NOT_FOUND, EXISTS, NOT_STORED, TOO_LARGE, OUT_OF_MEMORY, NON_NUMERIC }
    public final Status status;
    public long cas;
    public long number;
    public int size;
    public int flags;
    public long ttl;
    public long previousTtl;
    public boolean created;
    public long age;
    public int classId;
    public boolean fetched;
    public boolean stale;
    public boolean winner;
    public boolean alreadyWon;
    public boolean binaryKey;
    private ItemLease lease;

    public ExtendedResult(Status status) { this.status = status; }
    /** Transfers one newly acquired lease into this result. */
    public void attach(ItemLease owned) {
        if (lease != null) throw new IllegalStateException("Lease already attached");
        lease = owned;
    }
    /** Returns the borrowed immutable view while this result is open. */
    public Item item() { return lease == null ? null : lease.item(); }
    @Override public void close() {
        ItemLease owned = lease; lease = null;
        if (owned != null) owned.close();
    }
}
