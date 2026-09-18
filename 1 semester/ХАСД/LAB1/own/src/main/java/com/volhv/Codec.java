package com.volhv;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Codec {
    static final int BLOCK_ROWS = 20_000;
    static final String[] KEYS = {"doc_id", "lang", "domain", "source_dataset", "title", "date", "full_text",
            "n_sentences", "sentences", "label", "source_model", "generation_type", "seed_doc_id"};
    static final int ID = 0;
    static final int TEXT = 6;
    static final int COUNT = 7;
    static final int SENTENCES = 8;
    static final int RAW = KEYS.length;
    static final int COLUMNS = KEYS.length + 1;
    static final int[] RUN_COLUMNS = {1, 2, 3, 4, 5, 9, 10, 11, 12, RAW};

    static class Stats {
        long rows, rawRows, dictBytes;
        long[] columnBytes = new long[COLUMNS];
    }

    static String columnName(int c) {
        return c < KEYS.length ? KEYS[c] : "raw";
    }

    static Stats encode(Path in, Path out) throws IOException {
        Vocabulary.Counter counter = new Vocabulary.Counter();
        try (BufferedReader reader = Files.newBufferedReader(in)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Record r = Record.parse(line);
                if (r == null) continue;
                counter.addTokens(r.f[TEXT]);
                counter.addChars(r.f[TEXT]);
                for (String s : r.sentences) counter.addChars(s);
            }
        }
        Vocabulary vocab = counter.build();

        Stats stats = new Stats();
        Buf head = new Buf();
        head.varint(endsWithNewline(in) ? 1 : 0);
        writeVocabulary(head, vocab);
        stats.dictBytes = head.size();

        try (BufferedReader reader = Files.newBufferedReader(in);
             OutputStream o = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
            head.writeTo(o);
            List<Record> block = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                Record r = Record.parse(line);
                if (r == null) {
                    r = Record.raw(line);
                    stats.rawRows++;
                }
                block.add(r);
                stats.rows++;
                if (block.size() == BLOCK_ROWS) {
                    writeBlock(o, block, vocab, stats);
                    block.clear();
                }
            }
            if (!block.isEmpty()) writeBlock(o, block, vocab, stats);
            o.write(0);
        }
        return stats;
    }

    static void decode(Path in, Path out) throws IOException {
        In src = new In(Files.readAllBytes(in), 0);
        boolean endsWithNewline = src.varint() == 1;
        Vocabulary vocab = readVocabulary(src);
        try (Writer w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(out), StandardCharsets.UTF_8), 1 << 16)) {
            boolean first = true;
            int rows;
            while ((rows = (int) src.varint()) > 0) {
                int[] lengths = new int[COLUMNS];
                for (int c = 0; c < COLUMNS; c++) lengths[c] = (int) src.varint();
                In[] cols = new In[COLUMNS];
                for (int c = 0; c < COLUMNS; c++) cols[c] = src.slice(lengths[c]);
                for (Record r : readBlock(cols, rows, vocab)) {
                    if (!first) w.write('\n');
                    first = false;
                    w.write(r.toJson());
                }
            }
            if (endsWithNewline) w.write('\n');
        }
    }

    static boolean endsWithNewline(Path in) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(in.toFile(), "r")) {
            if (f.length() == 0) return false;
            f.seek(f.length() - 1);
            return f.read() == '\n';
        }
    }

    static void writeBlock(OutputStream out, List<Record> rows, Vocabulary vocab, Stats stats) throws IOException {
        Buf[] cols = new Buf[COLUMNS];
        for (int c = 0; c < COLUMNS; c++) cols[c] = new Buf();
        writeIds(cols[ID], rows);
        for (Record r : rows) {
            writeText(cols[TEXT], r.f[TEXT], vocab);
            cols[COUNT].varint(r.count);
            cols[SENTENCES].varint(r.sentences.size());
            for (String s : r.sentences) writeText(cols[SENTENCES], s, vocab);
        }
        for (int c : RUN_COLUMNS) writeRuns(cols[c], rows, c);

        Buf head = new Buf();
        head.varint(rows.size());
        for (int c = 0; c < COLUMNS; c++) {
            head.varint(cols[c].size());
            stats.columnBytes[c] += cols[c].size();
        }
        head.writeTo(out);
        for (Buf b : cols) b.writeTo(out);
    }

    static List<Record> readBlock(In[] cols, int rows, Vocabulary vocab) {
        List<Record> result = new ArrayList<>();
        for (int i = 0; i < rows; i++) result.add(new Record());
        readIds(cols[ID], result);
        for (Record r : result) {
            r.f[TEXT] = readText(cols[TEXT], vocab);
            r.count = cols[COUNT].varint();
            int n = (int) cols[SENTENCES].varint();
            for (int i = 0; i < n; i++) r.sentences.add(readText(cols[SENTENCES], vocab));
        }
        for (int c : RUN_COLUMNS) readRuns(cols[c], result, c);
        return result;
    }

    static void writeIds(Buf b, List<Record> rows) {
        String prevPrefix = null;
        int prevWidth = -1;
        long prevNum = 0;
        for (Record r : rows) {
            String s = r.f[ID];
            int i = s.length();
            while (i > 0 && s.length() - i < 18 && s.charAt(i - 1) >= '0' && s.charAt(i - 1) <= '9') i--;
            String prefix = s.substring(0, i);
            int width = s.length() - i;
            long num = width == 0 ? 0 : Long.parseLong(s.substring(i));
            if (prefix.equals(prevPrefix) && width == prevWidth) {
                if (num == prevNum + 1) {
                    b.varint(0);
                } else {
                    b.varint(1);
                    b.varint(zigzag(num - prevNum));
                }
            } else {
                b.varint(2);
                b.str(prefix);
                b.varint(width);
                b.varint(num);
            }
            prevPrefix = prefix;
            prevWidth = width;
            prevNum = num;
        }
    }

    static void readIds(In in, List<Record> rows) {
        String prefix = "";
        int width = 0;
        long num = 0;
        for (Record r : rows) {
            int mode = (int) in.varint();
            if (mode == 0) {
                num++;
            } else if (mode == 1) {
                num += unzigzag(in.varint());
            } else {
                prefix = in.str();
                width = (int) in.varint();
                num = in.varint();
            }
            r.f[ID] = prefix + (width == 0 ? "" : String.format("%0" + width + "d", num));
        }
    }

    static void writeRuns(Buf b, List<Record> rows, int c) {
        int i = 0;
        while (i < rows.size()) {
            String value = rows.get(i).f[c];
            int j = i + 1;
            while (j < rows.size() && Objects.equals(rows.get(j).f[c], value)) j++;
            b.varint(j - i);
            b.str(value);
            i = j;
        }
    }

    static void readRuns(In in, List<Record> rows, int c) {
        int i = 0;
        while (i < rows.size()) {
            int run = (int) in.varint();
            String value = in.str();
            for (int k = 0; k < run; k++) rows.get(i++).f[c] = value;
        }
    }

    static void writeText(Buf b, String s, Vocabulary vocab) {
        for (String token : Vocabulary.tokenize(s)) {
            Integer code = vocab.tokenCode.get(token);
            if (code != null) {
                b.varint(code);
            } else {
                b.varint(1);
                writeChars(b, token, vocab);
            }
        }
        b.varint(0);
    }

    static String readText(In in, Vocabulary vocab) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int code = (int) in.varint();
            if (code == 0) return sb.toString();
            if (code == 1) sb.append(readChars(in, vocab.chars));
            else sb.append(vocab.tokens[code - 2]);
        }
    }

    static void writeChars(Buf b, String s, Vocabulary vocab) {
        b.varint(s.codePointCount(0, s.length()));
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            b.varint(vocab.charCode.get(cp));
            i += Character.charCount(cp);
        }
    }

    static String readChars(In in, int[] chars) {
        int n = (int) in.varint();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.appendCodePoint(chars[(int) in.varint()]);
        return sb.toString();
    }

    static void writeVocabulary(Buf b, Vocabulary vocab) {
        b.varint(vocab.chars.length);
        for (int cp : vocab.chars) b.varint(cp);
        b.varint(vocab.tokens.length);
        for (String token : vocab.tokens) writeChars(b, token, vocab);
    }

    static Vocabulary readVocabulary(In in) {
        int[] chars = new int[(int) in.varint()];
        for (int i = 0; i < chars.length; i++) chars[i] = (int) in.varint();
        String[] tokens = new String[(int) in.varint()];
        for (int i = 0; i < tokens.length; i++) tokens[i] = readChars(in, chars);
        return new Vocabulary(chars, tokens);
    }

    static long zigzag(long v) {
        return (v << 1) ^ (v >> 63);
    }

    static long unzigzag(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    static class Record {
        final String[] f = new String[COLUMNS];
        long count;
        List<String> sentences = new ArrayList<>();

        static Record raw(String line) {
            Record r = new Record();
            r.f[ID] = "";
            r.f[TEXT] = "";
            r.f[RAW] = line;
            return r;
        }

        @SuppressWarnings("unchecked")
        static Record parse(String line) {
            try {
                Map<String, Object> map = (Map<String, Object>) Json.parse(line);
                if (!Arrays.asList(KEYS).equals(new ArrayList<>(map.keySet()))) return null;
                Record r = new Record();
                for (int i = 0; i < KEYS.length; i++) {
                    if (i != COUNT && i != SENTENCES) r.f[i] = (String) map.get(KEYS[i]);
                }
                r.count = (Long) map.get("n_sentences");
                for (Object o : (List<Object>) map.get("sentences")) {
                    r.sentences.add((String) ((Map<String, Object>) o).get("text"));
                }
                if (r.f[ID] == null || r.f[TEXT] == null || r.count < 0 || r.sentences.contains(null)) return null;
                return r.toJson().equals(line) ? r : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        String toJson() {
            if (f[RAW] != null) return f[RAW];
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < KEYS.length; i++) map.put(KEYS[i], f[i]);
            map.put("n_sentences", count);
            List<Object> list = new ArrayList<>();
            long end = 0;
            for (int i = 0; i < sentences.size(); i++) {
                String text = sentences.get(i);
                long start = i == 0 ? 0 : end + 1;
                end = start + text.codePointCount(0, text.length());
                Map<String, Object> sentence = new LinkedHashMap<>();
                sentence.put("sent_id", (long) i);
                sentence.put("text", text);
                sentence.put("start_char", start);
                sentence.put("end_char", end);
                list.add(sentence);
            }
            map.put("sentences", list);
            return Json.write(map);
        }
    }

    static class Buf extends ByteArrayOutputStream {
        void varint(long v) {
            while (v >= 0x80) {
                write((int) (v & 0x7F) | 0x80);
                v >>>= 7;
            }
            write((int) v);
        }

        void str(String s) {
            if (s == null) {
                varint(0);
                return;
            }
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            varint(bytes.length + 1L);
            write(bytes, 0, bytes.length);
        }
    }

    static class In {
        final byte[] data;
        int pos;

        In(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        long varint() {
            long v = 0;
            int shift = 0;
            while (true) {
                int x = data[pos++];
                v |= (long) (x & 0x7F) << shift;
                if (x >= 0) return v;
                shift += 7;
            }
        }

        String str() {
            int n = (int) varint();
            if (n == 0) return null;
            String s = new String(data, pos, n - 1, StandardCharsets.UTF_8);
            pos += n - 1;
            return s;
        }

        In slice(int length) {
            In part = new In(data, pos);
            pos += length;
            return part;
        }
    }
}
