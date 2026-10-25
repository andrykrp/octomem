package ru.octomem.expiry;

/** Pure protocol expiry conversion; all clock sampling belongs to locked callers. */
public final class ExpiryPolicy {
    private ExpiryPolicy() { }
    /** Returns a signed deadline; callers separately retain exptime != 0 as presence. */
    public static long deadline(long exptime, long now) {
        if (exptime == 0) return 0;
        if (exptime < 0) return now;
        if (exptime > 2_592_000) return exptime;
        return now > Long.MAX_VALUE - exptime ? Long.MAX_VALUE : now + exptime;
    }
}
