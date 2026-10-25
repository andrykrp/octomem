package ru.octomem.storage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

final class AllocatorOracle {
    private AllocatorOracle() { }

    static List<Integer> classes(int maximum) {
        BigInteger eight = BigInteger.valueOf(8);
        BigInteger cap = BigInteger.valueOf(maximum).add(BigInteger.valueOf(7)).divide(eight).multiply(eight);
        BigInteger value = BigInteger.valueOf(64);
        List<Integer> classes = new ArrayList<>();
        while (true) {
            classes.add(value.intValueExact());
            if (value.equals(cap)) return List.copyOf(classes);
            BigInteger growth = value.multiply(BigInteger.valueOf(5)).add(BigInteger.valueOf(3))
                    .divide(BigInteger.valueOf(4));
            value = growth.add(BigInteger.valueOf(7)).divide(eight).multiply(eight).min(cap);
        }
    }

    static int classId(List<Integer> classes, int size) {
        for (int i = 0; i < classes.size(); i++) if (classes.get(i) >= size) return i;
        throw new AssertionError("No model class");
    }
}
