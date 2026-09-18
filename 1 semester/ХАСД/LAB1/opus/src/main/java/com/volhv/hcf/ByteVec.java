package com.volhv.hcf;

import java.util.Arrays;

/** Растущий байтовый буфер. */
public final class ByteVec {
    public byte[] a;
    public int n;

    public ByteVec() {
        this(64);
    }

    public ByteVec(int cap) {
        a = new byte[Math.max(8, cap)];
    }

    public void ensure(int extra) {
        if (n + extra > a.length) {
            a = Arrays.copyOf(a, Math.max(a.length * 2, n + extra + 32));
        }
    }

    public void add(byte b) {
        ensure(1);
        a[n++] = b;
    }

    public void add(byte[] b) {
        add(b, 0, b.length);
    }

    public void add(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, a, n, len);
        n += len;
    }

    /** Скопировать собственный участок в конец (безопасно при перевыделении). */
    public void addSelf(int off, int len) {
        ensure(len);
        System.arraycopy(a, off, a, n, len);
        n += len;
    }

    public void addAscii(String s) {
        ensure(s.length());
        for (int i = 0; i < s.length(); i++) a[n++] = (byte) s.charAt(i);
    }

    public void addDecimal(long v) {
        if (v < 0) {
            add((byte) '-');
            v = -v;
        }
        if (v == 0) {
            add((byte) '0');
            return;
        }
        int start = n;
        while (v > 0) {
            add((byte) ('0' + (int) (v % 10)));
            v /= 10;
        }
        for (int i = start, j = n - 1; i < j; i++, j--) {
            byte t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    public void clear() {
        n = 0;
    }

    public byte[] toArray() {
        return Arrays.copyOf(a, n);
    }
}
