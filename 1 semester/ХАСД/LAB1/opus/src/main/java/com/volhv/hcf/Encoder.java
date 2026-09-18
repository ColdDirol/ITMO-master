package com.volhv.hcf;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Кодер: JSONL -> HCF.
 *
 * <p>Два прохода по файлу. Первый собирает статистику столбцов и обучает словарь фраз;
 * второй пишет заголовок (шаблон строки, описания столбцов, словарь) и затем группы
 * строк, где каждый столбец лежит отдельным блоком.
 */
public final class Encoder {

    public static final byte[] MAGIC = {'H', 'C', 'F', '1'};
    public static final int VERSION = 1;

    public static final class Params {
        public int groupSize = 4096;
        /** Обучать словарь на каждом N-м документе (1 = на всём датасете). */
        public int sampleEvery = 1;
        public VocabBuilder.Params vocab = new VocabBuilder.Params();
    }

    public final Params p;
    private final Consumer<String> log;

    /** Отчёт о размерах — для обоснования сжатия. */
    public Template template;
    public Columns.Spec[] specs;
    public long[] slotBytes;
    public long[] slotRawBytes;
    public long headerBytes, vocabBytes, templateBytes, excBytes, indexBytes;
    public long nRows, nValues, srcBytes;
    public Vocab vocab;

    public Encoder(Params p, Consumer<String> log) {
        this.p = p;
        this.log = log;
    }

    private void say(String s) {
        if (log != null) log.accept(s);
    }

    // ------------------------------------------------------------------ сбор

    /** Накопитель значений строки: позволяет откатить строку, не совпавшую с шаблоном. */
    private static final class Staging implements Template.Sink {
        int[] rec = new int[1 << 14];
        int n;

        @Override
        public void scalar(int slot, int kind, byte[] buf, int off, int len) {
            grow();
            rec[n++] = slot;
            rec[n++] = kind;
            rec[n++] = off;
            rec[n++] = len;
        }

        @Override
        public void count(int slot, int c) {
            grow();
            rec[n++] = slot;
            rec[n++] = -1;
            rec[n++] = c;
            rec[n++] = 0;
        }

        private void grow() {
            if (n + 4 > rec.length) rec = java.util.Arrays.copyOf(rec, rec.length * 2);
        }

        void reset() {
            n = 0;
        }
    }

    public void encode(Path inPath, Path outPath) throws IOException {
        long t0 = System.nanoTime();

        // ---------- проход 1: шаблон, статистика, обучение словаря ----------
        byte[] firstLine;
        try (Lines lr = new Lines(new BufferedInputStream(new FileInputStream(inPath.toFile()), 1 << 20), 1 << 22)) {
            if (!lr.next()) throw new IOException("пустой файл");
            firstLine = java.util.Arrays.copyOfRange(lr.buf, lr.off, lr.off + lr.len);
        }
        template = Template.derive(firstLine, 0, firstLine.length);
        int S = template.slotCount;
        say(String.format("шаблон: %d слотов, %d байт разметки на строку", S, template.literalBytes()));

        Columns.Stats[] st = new Columns.Stats[S];
        for (int i = 0; i < S; i++) st[i] = new Columns.Stats(template.names[i]);
        VocabBuilder vb = new VocabBuilder();
        Staging stage = new Staging();
        List<Long> excIdx = new ArrayList<>();
        List<byte[]> excData = new ArrayList<>();
        long rows = 0;
        srcBytes = 0;

        try (Lines lr = new Lines(new BufferedInputStream(new FileInputStream(inPath.toFile()), 1 << 20), 1 << 22)) {
            while (lr.next()) {
                srcBytes += lr.len;
                stage.reset();
                boolean ok = template.parse(lr.buf, lr.off, lr.len, stage);
                if (!ok) {
                    excIdx.add(rows);
                    excData.add(java.util.Arrays.copyOfRange(lr.buf, lr.off, lr.off + lr.len));
                    rows++;
                    continue;
                }
                boolean train = (rows % p.sampleEvery) == 0;
                for (int i = 0; i < stage.n; i += 4) {
                    int slot = stage.rec[i], kind = stage.rec[i + 1], off = stage.rec[i + 2], len = stage.rec[i + 3];
                    if (kind < 0) {
                        st[slot].addCount(off);
                    } else {
                        st[slot].add(kind, lr.buf, off, len);
                        if (kind == Template.KIND_STR && len > 0) {
                            vb.scanChars(lr.buf, off, len);
                            if (train) vb.scanTrain(lr.buf, off, len);
                        }
                    }
                }
                rows++;
                if (log != null && rows % 10000 == 0) say(String.format("  проход 1: %,d строк", rows));
            }
        }
        nRows = rows;
        say(String.format("проход 1 готов за %.1f с: %,d строк, %,d байт", (System.nanoTime() - t0) / 1e9, rows, srcBytes));

        // ---------- выбор кодеков ----------
        specs = new Columns.Spec[S];
        slotBytes = new long[S];
        slotRawBytes = new long[S];
        for (int i = 0; i < S; i++) {
            Columns.Spec sp = new Columns.Spec();
            sp.kind = st[i].pick();
            sp.hasNull = st[i].nNull > 0;
            slotRawBytes[i] = st[i].textBytes;
            nValues += st[i].n;
            if (sp.kind == Columns.Kind.CONST) {
                Maps.BytesMap d = st[i].distinct;
                sp.constVal = java.util.Arrays.copyOfRange(d.arena.a, d.off[0], d.off[0] + d.len[0]);
                sp.constQuoted = !st[i].allNum;   // числовую константу нельзя брать в кавычки
            } else if (sp.kind == Columns.Kind.DICT) {
                Maps.BytesMap d = st[i].distinct;
                for (int j = 0; j < d.size; j++) {
                    sp.dict.add(java.util.Arrays.copyOfRange(d.arena.a, d.off[j], d.off[j] + d.len[j]));
                }
                sp.buildIndex();
            } else if (sp.kind == Columns.Kind.IDNUM) {
                Maps.BytesMap d = st[i].prefixes;
                for (int j = 0; j < d.size; j++) {
                    sp.dict.add(java.util.Arrays.copyOfRange(d.arena.a, d.off[j], d.off[j] + d.len[j]));
                }
                sp.buildIndex();
            }
            specs[i] = sp;
            say(String.format("  столбец %-24s %-8s null=%-7d значений=%,d", template.names[i],
                    sp.kind, st[i].nNull, st[i].n));
        }

        // ---------- словарь фраз ----------
        boolean needVocab = false;
        for (Columns.Spec sp : specs) if (sp.kind == Columns.Kind.TEXT) needVocab = true;
        if (needVocab) {
            long tv = System.nanoTime();
            say("обучение словаря фраз (" + p.vocab + ", выборка 1/" + p.sampleEvery + ")");
            vocab = vb.build(p.vocab, log);
            say(String.format("словарь готов за %.1f с", (System.nanoTime() - tv) / 1e9));
        }

        // ---------- проход 2: запись ----------
        long t2 = System.nanoTime();
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(outPath), 1 << 20)) {
            ByteArrayOutputStream hdr = new ByteArrayOutputStream(1 << 22);
            hdr.write(MAGIC);
            hdr.write(VERSION);
            Bits.putUV(hdr, nRows);
            Bits.putUV(hdr, p.groupSize);
            byte[] tb = template.serialize();
            templateBytes = tb.length;
            Bits.putUV(hdr, tb.length);
            hdr.write(tb);
            int specStart = hdr.size();
            for (Columns.Spec sp : specs) sp.write(hdr);
            int specLen = hdr.size() - specStart;
            byte[] vbytes = vocab == null ? new byte[0] : vocab.serialize();
            vocabBytes = vbytes.length;
            Bits.putUV(hdr, vbytes.length);
            hdr.write(vbytes);
            Bits.putUV(hdr, excIdx.size());
            long prev = -1;
            for (int i = 0; i < excIdx.size(); i++) {
                Bits.putUV(hdr, excIdx.get(i) - prev - 1);
                prev = excIdx.get(i);
                Bits.putUV(hdr, excData.get(i).length);
                hdr.write(excData.get(i));
                excBytes += excData.get(i).length;
            }
            byte[] h = hdr.toByteArray();
            headerBytes = h.length;
            os.write(h);
            say(String.format("заголовок %,d байт (шаблон %,d, описания %,d, словарь %,d, исключения %,d)",
                    h.length, templateBytes, specLen, vocabBytes, excBytes));

            Columns.SlotBuf[] bufs = new Columns.SlotBuf[S];
            for (int i = 0; i < S; i++) bufs[i] = new Columns.SlotBuf();
            IntVec atomBuf = new IntVec(1 << 12);
            byte[] numTmp = new byte[24];
            int inGroup = 0;
            long groups = 0;
            int excPtr = 0;
            long row = 0;

            try (Lines lr = new Lines(new BufferedInputStream(new FileInputStream(inPath.toFile()), 1 << 20), 1 << 22)) {
                while (lr.next()) {
                    if (excPtr < excIdx.size() && excIdx.get(excPtr) == row) {
                        excPtr++;
                        row++;
                        continue;
                    }
                    stage.reset();
                    if (!template.parse(lr.buf, lr.off, lr.len, stage)) {
                        throw new IOException("строка " + row + " не совпала с шаблоном во втором проходе");
                    }
                    for (int i = 0; i < stage.n; i += 4) {
                        int slot = stage.rec[i], kind = stage.rec[i + 1], off = stage.rec[i + 2], len = stage.rec[i + 3];
                        if (kind < 0) {
                            int l = fmt(numTmp, off);
                            bufs[slot].add(numTmp, 0, l);
                        } else if (kind == Template.KIND_NULL) {
                            bufs[slot].addNull();
                        } else {
                            bufs[slot].add(lr.buf, off, len);
                        }
                    }
                    inGroup++;
                    row++;
                    if (inGroup == p.groupSize) {
                        flush(os, bufs, inGroup, atomBuf);
                        inGroup = 0;
                        groups++;
                        say(String.format("  проход 2: %,d строк", row));
                    }
                }
            }
            if (inGroup > 0) {
                flush(os, bufs, inGroup, atomBuf);
                groups++;
            }
            Bits.putUV(os, 0);   // маркер конца групп
            say(String.format("проход 2 готов за %.1f с: %,d групп", (System.nanoTime() - t2) / 1e9, groups));
        }
        say(String.format("всего %.1f с", (System.nanoTime() - t0) / 1e9));
    }

    private static int fmt(byte[] out, int v) {
        if (v == 0) {
            out[0] = '0';
            return 1;
        }
        int n = 0;
        while (v > 0) {
            out[n++] = (byte) ('0' + v % 10);
            v /= 10;
        }
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            byte t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return n;
    }

    private void flush(OutputStream os, Columns.SlotBuf[] bufs, int rowsInGroup, IntVec atomBuf) throws IOException {
        Bits.putUV(os, rowsInGroup);
        for (int i = 0; i < bufs.length; i++) {
            byte[] blk = Columns.encode(specs[i], bufs[i], vocab, atomBuf);
            Bits.putUV(os, blk.length);
            os.write(blk);
            slotBytes[i] += blk.length;
            indexBytes += Bits.uvLen(blk.length);
            bufs[i].clear();
        }
        indexBytes += Bits.uvLen(rowsInGroup);
    }
}
