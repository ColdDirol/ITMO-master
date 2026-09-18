package com.volhv.hcf;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Обучение словаря фраз по корпусу.
 *
 * <p>Схема — итеративное наращивание префиксного дерева над атомами (идея та же, что
 * в BPE, но единица — атом, а разбор жадный/оптимальный, как в LZ78):
 * <ol>
 *   <li>считаем частоты атомов, оставляем достаточно частые (остальные будут
 *       раскладываться на символы, а символы — на байты);
 *   <li>дерево инициализируется узлами глубины 1 — самими атомами;
 *   <li>раунд: разбираем обучающий поток текущим деревом и считаем, какой атом чаще
 *       всего следует за каждым выданным узлом; самые частые продолжения добавляем
 *       новыми узлами. За раунд фразы удлиняются на один атом;
 *   <li>пересчитываем частоты узлов, выбрасываем фразы, не окупающие места в словаре,
 *       и нумеруем узлы по убыванию частоты — частым фразам достаются короткие коды;
 *   <li>уточняющая итерация: частоты пересчитываются уже оптимальным разбором (тем
 *       самым, который применит кодер), после чего нумерация и параметр кода
 *       подбираются заново.
 * </ol>
 *
 * <p>Разбор всегда идёт в границах отдельного значения: фраза не может пересекать
 * границу двух значений столбца.
 */
public final class VocabBuilder {

    public static final class Params {
        /** Минимальная частота атома в выборке для попадания в таблицу атомов. */
        public int minAtomCount = 3;
        /** Число раундов наращивания фраз (максимальная длина фразы = rounds+1 атом). */
        public int rounds = 16;
        /** Ограничение на размер словаря (узлов). */
        public int maxNodes = 1 << 21;
        /** Минимальная частота продолжения, чтобы стать фразой. */
        public int minPairCount = 4;
        /** Начальный размер таблицы пар (в степенях двойки). */
        public int pairMapBits = 25;
        /**
         * Отсечение по итоговой частоте: бездетная фраза с частотой <= порога не
         * окупает ~5 байт, которые занимает в словаре, и выбрасывается.
         */
        public int pruneFreq = 6;

        @Override
        public String toString() {
            return "minAtomCount=" + minAtomCount + " rounds=" + rounds
                    + " maxNodes=" + maxNodes + " minPairCount=" + minPairCount
                    + " pruneFreq=" + pruneFreq;
        }
    }

    private static final int ID_BITS = 21;
    private static final long ID_MASK = (1L << ID_BITS) - 1;

    private final Maps.BytesMap atoms = new Maps.BytesMap(1 << 21);
    private final IntVec stream = new IntVec(1 << 24);
    private final IntVec valueEnd = new IntVec(1 << 16);
    private final boolean[] cpSeen = new boolean[0x110000];
    public long trainAtoms;
    public long trainBytes;

    // ------------------------------------------------------------------- сбор

    /** Зафиксировать символы значения (вызывается для всего датасета). */
    public void scanChars(byte[] a, int off, int len) {
        int i = off, end = off + len;
        while (i < end) {
            int l = Tokenizer.utf8Len(a, i, end);
            cpSeen[codePoint(a, i, l)] = true;
            i += l;
        }
    }

    /** Добавить значение в обучающую выборку. */
    public void scanTrain(byte[] a, int off, int len) {
        trainBytes += len;
        int i = off, end = off + len;
        while (i < end) {
            int e = Tokenizer.atomEnd(a, i, end);
            stream.add(atoms.intern(a, i, e - i, 1));
            trainAtoms++;
            i = e;
        }
        valueEnd.add(stream.n);
    }

    private static int codePoint(byte[] a, int i, int l) {
        int b = a[i] & 0xFF;
        if (l == 1) return b;
        int cp;
        if (l == 2) cp = b & 0x1F;
        else if (l == 3) cp = b & 0x0F;
        else cp = b & 0x07;
        for (int j = 1; j < l; j++) cp = (cp << 6) | (a[i + j] & 0x3F);
        return cp >= 0 && cp < 0x110000 ? cp : b;
    }

    private static int utf8Encode(int cp, byte[] out) {
        if (cp < 0x80) {
            out[0] = (byte) cp;
            return 1;
        }
        if (cp < 0x800) {
            out[0] = (byte) (0xC0 | (cp >> 6));
            out[1] = (byte) (0x80 | (cp & 0x3F));
            return 2;
        }
        if (cp < 0x10000) {
            out[0] = (byte) (0xE0 | (cp >> 12));
            out[1] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            out[2] = (byte) (0x80 | (cp & 0x3F));
            return 3;
        }
        out[0] = (byte) (0xF0 | (cp >> 18));
        out[1] = (byte) (0x80 | ((cp >> 12) & 0x3F));
        out[2] = (byte) (0x80 | ((cp >> 6) & 0x3F));
        out[3] = (byte) (0x80 | (cp & 0x3F));
        return 4;
    }

    // --------------------------------------------------------------- обучение

    public Vocab build(Params p, Consumer<String> log) {
        if (p.maxNodes > (1 << ID_BITS)) throw new IllegalArgumentException("maxNodes > 2^21");

        // --- 1. таблица атомов -------------------------------------------------
        Maps.BytesMap tbl = new Maps.BytesMap(1 << 20);
        byte[] tmp = new byte[4];
        for (int b = 0; b < 256; b++) {                 // все однобайтовые строки
            tmp[0] = (byte) b;
            tbl.intern(tmp, 0, 1, 0);
        }
        for (int cp = 0; cp < cpSeen.length; cp++) {     // все встреченные символы
            if (!cpSeen[cp]) continue;
            int l = utf8Encode(cp, tmp);
            tbl.intern(tmp, 0, l, 0);
        }
        byte[] ar = atoms.arena.a;
        for (int id = 0; id < atoms.size; id++) {        // достаточно частые атомы
            if (atoms.count[id] >= p.minAtomCount) tbl.intern(ar, atoms.off[id], atoms.len[id], 0);
        }
        int A = tbl.size;
        if (A > p.maxNodes) throw new IllegalStateException("атомов больше, чем maxNodes: " + A);

        int[] order = sortLex(tbl);
        Vocab v = new Vocab();
        v.nAtoms = A;
        v.atomOff = new int[A];
        v.atomLen = new int[A];
        ByteVec aren = new ByteVec(tbl.arena.n + 16);
        byte[] tar = tbl.arena.a;
        for (int i = 0; i < A; i++) {
            int id = order[i];
            v.atomOff[i] = aren.n;
            v.atomLen[i] = tbl.len[id];
            aren.add(tar, tbl.off[id], tbl.len[id]);
        }
        v.atomArena = aren.a;

        Maps.BytesMap lookup = new Maps.BytesMap(A * 2);
        for (int i = 0; i < A; i++) lookup.intern(v.atomArena, v.atomOff[i], v.atomLen[i], 0);

        // --- 2. обучающий поток в нумерации таблицы атомов ---------------------
        int[] map = new int[atoms.size];
        for (int id = 0; id < atoms.size; id++) map[id] = lookup.find(ar, atoms.off[id], atoms.len[id]);
        IntVec tsv = new IntVec(stream.n + (stream.n >> 3) + 16);
        int nValues = valueEnd.n;
        int[] ends = new int[nValues];
        int src = 0;
        for (int vi = 0; vi < nValues; vi++) {
            int hi = valueEnd.a[vi];
            for (; src < hi; src++) {
                int id = stream.a[src];
                int ai = map[id];
                if (ai >= 0) {
                    tsv.add(ai);
                } else {
                    int o = atoms.off[id], e = o + atoms.len[id];
                    int j = o;
                    while (j < e) {
                        int cl = Tokenizer.utf8Len(ar, j, e);
                        int ci = lookup.find(ar, j, cl);
                        if (ci >= 0) tsv.add(ci);
                        else for (int b = 0; b < cl; b++) tsv.add(lookup.find(ar, j + b, 1));
                        j += cl;
                    }
                }
            }
            ends[vi] = tsv.n;
        }
        int[] ts = tsv.a;
        int tn = tsv.n;
        stream.a = null;
        if (log != null) log.accept(String.format(
                "  словарь: атомов %,d (из %,d различных), обучающий поток %,d атомов в %,d значениях",
                A, atoms.size, tn, nValues));

        // --- 3. раунды наращивания фраз ---------------------------------------
        int[] parent = new int[p.maxNodes];
        int[] atom = new int[p.maxNodes];
        Arrays.fill(parent, 0, A, -1);
        for (int i = 0; i < A; i++) atom[i] = i;
        int nNodes = A;
        Maps.LongIntMap child = new Maps.LongIntMap(p.maxNodes * 2);
        Maps.LongIntMap pairs = new Maps.LongIntMap(1 << p.pairMapBits);
        int perRound = Math.max(1024, (p.maxNodes - A) / Math.max(1, p.rounds));

        for (int round = 0; round < p.rounds && nNodes < p.maxNodes; round++) {
            pairs.clear();
            int lo = 0;
            for (int vi = 0; vi < nValues; vi++) {
                greedy(ts, lo, ends[vi], child, pairs, null);
                lo = ends[vi];
            }
            int budget = Math.min(perRound, p.maxNodes - nNodes);
            int thr = threshold(pairs, budget, p.minPairCount);
            int added = 0;
            for (int s = 0; s < pairs.capacity() && nNodes < p.maxNodes; s++) {
                if (!pairs.occupied(s) || pairs.valAt(s) < thr) continue;
                long kk = pairs.keyAt(s);
                int id = nNodes++;
                parent[id] = (int) (kk >>> 32);
                atom[id] = (int) (kk & 0xFFFFFFFFL);
                child.put(kk, id);
                added++;
            }
            if (log != null) log.accept(String.format(
                    "  раунд %2d: кандидатов %,d, порог %d, добавлено %,d, узлов %,d",
                    round + 1, pairs.size, thr, added, nNodes));
            if (added == 0) break;
        }
        pairs.clear();

        // --- 4. итоговые частоты (жадный разбор), отсечение, ранжирование ------
        long[] freq = new long[nNodes];
        long codes = 0;
        int lo = 0;
        for (int vi = 0; vi < nValues; vi++) {
            codes += greedy(ts, lo, ends[vi], child, null, freq);
            lo = ends[vi];
        }

        // Отсечение снизу вверх: узлы глубины 1 нужны всегда (ими покрывается любой
        // вход), фраза нужна, если она сама достаточно часта или является префиксом
        // сохранённой фразы. Родитель всегда имеет меньший номер, чем ребёнок,
        // поэтому одного прохода от старших номеров к младшим достаточно.
        int[] nChild = new int[nNodes];
        for (int n = A; n < nNodes; n++) nChild[parent[n]]++;
        boolean[] keep = new boolean[nNodes];
        int kept = 0;
        for (int n = nNodes - 1; n >= 0; n--) {
            keep[n] = parent[n] < 0 || nChild[n] > 0 || freq[n] > p.pruneFreq;
            if (keep[n]) {
                kept++;
            } else {
                nChild[parent[n]]--;
                freq[parent[n]] += freq[n];   // вхождения откатятся на префикс
            }
        }

        long[] sk = new long[kept];
        int w = 0;
        for (int n = 0; n < nNodes; n++) if (keep[n]) sk[w++] = (freq[n] << ID_BITS) | n;
        Arrays.sort(sk);
        int[] rankOf = new int[nNodes];
        Arrays.fill(rankOf, -1);
        for (int r = 0; r < kept; r++) rankOf[(int) (sk[kept - 1 - r] & ID_MASK)] = r;

        v.nNodes = kept;
        v.parent = new int[kept];
        v.atom = new int[kept];
        long[] rfreq = new long[kept];
        for (int n = 0; n < nNodes; n++) {
            int r = rankOf[n];
            if (r < 0) continue;
            v.parent[r] = parent[n] < 0 ? -1 : rankOf[parent[n]];
            v.atom[r] = atom[n];
            rfreq[r] = freq[n];
        }
        v.k = chooseK(rfreq, log, "жадный");
        if (log != null) log.accept(String.format(
                "  узлов %,d (отсечено %,d), кодов %,d, байт/код %.2f",
                kept, nNodes - kept, codes, (double) trainBytes / codes));

        // --- 5. уточнение: частоты по оптимальному разбору ---------------------
        v.buildExpansions();
        v.buildEncoderIndex();
        long[] f2 = new long[v.nNodes];
        long codes2 = 0;
        lo = 0;
        for (int vi = 0; vi < nValues; vi++) {
            codes2 += v.countOptimal(ts, lo, ends[vi] - lo, f2);
            lo = ends[vi];
        }
        rerank(v, f2);
        v.k = chooseK(f2, log, "оптим.");
        if (log != null) log.accept(String.format(
                "  после уточнения: кодов %,d, байт/код %.2f", codes2, (double) trainBytes / codes2));

        v.buildExpansions();
        v.buildEncoderIndex();
        return v;
    }

    /** Жадный разбор значения; при {@code pairs != null} копит кандидатов на продолжение. */
    private static int greedy(int[] ts, int lo, int hi, Maps.LongIntMap child,
                              Maps.LongIntMap pairs, long[] freq) {
        int i = lo, codes = 0;
        while (i < hi) {
            int node = ts[i];
            int j = i + 1;
            while (j < hi) {
                int c = child.get(Vocab.key(node, ts[j]));
                if (c < 0) break;
                node = c;
                j++;
            }
            if (freq != null) freq[node]++;
            if (pairs != null && j < hi) pairs.inc(Vocab.key(node, ts[j]));
            i = j;
            codes++;
        }
        return codes;
    }

    /** Перенумеровать узлы по убыванию новых частот; {@code freq} переставляется на месте. */
    private static void rerank(Vocab v, long[] freq) {
        int n = v.nNodes;
        long[] sk = new long[n];
        for (int i = 0; i < n; i++) sk[i] = (freq[i] << ID_BITS) | i;
        Arrays.sort(sk);
        int[] rankOf = new int[n];
        for (int r = 0; r < n; r++) rankOf[(int) (sk[n - 1 - r] & ID_MASK)] = r;
        int[] np = new int[n], na = new int[n];
        long[] nf = new long[n];
        for (int old = 0; old < n; old++) {
            int r = rankOf[old];
            np[r] = v.parent[old] < 0 ? -1 : rankOf[v.parent[old]];
            na[r] = v.atom[old];
            nf[r] = freq[old];
        }
        v.parent = np;
        v.atom = na;
        System.arraycopy(nf, 0, freq, 0, n);
    }

    /** Подобрать параметр k кода bit-varint по измеренным частотам узлов. */
    private static int chooseK(long[] rfreq, Consumer<String> log, String tag) {
        int bestK = 6;
        long bestBits = Long.MAX_VALUE;
        StringBuilder kt = new StringBuilder();
        for (int kk = 3; kk <= 26; kk++) {
            long bits = 0;
            for (int r = 0; r < rfreq.length; r++) if (rfreq[r] != 0) bits += rfreq[r] * Bits.varBits(r, kk);
            if (kk % 3 == 0) kt.append(String.format(" %d:%.1f", kk, bits / 8.0 / 1e6));
            if (bits < bestBits) {
                bestBits = bits;
                bestK = kk;
            }
        }
        if (log != null) {
            log.accept(String.format("  выбор k (%s, МБ):%s  ->  k=%d, %.1f МБ, %.2f бит/код",
                    tag, kt, bestK, bestBits / 8.0 / 1e6,
                    (double) bestBits / Arrays.stream(rfreq).sum()));
        }
        return bestK;
    }

    /** Наибольший порог >= minCount, при котором число отобранных пар не превысит budget. */
    private static int threshold(Maps.LongIntMap pairs, int budget, int minCount) {
        int[] hist = new int[1 << 16];
        for (int s = 0; s < pairs.capacity(); s++) {
            if (pairs.occupied(s)) hist[Math.min(pairs.valAt(s), 0xFFFF)]++;
        }
        int thr = 0xFFFF;
        long acc = hist[thr];
        while (thr > minCount && acc + hist[thr - 1] <= budget) {
            thr--;
            acc += hist[thr];
        }
        return thr;
    }

    // ------------------------------------------- лексикографическая сортировка

    /** Индексы ключей {@code m} в лексикографическом порядке (сортировка слиянием). */
    private static int[] sortLex(Maps.BytesMap m) {
        int n = m.size;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        int[] buf = new int[n];
        mergeSort(a, buf, 0, n, m);
        return a;
    }

    private static void mergeSort(int[] a, int[] buf, int lo, int hi, Maps.BytesMap m) {
        if (hi - lo < 2) return;
        int mid = (lo + hi) >>> 1;
        mergeSort(a, buf, lo, mid, m);
        mergeSort(a, buf, mid, hi, m);
        int i = lo, j = mid, w = lo;
        while (i < mid && j < hi) buf[w++] = cmp(m, a[i], a[j]) <= 0 ? a[i++] : a[j++];
        while (i < mid) buf[w++] = a[i++];
        while (j < hi) buf[w++] = a[j++];
        System.arraycopy(buf, lo, a, lo, hi - lo);
    }

    private static int cmp(Maps.BytesMap m, int x, int y) {
        byte[] ar = m.arena.a;
        int ox = m.off[x], oy = m.off[y], lx = m.len[x], ly = m.len[y];
        int n = Math.min(lx, ly);
        for (int i = 0; i < n; i++) {
            int d = (ar[ox + i] & 0xFF) - (ar[oy + i] & 0xFF);
            if (d != 0) return d;
        }
        return lx - ly;
    }
}
