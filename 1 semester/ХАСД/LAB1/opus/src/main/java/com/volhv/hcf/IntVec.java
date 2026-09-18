package com.volhv.hcf;

import java.util.Arrays;

/** Растущий массив int. */
public final class IntVec {
    public int[] a;
    public int n;

    public IntVec() {
        this(16);
    }

    public IntVec(int cap) {
        a = new int[Math.max(4, cap)];
    }

    public void add(int v) {
        if (n == a.length) a = Arrays.copyOf(a, a.length + (a.length >> 1) + 16);
        a[n++] = v;
    }

    public int get(int i) {
        return a[i];
    }

    public void clear() {
        n = 0;
    }

    public int[] toArray() {
        return Arrays.copyOf(a, n);
    }
}
