package com.volhv.hcf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сквозная проверка: кодирование -> декодирование -> побайтовое сравнение. */
class EndToEndTest {

    private static final String[] WORDS = {
            "агрегат", "экосистема", "исследование", "влияние", "вода", "нефть", "анализ",
            "метод", "результат", "процесс", "модель", "данные", "система", "значение",
    };

    private static String doc(Random r, int idx) {
        StringBuilder text = new StringBuilder();
        StringBuilder sents = new StringBuilder();
        int n = 1 + r.nextInt(6);
        for (int s = 0; s < n; s++) {
            int start = text.length();
            StringBuilder sent = new StringBuilder();
            int w = 3 + r.nextInt(8);
            for (int i = 0; i < w; i++) {
                if (i > 0) sent.append(' ');
                sent.append(WORDS[r.nextInt(WORDS.length)]);
            }
            sent.append('.');
            text.append(sent);
            if (s < n - 1) text.append(' ');
            if (s > 0) sents.append(", ");
            sents.append("{\"sent_id\": ").append(s)
                    .append(", \"text\": \"").append(sent)
                    .append("\", \"start_char\": ").append(start)
                    .append(", \"end_char\": ").append(start + sent.length()).append('}');
        }
        String title = r.nextInt(3) == 0 ? "null" : "\"Заголовок \\\"" + idx + "\\\" \\\\ ок\"";
        String date = r.nextInt(2) == 0 ? "null"
                : String.format("\"%04d-%02d-%02d\"", 2000 + r.nextInt(25), 1 + r.nextInt(12), 1 + r.nextInt(28));
        return "{\"doc_id\": \"corp_ru_" + String.format("%06d", idx + 700)
                + "\", \"lang\": \"ru\", \"domain\": \"" + (idx % 2 == 0 ? "news" : "abstracts")
                + "\", \"title\": " + title
                + ", \"date\": " + date
                + ", \"full_text\": \"" + text + "\""
                + ", \"n_sentences\": " + n
                + ", \"sentences\": [" + sents + "]"
                + ", \"label\": \"human\", \"score\": " + (r.nextInt(2001) - 1000)
                + ", \"source_model\": null}\n";
    }

    private static void roundTrip(Path dir, String content, Encoder.Params p) throws IOException {
        Path in = dir.resolve("in.jsonl");
        Path bin = dir.resolve("out.hcf");
        Path out = dir.resolve("back.jsonl");
        byte[] original = content.getBytes(StandardCharsets.UTF_8);
        Files.write(in, original);
        new Encoder(p, null).encode(in, bin);
        new Decoder(null).decode(bin, out);
        assertArrayEquals(original, Files.readAllBytes(out));
        assertTrue(Files.size(bin) > 0);
    }

    private static Encoder.Params params() {
        Encoder.Params p = new Encoder.Params();
        p.groupSize = 64;                 // несколько групп даже на маленьком входе
        p.vocab.minAtomCount = 2;
        p.vocab.rounds = 5;
        p.vocab.maxNodes = 1 << 15;
        p.vocab.minPairCount = 2;
        p.vocab.pruneFreq = 0;
        p.vocab.pairMapBits = 16;
        return p;
    }

    @Test
    void syntheticDatasetIsRestoredByteExactly(@TempDir Path dir) throws IOException {
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append(doc(r, i));
        roundTrip(dir, sb.toString(), params());
    }

    @Test
    void singleRowWorks(@TempDir Path dir) throws IOException {
        roundTrip(dir, doc(new Random(1), 0), params());
    }

    @Test
    void lineNotMatchingTheTemplateFallsBackToRaw(@TempDir Path dir) throws IOException {
        Random r = new Random(5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append(doc(r, i));
        // строка с другим набором ключей: должна уйти в поток исключений и уцелеть
        sb.append("{\"totally\": \"different\", \"shape\": [1, 2, 3]}\n");
        for (int i = 40; i < 80; i++) sb.append(doc(r, i));
        roundTrip(dir, sb.toString(), params());
    }

    @Test
    void fileWithoutTrailingNewlineWorks(@TempDir Path dir) throws IOException {
        Random r = new Random(9);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append(doc(r, i));
        sb.setLength(sb.length() - 1);      // убрать последний '\n'
        roundTrip(dir, sb.toString(), params());
    }

    @Test
    void compressesBetterThanTwiceOnSyntheticData(@TempDir Path dir) throws IOException {
        Random r = new Random(77);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append(doc(r, i));
        Path in = dir.resolve("in.jsonl");
        Path bin = dir.resolve("out.hcf");
        Files.write(in, sb.toString().getBytes(StandardCharsets.UTF_8));
        new Encoder(params(), null).encode(in, bin);
        double ratio = Files.size(in) / (double) Files.size(bin);
        assertTrue(ratio > 2.0, "коэффициент сжатия слишком мал: " + ratio);
    }
}
