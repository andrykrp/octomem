package ru.octomem.engine;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import ru.octomem.storage.Item;
import ru.octomem.storage.ItemLease;
import ru.octomem.storage.SegmentedItemTable;

/** Seven fixed arrays including occurrence counts, stable leased views and one cursor. */
final class CommandSnapshot implements RetrievalSnapshot, Iterator<Item>, KeyVisitor {
    final ItemLease[] references;
    final int[] firstOccurrenceOrder;
    final int[] segmentIds;
    final int[] dedupOrder;
    final int[] occurrences;
    final byte[] keyLengths;
    final byte[] keys;
    private final AtomicLong budget;
    private final long charge;
    long clock;
    int uniqueCount;
    int segmentCount;
    int failedClass;
    private int filled;
    private int cursor;
    private boolean obtained;
    private boolean closed;

    CommandSnapshot(int count, AtomicLong budget, long charge) {
        this.budget = budget;
        this.charge = charge;
        references = new ItemLease[count];
        firstOccurrenceOrder = new int[count];
        segmentIds = new int[count];
        dedupOrder = new int[count];
        occurrences = new int[count];
        keyLengths = new byte[count];
        keys = new byte[Math.multiplyExact(250, count)];
    }

    @Override
    public void accept(byte[] backing, int offset, int length) {
        if (filled == keyLengths.length) throw new IllegalArgumentException("Key count changed");
        DefaultCacheEngine.validateKey(backing, offset, length);
        System.arraycopy(backing, offset, keys, filled * 250, length);
        keyLengths[filled] = (byte) length;
        dedupOrder[filled] = filled;
        filled++;
    }

    void prepare(SegmentedItemTable table) {
        if (filled != keyLengths.length) throw new IllegalArgumentException("Key count changed");
        sort(dedupOrder, filled, true);
        int previous = -1;
        for (int i = 0; i < filled; i++) {
            int index = dedupOrder[i];
            if (previous < 0 || compareKey(previous, index) != 0) {
                firstOccurrenceOrder[uniqueCount++] = index;
                previous = index;
            }
            occurrences[previous]++;
        }
        sort(firstOccurrenceOrder, uniqueCount, false);
        for (int i = 0; i < uniqueCount; i++) {
            int index = firstOccurrenceOrder[i];
            segmentIds[i] = table.segmentId(keys, index * 250, length(index));
        }
        sort(segmentIds, uniqueCount, false);
        for (int i = 0; i < uniqueCount; i++) {
            if (segmentCount == 0 || segmentIds[i] != segmentIds[segmentCount - 1]) {
                segmentIds[segmentCount++] = segmentIds[i];
            }
        }
    }

    int length(int index) { return keyLengths[index] & 255; }

    boolean contains(ru.octomem.storage.StoredItem item) {
        for (int i = 0; i < uniqueCount; i++) {
            int index = firstOccurrenceOrder[i];
            if (item.keyEquals(keys, index * 250, length(index))) return true;
        }
        return false;
    }

    private int compareKey(int a, int b) {
        int length = Math.min(length(a), length(b));
        for (int i = 0; i < length; i++) {
            int difference = (keys[a * 250 + i] & 255) - (keys[b * 250 + i] & 255);
            if (difference != 0) return difference;
        }
        return length(a) - length(b);
    }

    private int compare(int a, int b, boolean keyOrder) {
        int comparison = keyOrder ? compareKey(a, b) : 0;
        return comparison != 0 ? comparison : Integer.compare(a, b);
    }

    private void sort(int[] array, int count, boolean keyOrder) {
        for (int start = count / 2 - 1; start >= 0; start--) sift(array, start, count, keyOrder);
        for (int end = count - 1; end > 0; end--) {
            int value = array[0]; array[0] = array[end]; array[end] = value;
            sift(array, 0, end, keyOrder);
        }
    }

    private void sift(int[] array, int root, int count, boolean keyOrder) {
        while (root < count / 2) {
            int child = root * 2 + 1;
            if (child + 1 < count && compare(array[child], array[child + 1], keyOrder) < 0) child++;
            if (compare(array[root], array[child], keyOrder) >= 0) return;
            int value = array[root]; array[root] = array[child]; array[child] = value;
            root = child;
        }
    }

    void releasePins() {
        for (int i = 0; i < references.length; i++) {
            if (references[i] != null) { references[i].close(); references[i] = null; }
        }
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Snapshot closed"); }
    @Override public long capturedClock() { requireOpen(); return clock; }
    @Override public Iterator<Item> values() {
        requireOpen();
        if (obtained) throw new IllegalStateException("Cursor already obtained");
        obtained = true;
        return this;
    }
    @Override public boolean hasNext() {
        requireOpen();
        while (cursor < uniqueCount && references[firstOccurrenceOrder[cursor]] == null) cursor++;
        return cursor < uniqueCount;
    }
    @Override public Item next() {
        if (!hasNext()) throw new NoSuchElementException();
        return references[firstOccurrenceOrder[cursor++]].item();
    }
    @Override public void close() {
        if (!closed) {
            closed = true;
            try { releasePins(); } finally { budget.addAndGet(-charge); }
        }
    }
}
