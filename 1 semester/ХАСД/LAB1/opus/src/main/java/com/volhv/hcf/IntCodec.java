package com.volhv.hcf;

/**
 * Самонастраивающийся кодек целочисленных столбцов.
 *
 * <p>Поток значений режется на куски по {@link #CHUNK} значений; для каждого куска
 * кодер <i>измеряет</i> размер во всех режимах и выбирает лучший, записывая номер
 * режима в 3 бита. Все режимы используют только само значение и предыдущее значение
 * того же столбца:
 *
 * <ul>
 *   <li>{@code CONST} — все значения куска равны (столбец-константа);
 *   <li>{@code FOR} — frame of reference: {@code v - min} в фиксированных w битах
 *       (ограниченная кардинальность/диапазон);
 *   <li>{@code DELTA} — зигзаг-разности с кодом bit-varint(k), k подбирается
 *       (монотонные и почти монотонные последовательности);
 *   <li>{@code RLE} — повторы (значение, длина серии);
 *   <li>{@code PFOR} / {@code PFOR_DELTA} — patched frame of reference: w бит на
 *       значение, значение {@code 2^w-1} зарезервировано как «исключение», после
 *       которого идёт полный varint. Терпит редкие выбросы (например, сброс
 *       {@code start_char} на границе документа) без расширения ширины поля.
 * </ul>
 */
public final class IntCodec {

    public static final int CHUNK = 1024;

    private static final int CONST = 0, FOR = 1, DELTA = 2, RLE = 3, PFOR = 4, PFOR_DELTA = 5;
    private static final int[] DELTA_KS = {1, 2, 3, 4, 6, 8};

    private IntCodec() {
    }

    private static int zzBits(long v) {
        long z = (v << 1) ^ (v >> 63);
        return 64 - Long.numberOfLeadingZeros(z);
    }

    private static long zz(long v) {
        return (v << 1) ^ (v >> 63);
    }

    public static void encode(long[] v, int off, int n, Bits.Out out) {
        for (int p = 0; p < n; p += CHUNK) {
            encodeChunk(v, off + p, Math.min(CHUNK, n - p), out);
        }
    }

    public static void decode(Bits.In in, long[] v, int off, int n) {
        for (int p = 0; p < n; p += CHUNK) {
            decodeChunk(in, v, off + p, Math.min(CHUNK, n - p));
        }
    }

    // --------------------------------------------------------------- кодер

    private static void encodeChunk(long[] v, int off, int n, Bits.Out out) {
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long x = v[off + i];
            if (x < min) min = x;
            if (x > max) max = x;
        }
        // --- CONST
        long bestBits = Long.MAX_VALUE;
        int bestMode = -1, bestParam = 0;
        if (min == max) {
            bestBits = 3 + Bits.varBits(zz(min), 6);
            bestMode = CONST;
        }
        // --- FOR
        int wFor = 64 - Long.numberOfLeadingZeros(max - min);
        long forBits = 3L + Bits.varBits(zz(min), 6) + 6 + (long) n * wFor;
        if (forBits < bestBits) {
            bestBits = forBits;
            bestMode = FOR;
            bestParam = wFor;
        }
        // --- DELTA(k)
        for (int ki = 0; ki < DELTA_KS.length; ki++) {
            int k = DELTA_KS[ki];
            long bits = 3 + 3 + Bits.varBits(zz(v[off]), 6);
            for (int i = 1; i < n; i++) bits += Bits.varBits(zz(v[off + i] - v[off + i - 1]), k);
            if (bits < bestBits) {
                bestBits = bits;
                bestMode = DELTA;
                bestParam = ki;
            }
        }
        // --- RLE
        long rleBits = 3 + Bits.varBits(n, 6);
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && v[off + j] == v[off + i]) j++;
            rleBits += Bits.varBits(zz(v[off + i]), 6) + Bits.varBits(j - i - 1, 4);
            i = j;
        }
        if (rleBits < bestBits) {
            bestBits = rleBits;
            bestMode = RLE;
        }
        // --- PFOR / PFOR_DELTA
        int[] hv = new int[66], hd = new int[66];
        for (int t = 0; t < n; t++) hv[zzBits(v[off + t] - min)]++;
        hd[zzBits(v[off])]++;
        for (int t = 1; t < n; t++) hd[zzBits(v[off + t] - v[off + t - 1])]++;
        for (int w = 1; w <= 40; w++) {
            long exc = 0, excBits = 0;
            for (int b = w + 1; b < 66; b++) {
                exc += hv[b];
                excBits += (long) hv[b] * (b + (b + 5) / 6);   // оценка varint(k=6)
            }
            long bits = 3L + Bits.varBits(zz(min), 6) + 6 + (long) n * w + excBits;
            if (exc * 2 < n && bits < bestBits) {
                bestBits = bits;
                bestMode = PFOR;
                bestParam = w;
            }
            exc = 0;
            excBits = 0;
            for (int b = w + 1; b < 66; b++) {
                exc += hd[b];
                excBits += (long) hd[b] * (b + (b + 5) / 6);
            }
            bits = 3L + 6 + (long) n * w + excBits;
            if (exc * 2 < n && bits < bestBits) {
                bestBits = bits;
                bestMode = PFOR_DELTA;
                bestParam = w;
            }
        }

        out.put(bestMode, 3);
        switch (bestMode) {
            case CONST -> out.putSVar(min, 6);
            case FOR -> {
                out.putSVar(min, 6);
                out.put(wFor, 6);
                for (int t = 0; t < n; t++) out.put(v[off + t] - min, wFor);
            }
            case DELTA -> {
                int k = DELTA_KS[bestParam];
                out.put(bestParam, 3);
                out.putSVar(v[off], 6);
                for (int t = 1; t < n; t++) out.putSVar(v[off + t] - v[off + t - 1], k);
            }
            case RLE -> {
                out.putVar(n, 6);
                int p = 0;
                while (p < n) {
                    int j = p + 1;
                    while (j < n && v[off + j] == v[off + p]) j++;
                    out.putSVar(v[off + p], 6);
                    out.putVar(j - p - 1, 4);
                    p = j;
                }
            }
            case PFOR -> {
                out.putSVar(min, 6);
                out.put(bestParam, 6);
                long esc = (1L << bestParam) - 1;
                for (int t = 0; t < n; t++) {
                    long d = v[off + t] - min;
                    if (d < esc) {
                        out.put(d, bestParam);
                    } else {
                        out.put(esc, bestParam);
                        out.putVar(d, 6);
                    }
                }
            }
            case PFOR_DELTA -> {
                out.put(bestParam, 6);
                long esc = (1L << bestParam) - 1;
                long prev = 0;
                for (int t = 0; t < n; t++) {
                    long d = zz(v[off + t] - prev);
                    prev = v[off + t];
                    if (d < esc) {
                        out.put(d, bestParam);
                    } else {
                        out.put(esc, bestParam);
                        out.putVar(d, 6);
                    }
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    // --------------------------------------------------------------- декодер

    private static void decodeChunk(Bits.In in, long[] v, int off, int n) {
        int mode = (int) in.get(3);
        switch (mode) {
            case CONST -> {
                long c = in.getSVar(6);
                for (int t = 0; t < n; t++) v[off + t] = c;
            }
            case FOR -> {
                long min = in.getSVar(6);
                int w = (int) in.get(6);
                for (int t = 0; t < n; t++) v[off + t] = min + in.get(w);
            }
            case DELTA -> {
                int k = DELTA_KS[(int) in.get(3)];
                long prev = in.getSVar(6);
                v[off] = prev;
                for (int t = 1; t < n; t++) {
                    prev += in.getSVar(k);
                    v[off + t] = prev;
                }
            }
            case RLE -> {
                int total = (int) in.getVar(6);
                int p = 0;
                while (p < total) {
                    long val = in.getSVar(6);
                    int rep = (int) in.getVar(4) + 1;
                    for (int t = 0; t < rep; t++) v[off + p + t] = val;
                    p += rep;
                }
            }
            case PFOR -> {
                long min = in.getSVar(6);
                int w = (int) in.get(6);
                long esc = (1L << w) - 1;
                for (int t = 0; t < n; t++) {
                    long d = in.get(w);
                    if (d == esc) d = in.getVar(6);
                    v[off + t] = min + d;
                }
            }
            case PFOR_DELTA -> {
                int w = (int) in.get(6);
                long esc = (1L << w) - 1;
                long prev = 0;
                for (int t = 0; t < n; t++) {
                    long d = in.get(w);
                    if (d == esc) d = in.getVar(6);
                    prev += (d >>> 1) ^ -(d & 1);
                    v[off + t] = prev;
                }
            }
            default -> throw new IllegalStateException("режим " + mode);
        }
    }
}
