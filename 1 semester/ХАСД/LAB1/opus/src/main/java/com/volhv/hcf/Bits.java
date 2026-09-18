package com.volhv.hcf;

import java.util.Arrays;

/**
 * Битовые примитивы формата.
 *
 * <p>Единственный код переменной длины, используемый в формате — {@code bit-varint(k)}:
 * значение разбивается на группы по {@code k} бит, каждой группе предшествует бит
 * продолжения. Код полный (биективный): g групп кодируют ровно
 * {@code [base(g), base(g) + 2^(g*k))}, где {@code base(g) = sum_{i<g} 2^(i*k)},
 * поэтому кодовые пространства разных длин не перекрываются и ни одно значение
 * не тратится зря.
 *
 * <p>Важно: длина кода зависит только от <i>величины</i> числа, а не от таблицы
 * вероятностей — это кодирование целых (как VByte/VLQ), а не энтропийное кодирование.
 */
public final class Bits {

    private Bits() {
    }

    /** base(g) для g = 1..: суммарное число значений, покрытых кодами короче g групп. */
    public static long varBase(int k, int groups) {
        long base = 0, span = 1L << k;
        for (int i = 1; i < groups; i++) {
            base += span;
            span <<= k;
        }
        return base;
    }

    /**
     * Сколько групп по k бит нужно значению v.
     *
     * <p>Значение должно быть неотрицательным и укладываться в кодовое пространство:
     * оно исчерпывается, когда {@code span} перестаёт умещаться в long (для k = 6 это
     * примерно 2^60). Столбцовый кодек держит значения в пределах
     * {@code ±2^54} (см. {@code Columns.NUM_LIMIT}), так что запас есть всегда.
     */
    public static int varGroups(long v, int k) {
        if (v < 0) throw new IllegalArgumentException("отрицательное значение: " + v);
        long base = 0, span = 1L << k;
        int g = 1;
        while (v - base >= span) {
            base += span;
            if (span > (Long.MAX_VALUE >>> k)) {
                throw new IllegalArgumentException(
                        "значение вне диапазона bit-varint(k=" + k + "): " + v);
            }
            span <<= k;
            g++;
        }
        return g;
    }

    /** Длина кода bit-varint(k) в битах. */
    public static int varBits(long v, int k) {
        return varGroups(v, k) * (k + 1);
    }

    // ------------------------------------------------------------------ writer

    public static final class Out {
        private byte[] buf;
        private int pos;
        private long acc;
        private int nbits;

        public Out() {
            this(1024);
        }

        public Out(int cap) {
            buf = new byte[Math.max(16, cap)];
        }

        private void ensure(int extra) {
            if (pos + extra > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + extra + 64));
            }
        }

        /** Записать младшие {@code bits} бит значения {@code v} (bits <= 56). */
        public void put(long v, int bits) {
            if (bits == 0) return;
            acc = (acc << bits) | (v & (bits == 64 ? -1L : (1L << bits) - 1));
            nbits += bits;
            while (nbits >= 8) {
                nbits -= 8;
                ensure(1);
                buf[pos++] = (byte) (acc >>> nbits);
            }
        }

        public void putBit(int b) {
            put(b, 1);
        }

        /** bit-varint(k): группы по k бит, старшая группа первой, перед каждой — бит продолжения. */
        public void putVar(long v, int k) {
            int g = varGroups(v, k);
            long x = v - varBase(k, g);
            for (int i = g; i >= 1; i--) {
                put(i > 1 ? 1 : 0, 1);
                put(x >>> ((i - 1) * k), k);
            }
        }

        /** Зигзаг-отображение знакового значения в беззнаковое, затем bit-varint(k). */
        public void putSVar(long v, int k) {
            putVar((v << 1) ^ (v >> 63), k);
        }

        public void putBytes(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) put(b[off + i] & 0xFF, 8);
        }

        /** Быстрое копирование блока байт; требует выравнивания на байт. */
        public void putRaw(byte[] b, int off, int len) {
            if (nbits != 0) throw new IllegalStateException("не выровнено на байт");
            ensure(len);
            System.arraycopy(b, off, buf, pos, len);
            pos += len;
        }

        /** Дописать нули до границы байта. */
        public void align() {
            if (nbits > 0) put(0, 8 - nbits);
        }

        public int bitLength() {
            return pos * 8 + nbits;
        }

        public byte[] toArray() {
            align();
            return Arrays.copyOf(buf, pos);
        }
    }

    // ------------------------------------------------------------------ reader

    public static final class In {
        private final byte[] buf;
        private final int end;
        private int pos;
        private long acc;
        private int nbits;

        public In(byte[] buf) {
            this(buf, 0, buf.length);
        }

        public In(byte[] buf, int off, int len) {
            this.buf = buf;
            this.pos = off;
            this.end = off + len;
        }

        public long get(int bits) {
            if (bits == 0) return 0;
            while (nbits < bits) {
                int b = pos < end ? (buf[pos++] & 0xFF) : 0;
                acc = (acc << 8) | b;
                nbits += 8;
            }
            nbits -= bits;
            long v = (acc >>> nbits) & ((1L << bits) - 1);
            return v;
        }

        public int getBit() {
            return (int) get(1);
        }

        public long getVar(int k) {
            long x = 0;
            int g = 0;
            boolean more;
            do {
                more = get(1) != 0;
                x = (x << k) | get(k);
                g++;
            } while (more);
            return varBase(k, g) + x;
        }

        public long getSVar(int k) {
            long u = getVar(k);
            return (u >>> 1) ^ -(u & 1);
        }

        public void align() {
            nbits = 0;
            acc = 0;
        }
    }

    // ------------------------------------------- байтовый varint (для заголовка)

    public static void putUV(java.io.OutputStream o, long v) throws java.io.IOException {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    public static int uvLen(long v) {
        int n = 1;
        while ((v & ~0x7FL) != 0) {
            v >>>= 7;
            n++;
        }
        return n;
    }

    /** Курсор чтения байтовых varint'ов. */
    public static final class ByteCursor {
        public final byte[] a;
        public int p;

        public ByteCursor(byte[] a, int p) {
            this.a = a;
            this.p = p;
        }

        public long uv() {
            long v = 0;
            int s = 0;
            while (true) {
                int b = a[p++] & 0xFF;
                v |= (long) (b & 0x7F) << s;
                if ((b & 0x80) == 0) return v;
                s += 7;
            }
        }

        public int u8() {
            return a[p++] & 0xFF;
        }

        public byte[] bytes(int n) {
            byte[] r = Arrays.copyOfRange(a, p, p + n);
            p += n;
            return r;
        }
    }
}
