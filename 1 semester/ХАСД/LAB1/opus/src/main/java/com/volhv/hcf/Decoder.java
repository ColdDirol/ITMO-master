package com.volhv.hcf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Декодер: HCF -> JSONL, побайтово совпадающий с исходным файлом. */
public final class Decoder {

    private final Consumer<String> log;

    public Template template;
    public Columns.Spec[] specs;
    public Vocab vocab;
    public long nRows;

    public Decoder(Consumer<String> log) {
        this.log = log;
    }

    private void say(String s) {
        if (log != null) log.accept(s);
    }

    private static final class Src implements Template.Source {
        Columns.SlotOut[] outs;

        @Override
        public void emit(int slot, ByteVec out) {
            Columns.SlotOut so = outs[slot];
            int i = so.cursor++;
            if (so.len.a[i] < 0) out.addAscii("null");
            else out.add(so.arena.a, so.off.a[i], so.len.a[i]);
        }

        @Override
        public int count(int slot) {
            Columns.SlotOut so = outs[slot];
            int i = so.cursor++;
            return (int) Columns.parseLong(so.arena.a, so.off.a[i], so.len.a[i]);
        }
    }

    public void decode(Path inPath, Path outPath) throws IOException {
        long t0 = System.nanoTime();
        byte[] all = Files.readAllBytes(inPath);
        for (int i = 0; i < Encoder.MAGIC.length; i++) {
            if (all[i] != Encoder.MAGIC[i]) throw new IOException("не файл HCF");
        }
        Bits.ByteCursor c = new Bits.ByteCursor(all, Encoder.MAGIC.length);
        int version = c.u8();
        if (version != Encoder.VERSION) throw new IOException("версия " + version);
        nRows = c.uv();
        int groupSize = (int) c.uv();
        int tlen = (int) c.uv();
        int tEnd = c.p + tlen;
        template = Template.deserialize(c);
        c.p = tEnd;
        int S = template.slotCount;
        specs = new Columns.Spec[S];
        for (int i = 0; i < S; i++) specs[i] = Columns.Spec.read(c);
        int vlen = (int) c.uv();
        if (vlen > 0) {
            int vEnd = c.p + vlen;
            vocab = Vocab.deserialize(c);
            c.p = vEnd;
        }
        int nExc = (int) c.uv();
        long[] excIdx = new long[nExc];
        byte[][] excData = new byte[nExc][];
        long prev = -1;
        for (int i = 0; i < nExc; i++) {
            excIdx[i] = prev + 1 + c.uv();
            prev = excIdx[i];
            excData[i] = c.bytes((int) c.uv());
        }
        say(String.format("заголовок разобран: %,d строк, группа %d, словарь %,d байт, узлов %,d",
                nRows, groupSize, vlen, vocab == null ? 0 : vocab.nNodes));

        Columns.SlotOut[] outs = new Columns.SlotOut[S];
        for (int i = 0; i < S; i++) outs[i] = new Columns.SlotOut();
        long[] sumOf = new long[S];
        Src src = new Src();
        src.outs = outs;
        ByteVec line = new ByteVec(1 << 16);
        int excPtr = 0;
        long row = 0;

        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(outPath), 1 << 20)) {
            while (true) {
                int rowsInGroup = (int) c.uv();
                if (rowsInGroup == 0) break;
                for (int i = 0; i < S; i++) {
                    int n = template.ownerCount[i] < 0 ? rowsInGroup : (int) sumOf[template.ownerCount[i]];
                    int blen = (int) c.uv();
                    byte[] blk = new byte[blen];
                    System.arraycopy(all, c.p, blk, 0, blen);
                    c.p += blen;
                    Columns.decode(specs[i], blk, n, vocab, outs[i]);
                    if (template.isCount[i]) {
                        long s = 0;
                        for (int r = 0; r < n; r++) {
                            s += Columns.parseLong(outs[i].arena.a, outs[i].off.a[r], outs[i].len.a[r]);
                        }
                        sumOf[i] = s;
                    }
                }
                for (int r = 0; r < rowsInGroup; r++) {
                    while (excPtr < nExc && excIdx[excPtr] == row) {
                        os.write(excData[excPtr++]);
                        row++;
                    }
                    line.clear();
                    template.write(src, line);
                    os.write(line.a, 0, line.n);
                    row++;
                }
                if (log != null) say(String.format("  декодировано %,d строк", row));
            }
            while (excPtr < nExc) {
                os.write(excData[excPtr++]);
                row++;
            }
        }
        say(String.format("декодирование готово за %.1f с: %,d строк", (System.nanoTime() - t0) / 1e9, row));
        if (row != nRows) throw new IOException("ожидалось " + nRows + " строк, получено " + row);
    }
}
