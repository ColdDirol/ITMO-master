package com.volhv.hcf;

import java.util.Arrays;

/** Специализированные хеш-таблицы: без боксинга, с открытой адресацией. */
public final class Maps {

    private Maps() {
    }

    /**
     * Множество байтовых строк с присвоением последовательных id и счётчиками.
     * Ключи хранятся в одной общей «арене», чтобы не создавать миллионы объектов.
     */
    public static final class BytesMap {
        private int[] table;          // слот -> id+1
        private int mask;
        public int size;
        public int[] off;
        public int[] len;
        public long[] count;
        public final ByteVec arena = new ByteVec(1 << 20);

        public BytesMap() {
            this(1 << 16);
        }

        public BytesMap(int cap) {
            int n = Integer.highestOneBit(Math.max(16, cap) - 1) << 1;
            table = new int[n];
            mask = n - 1;
            off = new int[16];
            len = new int[16];
            count = new long[16];
        }

        private static int hash(byte[] a, int o, int l) {
            int h = 0x9E3779B9;
            for (int i = 0; i < l; i++) h = (h ^ (a[o + i] & 0xFF)) * 0x01000193;
            h ^= l * 0x85EBCA6B;
            h ^= h >>> 15;
            return h;
        }

        private boolean eq(int id, byte[] a, int o, int l) {
            if (len[id] != l) return false;
            int p = off[id];
            byte[] ar = arena.a;
            for (int i = 0; i < l; i++) if (ar[p + i] != a[o + i]) return false;
            return true;
        }

        /** Найти id ключа или -1. */
        public int find(byte[] a, int o, int l) {
            int i = hash(a, o, l) & mask;
            while (true) {
                int v = table[i];
                if (v == 0) return -1;
                if (eq(v - 1, a, o, l)) return v - 1;
                i = (i + 1) & mask;
            }
        }

        /** Найти или добавить; счётчик увеличивается на {@code inc}. */
        public int intern(byte[] a, int o, int l, long inc) {
            int i = hash(a, o, l) & mask;
            while (true) {
                int v = table[i];
                if (v == 0) {
                    if (size == off.length) {
                        off = Arrays.copyOf(off, size * 2);
                        len = Arrays.copyOf(len, size * 2);
                        count = Arrays.copyOf(count, size * 2);
                    }
                    int id = size++;
                    off[id] = arena.n;
                    len[id] = l;
                    arena.add(a, o, l);
                    count[id] = inc;
                    table[i] = id + 1;
                    if (size * 10 > table.length * 7) rehash();
                    return id;
                }
                if (eq(v - 1, a, o, l)) {
                    count[v - 1] += inc;
                    return v - 1;
                }
                i = (i + 1) & mask;
            }
        }

        private void rehash() {
            int n = table.length << 1;
            int[] t = new int[n];
            int m = n - 1;
            byte[] ar = arena.a;
            for (int id = 0; id < size; id++) {
                int i = hash(ar, off[id], len[id]) & m;
                while (t[i] != 0) i = (i + 1) & m;
                t[i] = id + 1;
            }
            table = t;
            mask = m;
        }
    }

    /** Хеш-таблица long -> int (значение -1 = отсутствует). */
    public static final class LongIntMap {
        private long[] keys;   // хранится key+1, 0 = пусто
        private int[] vals;
        private int mask;
        public int size;

        public LongIntMap(int cap) {
            int n = Integer.highestOneBit(Math.max(16, cap) - 1) << 1;
            keys = new long[n];
            vals = new int[n];
            mask = n - 1;
        }

        private static int hash(long k) {
            k *= 0x9E3779B97F4A7C15L;
            k ^= k >>> 29;
            k *= 0xBF58476D1CE4E5B9L;
            k ^= k >>> 32;
            return (int) k;
        }

        public int get(long key) {
            long k = key + 1;
            int i = hash(key) & mask;
            while (true) {
                long kk = keys[i];
                if (kk == 0) return -1;
                if (kk == k) return vals[i];
                i = (i + 1) & mask;
            }
        }

        public void put(long key, int val) {
            long k = key + 1;
            int i = hash(key) & mask;
            while (true) {
                long kk = keys[i];
                if (kk == 0) {
                    keys[i] = k;
                    vals[i] = val;
                    size++;
                    if (size * 10 > keys.length * 7) rehash();
                    return;
                }
                if (kk == k) {
                    vals[i] = val;
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        public void inc(long key) {
            long k = key + 1;
            int i = hash(key) & mask;
            while (true) {
                long kk = keys[i];
                if (kk == 0) {
                    keys[i] = k;
                    vals[i] = 1;
                    size++;
                    if (size * 10 > keys.length * 7) rehash();
                    return;
                }
                if (kk == k) {
                    vals[i]++;
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        private void rehash() {
            int n = keys.length << 1;
            long[] nk = new long[n];
            int[] nv = new int[n];
            int m = n - 1;
            for (int i = 0; i < keys.length; i++) {
                long k = keys[i];
                if (k == 0) continue;
                int j = hash(k - 1) & m;
                while (nk[j] != 0) j = (j + 1) & m;
                nk[j] = k;
                nv[j] = vals[i];
            }
            keys = nk;
            vals = nv;
            mask = m;
        }

        public void clear() {
            Arrays.fill(keys, 0L);
            size = 0;
        }

        public int capacity() {
            return keys.length;
        }

        public long keyAt(int i) {
            return keys[i] - 1;
        }

        public int valAt(int i) {
            return vals[i];
        }

        public boolean occupied(int i) {
            return keys[i] != 0;
        }
    }
}
