package ru.octomem.engine;

/** Connection-confined, borrowed arguments for one atomic binary/meta operation.
 * No field or array may be changed until execute returns; the engine retains none.
 * @since 1.0
 */
public final class ExtendedRequest implements AutoCloseable {
    /** Atomic operation, independent of wire encoding. */
    public enum Operation { GET, DEBUG, SET, ADD, REPLACE, APPEND, PREPEND, DELETE, INCR, DECR }
    public Operation operation;
    public byte[] key;
    public byte[] value;
    public int flags;
    public long ttl;
    public boolean hasTtl;
    public boolean compareCas;
    public long expectedCas;
    public boolean overrideCas;
    public long newCas;
    public boolean create;
    public long createTtl;
    public long initial;
    public long delta = 1;
    public boolean invalidate;
    public boolean removeValue;
    public boolean noUpdate;
    public boolean recache;
    public long recacheTtl;
    public boolean binaryKey;
    public boolean claimRecache;
    public boolean binaryProtocol;
    public boolean ttlBeforeUpdate;
    public boolean createTtlLast;
    public boolean metaStore;
    public String[] orderedFlags;
    ru.octomem.storage.StoredItem.Reservation admitted;
    // Connection-confined accounting: one command, never one native retry.
    boolean storageCounted;
    /** Cancels any unpublished header reservation; idempotent after transfer. */
    @Override public void close(){if(admitted!=null){admitted.close();admitted=null;}}
}
