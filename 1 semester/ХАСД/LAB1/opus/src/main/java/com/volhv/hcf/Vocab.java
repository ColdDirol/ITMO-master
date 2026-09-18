package com.volhv.hcf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Статический словарь фраз — основной механизм сжатия текстовых столбцов.
 *
 * <p>Словарь — это префиксное дерево (trie) над атомами (см. {@link Tokenizer}).
 * Узел дерева = последовательность атомов = строка текста. Узлы глубины 1 — сами атомы
 * (в таблицу входят все 256 однобайтовых строк, поэтому <i>любая</i> последовательность
 * байт кодируема и escape-механизм не нужен). Узлы большей глубины — частые фразы,
 * набранные обучением (см. {@code VocabBuilder}).
 *
 * <p>Кодирование значения: жадный поиск наибольшего совпадения по дереву, на выходе —
 * последовательность номеров узлов. Номер узла = его ранг по частоте в корпусе, номера
 * пишутся кодом {@code bit-varint(k)}, поэтому частым фразам достаются короткие коды.
 * Это словарное кодирование (родственник LZ78 со статическим словарём), а не энтропийное:
 * длина кода определяется величиной номера, а не таблицей вероятностей.
 *
 * <p>Словарь хранится в заголовке файла один раз и является «внешними данными» для
 * декодера каждого значения.
 */
public final class Vocab {

    /** Таблица атомов, отсортированная лексикографически (для префиксного сжатия). */
    public byte[] atomArena;
    public int[] atomOff;
    public int[] atomLen;
    public int nAtoms;

    /** Узлы в порядке ранга по частоте. parent = -1 у узлов глубины 1. */
    public int[] parent;
    public int[] atom;
    public int nNodes;

    /** Параметр кода bit-varint. */
    public int k;

    /** Размеры частей словаря — для отчёта. */
    public int atomTableBytes, nodeTableBytes;

    /** Развёрнутые строки узлов (строится и кодером, и декодером). */
    public byte[] exp;
    public int[] expOff;
    public int[] expLen;

    /** Индексы кодера. */
    private Maps.BytesMap atomLookup;
    private Maps.LongIntMap child;
    private int[] atomNode;
    private int[] codeBits;
    private int[] cost = new int[1024];
    private int[] bestNode = new int[1024];
    private int[] bestLen = new int[1024];

    static long key(int node, int atomIdx) {
        return ((long) node << 32) | (atomIdx & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------ развёртывание

    /** Построить таблицу развёрнутых строк для всех узлов. */
    public void buildExpansions() {
        ByteVec v = new ByteVec(nNodes * 8 + 64);
        expOff = new int[nNodes];
        expLen = new int[nNodes];
        java.util.Arrays.fill(expLen, -1);
        int[] stack = new int[64];
        for (int r = 0; r < nNodes; r++) {
            if (expLen[r] >= 0) continue;
            int sp = 0;
            int cur = r;
            while (cur >= 0 && expLen[cur] < 0) {
                stack[sp++] = cur;
                if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, sp * 2);
                cur = parent[cur];
            }
            for (int i = sp - 1; i >= 0; i--) {
                int nd = stack[i];
                int p = parent[nd];
                int off = v.n;
                if (p >= 0) v.addSelf(expOff[p], expLen[p]);
                int a = atom[nd];
                v.add(atomArena, atomOff[a], atomLen[a]);
                expOff[nd] = off;
                expLen[nd] = v.n - off;
            }
        }
        exp = v.a;
    }

    /** Построить индексы, нужные только кодеру. */
    public void buildEncoderIndex() {
        atomLookup = new Maps.BytesMap(nAtoms * 2);
        for (int i = 0; i < nAtoms; i++) atomLookup.intern(atomArena, atomOff[i], atomLen[i], 0);
        child = new Maps.LongIntMap(nNodes * 2);
        atomNode = new int[nAtoms];
        java.util.Arrays.fill(atomNode, -1);
        codeBits = new int[nNodes];
        for (int r = 0; r < nNodes; r++) {
            if (parent[r] < 0) atomNode[atom[r]] = r;
            else child.put(key(parent[r], atom[r]), r);
            codeBits[r] = Bits.varBits(r, k);
        }
    }

    // --------------------------------------------------------------- кодирование

    /**
     * Разложить значение на идентификаторы атомов. Атом, отсутствующий в таблице,
     * разбивается на символы, а символ — на байты (однобайтовые атомы есть всегда).
     */
    public void atomize(byte[] a, int off, int len, IntVec out) {
        out.clear();
        int i = off, end = off + len;
        while (i < end) {
            int e = Tokenizer.atomEnd(a, i, end);
            int id = atomLookup.find(a, i, e - i);
            if (id >= 0) {
                out.add(id);
            } else {
                int j = i;
                while (j < e) {
                    int cl = Tokenizer.utf8Len(a, j, e);
                    int cid = atomLookup.find(a, j, cl);
                    if (cid >= 0) {
                        out.add(cid);
                    } else {
                        for (int b = 0; b < cl; b++) out.add(atomLookup.find(a, j + b, 1));
                    }
                    j += cl;
                }
            }
            i = e;
        }
    }

    /**
     * Оптимальный разбор потока атомов (кратчайший путь по решётке разбиений).
     *
     * <p>Жадный «самый длинный подходящий узел» не оптимален: длинная фраза может
     * иметь высокий ранг (длинный код), а два коротких частых узла — суммарно
     * более короткий. Динамика с конца даёт разбиение с минимальным числом бит
     * ровно за то же число обходов дерева, что и жадный разбор, только начиная с
     * каждой позиции. Декодеру ничего знать об этом не нужно — он просто читает
     * номера узлов.
     *
     * @return число выданных кодов
     */
    public int encodeAtoms(IntVec atoms, Bits.Out out) {
        int n = atoms.n;
        if (n == 0) return 0;
        parse(atoms.a, 0, n);
        int codes = 0;
        for (int i = 0; i < n; i += bestLen[i]) {
            out.putVar(bestNode[i], k);
            codes++;
        }
        return codes;
    }

    /** Тот же разбор, но вместо записи — подсчёт частот узлов (используется при обучении). */
    public int countOptimal(int[] t, int off, int n, long[] freq) {
        if (n == 0) return 0;
        parse(t, off, n);
        int codes = 0;
        for (int i = 0; i < n; i += bestLen[i]) {
            freq[bestNode[i]]++;
            codes++;
        }
        return codes;
    }

    /** Динамика с конца: заполняет bestNode/bestLen для t[off .. off+n). */
    private void parse(int[] t, int off, int n) {
        if (cost.length < n + 1) {
            cost = new int[n + 1];
            bestNode = new int[n + 1];
            bestLen = new int[n + 1];
        }
        cost[n] = 0;
        for (int i = n - 1; i >= 0; i--) {
            int node = atomNode[t[off + i]];
            cost[i] = codeBits[node] + cost[i + 1];
            bestNode[i] = node;
            bestLen[i] = 1;
            int j = i + 1;
            while (j < n) {
                int ch = child.get(key(node, t[off + j]));
                if (ch < 0) break;
                node = ch;
                j++;
                int cc = codeBits[node] + cost[j];
                if (cc < cost[i]) {
                    cost[i] = cc;
                    bestNode[i] = node;
                    bestLen[i] = j - i;
                }
            }
        }
    }

    /** Декодировать ровно {@code codes} кодов, дописав байты в {@code out}. */
    public void decodeInto(Bits.In in, int codes, ByteVec out) {
        for (int c = 0; c < codes; c++) {
            int r = (int) in.getVar(k);
            out.add(exp, expOff[r], expLen[r]);
        }
    }

    // ------------------------------------------------------- (де)сериализация

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 22);
        Bits.putUV(o, k);
        Bits.putUV(o, nAtoms);
        // таблица атомов: префиксное (front) кодирование по отсортированному списку
        int pOff = 0, pLen = 0;
        for (int i = 0; i < nAtoms; i++) {
            int len = atomLen[i], off = atomOff[i];
            int shared = 0, m = Math.min(pLen, len);
            while (shared < m && atomArena[pOff + shared] == atomArena[off + shared]) shared++;
            Bits.putUV(o, shared);
            Bits.putUV(o, len - shared);
            o.write(atomArena, off + shared, len - shared);
            pOff = off;
            pLen = len;
        }
        atomTableBytes = o.size();
        // Таблица узлов. Родитель — это ранг, а ранги родителей сильно скошены к нулю
        // (частые фразы наращиваются из частых), поэтому для него выгоден bit-varint.
        // Номер атома распределён почти равномерно — для него выгодна фиксированная ширина.
        Bits.putUV(o, nNodes);
        int aw = bitsFor(nAtoms - 1);
        Bits.Out bo = new Bits.Out(nNodes * 5 + 64);
        for (int r = 0; r < nNodes; r++) {
            bo.putVar(parent[r] + 1, 5);
            bo.put(atom[r], aw);
        }
        byte[] nb = bo.toArray();
        nodeTableBytes = nb.length;
        Bits.putUV(o, nb.length);
        o.write(nb);
        return o.toByteArray();
    }

    static int bitsFor(int maxValue) {
        int b = 0;
        while (maxValue > 0) {
            b++;
            maxValue >>>= 1;
        }
        return Math.max(b, 1);
    }

    public static Vocab deserialize(Bits.ByteCursor c) {
        Vocab v = new Vocab();
        v.k = (int) c.uv();
        v.nAtoms = (int) c.uv();
        v.atomOff = new int[v.nAtoms];
        v.atomLen = new int[v.nAtoms];
        ByteVec arena = new ByteVec(v.nAtoms * 8 + 64);
        int pOff = 0, pLen = 0;
        for (int i = 0; i < v.nAtoms; i++) {
            int shared = (int) c.uv();
            int suf = (int) c.uv();
            int off = arena.n;
            if (shared > 0) arena.addSelf(pOff, shared);
            arena.add(c.a, c.p, suf);
            c.p += suf;
            v.atomOff[i] = off;
            v.atomLen[i] = shared + suf;
            pOff = off;
            pLen = shared + suf;
        }
        v.atomArena = arena.a;
        v.nNodes = (int) c.uv();
        int aw = bitsFor(v.nAtoms - 1);
        int nbLen = (int) c.uv();
        Bits.In bi = new Bits.In(c.a, c.p, nbLen);
        c.p += nbLen;
        v.parent = new int[v.nNodes];
        v.atom = new int[v.nNodes];
        for (int r = 0; r < v.nNodes; r++) {
            v.parent[r] = (int) bi.getVar(5) - 1;
            v.atom[r] = (int) bi.get(aw);
        }
        v.buildExpansions();
        return v;
    }
}
