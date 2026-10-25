package ru.octomem.storage;

import java.nio.ByteBuffer;

/**
 * Opaque allocator-owned block, with no native address or backend-specific API.
 * Before publication, one owner may write it; after publication writes are
 * forbidden and reads require a live pin. Independent readers use independent
 * offsets and buffers. Free invalidates the handle and all further access.
 * Safe publication must establish visibility of initialization to readers.
 *
 * @since 1.0
 */
public interface SlabHandle {
    /**
     * Returns the size-class identifier.
     * @return nonnegative allocator-local class identifier
     */
    int classId();

    /**
     * Returns allocated block capacity, including header/key/alignment space.
     * @return positive byte capacity, at least the requested size
     */
    int capacity();

    /**
     * Copies min(dst.remaining(), capacity() - offset) bytes into dst.
     * Advances destination position only; no shared block cursor exists.
     * @param offset byte offset in [0, capacity()]
     * @param dst non-null writable destination
     * @return bytes copied; zero at end or for an empty destination
     * @throws NullPointerException if dst is null
     * @throws IndexOutOfBoundsException if offset is out of range
     * @throws java.nio.ReadOnlyBufferException if dst is read-only
     */
    int read(int offset, ByteBuffer dst);

    /**
     * Copies min(src.remaining(), capacity() - offset) bytes before publication.
     * Advances source position only. Mutation after publication is forbidden,
     * including when the version is retired but retained by readers.
     * @param offset byte offset in [0, capacity()]
     * @param src non-null source borrowed for this call
     * @return bytes copied; zero at end or for an empty source
     * @throws NullPointerException if src is null
     * @throws IndexOutOfBoundsException if offset is out of range
     * @throws IllegalStateException if the block has been published
     */
    int write(int offset, ByteBuffer src);
}
