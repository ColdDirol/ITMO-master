package com.volhv.hcf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Столбцовый слой: выбор кодека под каждый слот по измеренной статистике и
 * собственно кодирование/декодирование блоков.
 *
 * <p>Кодек выбирается автоматически в первом проходе и записывается в заголовок —
 * формат самоописываемый. Каждый столбец кодируется независимо от остальных:
 * значения одного столбца никогда не используются для предсказания другого.
 */
public final class Columns {

    public enum Kind {
        /** Все значения null — данных 0 бит. */
        ALLNULL,
        /** Одно и то же значение во всех строках — хранится в заголовке. */
        CONST,
        /** Целые числа. */
        NUM,
        /** Дата YYYY-MM-DD — как номер дня от эпохи. */
        DATE,
        /** Низкая кардинальность: словарь в заголовке + поток номеров. */
        DICT,
        /** Идентификатор вида {@code prefix_000123}: словарь префиксов + число + ширина. */
        IDNUM,
        /** Произвольный текст: словарь фраз. */
        TEXT
    }

    private Columns() {
    }

    // ------------------------------------------------------------ буферы слота

    /** Значения одного слота в пределах группы строк (сторона кодера). */
    public static final class SlotBuf {
        public final IntVec off = new IntVec();
        public final IntVec len = new IntVec();   // len < 0 -> null
        public final ByteVec arena = new ByteVec(1 << 16);

        public int n() {
            return len.n;
        }

        public void addNull() {
            off.add(0);
            len.add(-1);
        }

        public void add(byte[] a, int o, int l) {
            off.add(arena.n);
            len.add(l);
            arena.add(a, o, l);
        }

        public void clear() {
            off.clear();
            len.clear();
            arena.clear();
        }
    }

    /** Значения одного слота после декодирования: точные байты JSON-токена. */
    public static final class SlotOut {
        public final IntVec off = new IntVec();
        public final IntVec len = new IntVec();   // len < 0 -> null
        public final ByteVec arena = new ByteVec(1 << 16);
        public int cursor;

        public void clear() {
            off.clear();
            len.clear();
            arena.clear();
            cursor = 0;
        }
    }

    // ------------------------------------------------------------- статистика

    /** Сбор свойств слота в первом проходе для выбора кодека. */
    public static final class Stats {
        public final String name;
        public long n, nNull;
        public boolean allNum = true, allDate = true, allId = true;
        public boolean distinctOverflow;
        public final Maps.BytesMap distinct = new Maps.BytesMap(1 << 12);
        public final Maps.BytesMap prefixes = new Maps.BytesMap(1 << 8);
        public long textBytes;

        public Stats(String name) {
            this.name = name;
        }

        public void add(int kind, byte[] a, int off, int len) {
            n++;
            if (kind == Template.KIND_NULL) {
                nNull++;
                return;
            }
            if (kind == Template.KIND_STR) {
                allNum = false;
                textBytes += len;
                if (len > 64) {                      // длинный текст — не дата и не идентификатор
                    allDate = false;
                    allId = false;
                }
                if (allDate && !isDate(a, off, len)) allDate = false;
                if (allId && !isId(a, off, len, prefixes)) allId = false;
            } else {
                allDate = false;
                allId = false;
                long v = parseLong(a, off, len);
                if (v == Long.MIN_VALUE || !decimalEq(a, off, len, v)) allNum = false;
                textBytes += len;
            }
            if (!distinctOverflow) {
                if (len > 64 || distinct.size >= 4096) distinctOverflow = true;
                else distinct.intern(a, off, len, 1);
            }
        }

        /** Длина массива: в исходных байтах её нет, она задана структурой строки. */
        public void addCount(int value) {
            n++;
            allDate = false;
            allId = false;
            if (value < 0) allNum = false;
        }

        public Kind pick() {
            if (n > 0 && nNull == n) return Kind.ALLNULL;
            if (!distinctOverflow && distinct.size == 1) return Kind.CONST;
            if (allNum) return Kind.NUM;
            if (allDate) return Kind.DATE;
            boolean idOk = allId && prefixes.size <= 256;
            // при большом числе различных значений словарь в заголовке дороже,
            // чем разбор идентификатора на префикс + счётчик
            if (idOk && (distinctOverflow || distinct.size > 32)) return Kind.IDNUM;
            if (!distinctOverflow) return Kind.DICT;
            if (idOk) return Kind.IDNUM;
            return Kind.TEXT;
        }
    }

    static boolean isDate(byte[] a, int o, int l) {
        if (l != 10 || a[o + 4] != '-' || a[o + 7] != '-') return false;
        for (int i : new int[]{0, 1, 2, 3, 5, 6, 8, 9}) {
            byte c = a[o + i];
            if (c < '0' || c > '9') return false;
        }
        try {
            LocalDate d = LocalDate.of(num(a, o, 4), num(a, o + 5, 2), num(a, o + 8, 2));
            return d.toString().length() == 10 && matches(a, o, l, d.toString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean matches(byte[] a, int o, int l, String s) {
        if (s.length() != l) return false;
        for (int i = 0; i < l; i++) if ((a[o + i] & 0xFF) != s.charAt(i)) return false;
        return true;
    }

    private static int num(byte[] a, int o, int l) {
        int v = 0;
        for (int i = 0; i < l; i++) v = v * 10 + (a[o + i] - '0');
        return v;
    }

    static boolean isId(byte[] a, int o, int l, Maps.BytesMap prefixes) {
        int u = -1;
        for (int i = l - 1; i >= 0; i--) {
            if (a[o + i] == '_') {
                u = i;
                break;
            }
        }
        if (u < 0 || u == l - 1 || l - u - 1 > 18) return false;
        for (int i = u + 1; i < l; i++) {
            byte c = a[o + i];
            if (c < '0' || c > '9') return false;
        }
        if (prefixes != null && prefixes.size < 512) prefixes.intern(a, o, u + 1, 1);
        return true;
    }

    /**
     * Разбор целого. Диапазон ограничен {@code ±2^54}: этого хватает на любое целое,
     * представимое в JSON без потерь, а кодек разностей гарантированно не переполняется
     * (зигзаг разности двух таких чисел укладывается в код bit-varint).
     * Вне диапазона возвращается {@link Long#MIN_VALUE} — столбец тогда не считается
     * числовым и кодируется как текст, то есть точность всё равно сохраняется.
     */
    public static final long NUM_LIMIT = 1L << 54;

    public static long parseLong(byte[] a, int o, int l) {
        if (l == 0 || l > 19) return Long.MIN_VALUE;
        boolean neg = a[o] == '-';
        int i = neg ? 1 : 0;
        if (i == l) return Long.MIN_VALUE;
        long v = 0;
        for (; i < l; i++) {
            byte c = a[o + i];
            if (c < '0' || c > '9') return Long.MIN_VALUE;
            v = v * 10 + (c - '0');
            if (v < 0 || v > NUM_LIMIT) return Long.MIN_VALUE;
        }
        return neg ? -v : v;
    }

    static boolean decimalEq(byte[] a, int o, int l, long v) {
        String s = Long.toString(v);
        return matches(a, o, l, s);
    }

    // ---------------------------------------------------------------- описание

    /** Описание столбца: кодек + его данные в заголовке. */
    public static final class Spec {
        public Kind kind;
        public boolean hasNull;
        public byte[] constVal;                        // CONST (null => значение null)
        public boolean constQuoted = true;             // CONST: строка (в кавычках) или число
        public List<byte[]> dict = new ArrayList<>();  // DICT / IDNUM
        private Maps.BytesMap dictIndex;

        public void buildIndex() {
            dictIndex = new Maps.BytesMap(Math.max(16, dict.size() * 2));
            for (byte[] b : dict) dictIndex.intern(b, 0, b.length, 0);
        }

        public int dictId(byte[] a, int o, int l) {
            return dictIndex.find(a, o, l);
        }

        void write(ByteArrayOutputStream o) throws IOException {
            o.write(kind.ordinal());
            o.write(hasNull ? 1 : 0);
            if (kind == Kind.CONST) {
                o.write((constVal == null ? 0 : 1) | (constQuoted ? 2 : 0));
                if (constVal != null) {
                    Bits.putUV(o, constVal.length);
                    o.write(constVal);
                }
            } else if (kind == Kind.DICT || kind == Kind.IDNUM) {
                Bits.putUV(o, dict.size());
                for (byte[] b : dict) {
                    Bits.putUV(o, b.length);
                    o.write(b);
                }
            }
        }

        static Spec read(Bits.ByteCursor c) {
            Spec s = new Spec();
            s.kind = Kind.values()[c.u8()];
            s.hasNull = c.u8() != 0;
            if (s.kind == Kind.CONST) {
                int f = c.u8();
                s.constQuoted = (f & 2) != 0;
                if ((f & 1) != 0) s.constVal = c.bytes((int) c.uv());
            } else if (s.kind == Kind.DICT || s.kind == Kind.IDNUM) {
                int n = (int) c.uv();
                for (int i = 0; i < n; i++) s.dict.add(c.bytes((int) c.uv()));
            }
            return s;
        }
    }

    // ------------------------------------------------------- битовая карта null

    private static void writeNulls(SlotBuf b, Bits.Out out) {
        int n = b.n();
        int nulls = 0, runs = 1;
        for (int i = 0; i < n; i++) {
            if (b.len.a[i] < 0) nulls++;
            if (i > 0 && (b.len.a[i] < 0) != (b.len.a[i - 1] < 0)) runs++;
        }
        if (nulls == 0) {
            out.put(0, 2);
            return;
        }
        if (nulls == n) {
            out.put(1, 2);
            return;
        }
        long rleBits = 2 + 1 + Bits.varBits(runs, 6);
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && (b.len.a[j] < 0) == (b.len.a[i] < 0)) j++;
            rleBits += Bits.varBits(j - i - 1, 6);
            i = j;
        }
        if (rleBits < 2 + n) {
            out.put(3, 2);
            out.put(b.len.a[0] < 0 ? 1 : 0, 1);
            out.putVar(runs, 6);
            i = 0;
            while (i < n) {
                int j = i + 1;
                while (j < n && (b.len.a[j] < 0) == (b.len.a[i] < 0)) j++;
                out.putVar(j - i - 1, 6);
                i = j;
            }
        } else {
            out.put(2, 2);
            for (int t = 0; t < n; t++) out.put(b.len.a[t] < 0 ? 1 : 0, 1);
        }
    }

    /** true на позициях null. */
    private static boolean[] readNulls(Bits.In in, int n) {
        boolean[] r = new boolean[n];
        int mode = (int) in.get(2);
        if (mode == 0) return r;
        if (mode == 1) {
            java.util.Arrays.fill(r, true);
            return r;
        }
        if (mode == 2) {
            for (int i = 0; i < n; i++) r[i] = in.get(1) != 0;
            return r;
        }
        boolean cur = in.get(1) != 0;
        int runs = (int) in.getVar(6);
        int p = 0;
        for (int i = 0; i < runs; i++) {
            int l = (int) in.getVar(6) + 1;
            for (int t = 0; t < l && p < n; t++) r[p++] = cur;
            cur = !cur;
        }
        return r;
    }

    // ------------------------------------------------------------- кодирование

    /** Закодировать значения слота в отдельный блок. */
    public static byte[] encode(Spec spec, SlotBuf b, Vocab voc, IntVec atomBuf) {
        Bits.Out out = new Bits.Out(1 << 12);
        int n = b.n();
        if (spec.hasNull) writeNulls(b, out);
        int nn = 0;
        for (int i = 0; i < n; i++) if (b.len.a[i] >= 0) nn++;

        switch (spec.kind) {
            case ALLNULL, CONST -> {
            }
            case NUM -> {
                long[] v = new long[nn];
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (b.len.a[i] < 0) continue;
                    v[w++] = parseLong(b.arena.a, b.off.a[i], b.len.a[i]);
                }
                IntCodec.encode(v, 0, nn, out);
            }
            case DATE -> {
                long[] v = new long[nn];
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (b.len.a[i] < 0) continue;
                    int o = b.off.a[i];
                    v[w++] = LocalDate.of(num(b.arena.a, o, 4), num(b.arena.a, o + 5, 2),
                            num(b.arena.a, o + 8, 2)).toEpochDay();
                }
                IntCodec.encode(v, 0, nn, out);
            }
            case DICT -> {
                long[] v = new long[nn];
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (b.len.a[i] < 0) continue;
                    v[w++] = spec.dictId(b.arena.a, b.off.a[i], b.len.a[i]);
                }
                IntCodec.encode(v, 0, nn, out);
            }
            case IDNUM -> {
                long[] pid = new long[nn], num = new long[nn], wid = new long[nn];
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (b.len.a[i] < 0) continue;
                    int o = b.off.a[i], l = b.len.a[i];
                    int u = l - 1;
                    while (b.arena.a[o + u] != '_') u--;
                    pid[w] = spec.dictId(b.arena.a, o, u + 1);
                    num[w] = parseLong(b.arena.a, o + u + 1, l - u - 1);
                    wid[w] = l - u - 1;
                    w++;
                }
                IntCodec.encode(pid, 0, nn, out);
                IntCodec.encode(num, 0, nn, out);
                IntCodec.encode(wid, 0, nn, out);
            }
            case TEXT -> {
                long[] counts = new long[nn];
                Bits.Out codes = new Bits.Out(1 << 16);
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (b.len.a[i] < 0) continue;
                    voc.atomize(b.arena.a, b.off.a[i], b.len.a[i], atomBuf);
                    counts[w++] = voc.encodeAtoms(atomBuf, codes);
                }
                IntCodec.encode(counts, 0, nn, out);
                byte[] cb = codes.toArray();
                out.putVar(cb.length, 6);
                out.align();
                out.putRaw(cb, 0, cb.length);
            }
        }
        return out.toArray();
    }

    /** Декодировать блок слота обратно в точные байты JSON-токенов. */
    public static void decode(Spec spec, byte[] block, int n, Vocab voc, SlotOut so) {
        so.clear();
        Bits.In in = new Bits.In(block);
        boolean[] isNull = spec.hasNull ? readNulls(in, n) : new boolean[n];
        int nn = 0;
        for (int i = 0; i < n; i++) if (!isNull[i]) nn++;

        switch (spec.kind) {
            case ALLNULL -> {
                for (int i = 0; i < n; i++) pushNull(so);
            }
            case CONST -> {
                for (int i = 0; i < n; i++) {
                    if (isNull[i]) pushNull(so);
                    else if (spec.constQuoted) pushQuoted(so, spec.constVal, 0, spec.constVal.length);
                    else pushRaw(so, spec.constVal);
                }
            }
            case NUM -> {
                long[] v = new long[nn];
                IntCodec.decode(in, v, 0, nn);
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (isNull[i]) pushNull(so);
                    else pushNum(so, v[w++]);
                }
            }
            case DATE -> {
                long[] v = new long[nn];
                IntCodec.decode(in, v, 0, nn);
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (isNull[i]) {
                        pushNull(so);
                        continue;
                    }
                    String s = LocalDate.ofEpochDay(v[w++]).toString();
                    so.off.add(so.arena.n);
                    so.arena.add((byte) '"');
                    so.arena.addAscii(s);
                    so.arena.add((byte) '"');
                    so.len.add(so.arena.n - so.off.a[so.off.n - 1]);
                }
            }
            case DICT -> {
                long[] v = new long[nn];
                IntCodec.decode(in, v, 0, nn);
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (isNull[i]) pushNull(so);
                    else {
                        byte[] d = spec.dict.get((int) v[w++]);
                        pushQuoted(so, d, 0, d.length);
                    }
                }
            }
            case IDNUM -> {
                long[] pid = new long[nn], num = new long[nn], wid = new long[nn];
                IntCodec.decode(in, pid, 0, nn);
                IntCodec.decode(in, num, 0, nn);
                IntCodec.decode(in, wid, 0, nn);
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (isNull[i]) {
                        pushNull(so);
                        continue;
                    }
                    byte[] pre = spec.dict.get((int) pid[w]);
                    so.off.add(so.arena.n);
                    so.arena.add((byte) '"');
                    so.arena.add(pre);
                    String ds = Long.toString(num[w]);
                    for (int z = ds.length(); z < wid[w]; z++) so.arena.add((byte) '0');
                    so.arena.addAscii(ds);
                    so.arena.add((byte) '"');
                    so.len.add(so.arena.n - so.off.a[so.off.n - 1]);
                    w++;
                }
            }
            case TEXT -> {
                long[] counts = new long[nn];
                IntCodec.decode(in, counts, 0, nn);
                int cbLen = (int) in.getVar(6);
                if (cbLen < 0) throw new IllegalStateException();
                // поток кодов начинается на границе байта
                int consumed = block.length - cbLen;
                Bits.In cin = new Bits.In(block, consumed, cbLen);
                int w = 0;
                for (int i = 0; i < n; i++) {
                    if (isNull[i]) {
                        pushNull(so);
                        continue;
                    }
                    int start = so.arena.n;
                    so.off.add(start);
                    so.arena.add((byte) '"');
                    voc.decodeInto(cin, (int) counts[w++], so.arena);
                    so.arena.add((byte) '"');
                    so.len.add(so.arena.n - start);
                }
            }
        }
    }

    private static void pushNull(SlotOut so) {
        so.off.add(so.arena.n);
        so.len.add(-1);
    }

    private static void pushQuoted(SlotOut so, byte[] a, int o, int l) {
        int start = so.arena.n;
        so.off.add(start);
        so.arena.add((byte) '"');
        so.arena.add(a, o, l);
        so.arena.add((byte) '"');
        so.len.add(so.arena.n - start);
    }

    private static void pushRaw(SlotOut so, byte[] a) {
        so.off.add(so.arena.n);
        so.len.add(a.length);
        so.arena.add(a);
    }

    private static void pushNum(SlotOut so, long v) {
        int start = so.arena.n;
        so.off.add(start);
        so.arena.addDecimal(v);
        so.len.add(so.arena.n - start);
    }
}
