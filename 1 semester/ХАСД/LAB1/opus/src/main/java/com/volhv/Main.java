package com.volhv;

import com.volhv.hcf.Decoder;
import com.volhv.hcf.Encoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Практическая работа №1: бинарное кодирование структурированных данных.
 *
 * <pre>
 *   encode    &lt;in.jsonl&gt; &lt;out.hcf&gt;   [опции]
 *   decode    &lt;in.hcf&gt;   &lt;out.jsonl&gt;
 *   verify    &lt;a&gt;        &lt;b&gt;
 *   roundtrip &lt;in.jsonl&gt; &lt;work.hcf&gt; &lt;out.jsonl&gt; [опции]
 *
 * опции: --group=N --sample=N --min-atom=N --rounds=N --max-nodes=N --min-pair=N --prune=N
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 2) {
            System.out.println("""
                    Использование:
                      encode    <in.jsonl> <out.hcf>   [опции]
                      decode    <in.hcf>   <out.jsonl>
                      verify    <a>        <b>
                      roundtrip <in.jsonl> <work.hcf> <out.jsonl> [опции]
                    Опции: --group=N --sample=N --min-atom=N --rounds=N --max-nodes=N --min-pair=N --prune=N
                    """);
            return;
        }
        String cmd = args[0];
        switch (cmd) {
            case "encode" -> encode(Path.of(args[1]), Path.of(args[2]), parse(args, 3));
            case "decode" -> new Decoder(Main::log).decode(Path.of(args[1]), Path.of(args[2]));
            case "verify" -> verify(Path.of(args[1]), Path.of(args[2]));
            case "roundtrip" -> {
                Encoder.Params p = parse(args, 4);
                encode(Path.of(args[1]), Path.of(args[2]), p);
                System.out.println();
                new Decoder(Main::log).decode(Path.of(args[2]), Path.of(args[3]));
                System.out.println();
                verify(Path.of(args[1]), Path.of(args[3]));
            }
            default -> System.out.println("неизвестная команда: " + cmd);
        }
    }

    private static void log(String s) {
        if (s.startsWith("  проход") || s.startsWith("  декодировано")) {
            System.out.print("\r" + s + "        ");
            System.out.flush();
        } else {
            System.out.println("\r" + s);
        }
    }

    private static Encoder.Params parse(String[] args, int from) {
        Encoder.Params p = new Encoder.Params();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            int eq = a.indexOf('=');
            if (!a.startsWith("--") || eq < 0) throw new IllegalArgumentException("опция: " + a);
            String key = a.substring(2, eq);
            int v = Integer.parseInt(a.substring(eq + 1));
            switch (key) {
                case "group" -> p.groupSize = v;
                case "sample" -> p.sampleEvery = v;
                case "min-atom" -> p.vocab.minAtomCount = v;
                case "rounds" -> p.vocab.rounds = v;
                case "max-nodes" -> p.vocab.maxNodes = v;
                case "min-pair" -> p.vocab.minPairCount = v;
                case "prune" -> p.vocab.pruneFreq = v;
                default -> throw new IllegalArgumentException("опция: " + key);
            }
        }
        return p;
    }

    private static void encode(Path in, Path out, Encoder.Params p) throws IOException {
        Encoder e = new Encoder(p, Main::log);
        e.encode(in, out);
        report(in, out, e);
    }

    private static void report(Path in, Path out, Encoder e) throws IOException {
        long src = Files.size(in);
        long dst = Files.size(out);
        System.out.println();
        System.out.println("=== размер по столбцам ===");
        System.out.printf("%-26s %-8s %14s %14s %8s%n", "столбец", "кодек", "исходно, Б", "стало, Б", "раз");
        long sum = 0, rawSum = 0;
        for (int i = 0; i < e.specs.length; i++) {
            long raw = e.slotRawBytes[i];
            long got = e.slotBytes[i];
            sum += got;
            rawSum += raw;
            System.out.printf("%-26s %-8s %14s %14s %8s%n",
                    e.template.names[i], e.specs[i].kind, group(raw), group(got),
                    got == 0 ? "—" : String.format("%.1f", raw / (double) got));
        }
        System.out.printf("%-26s %-8s %14s %14s %8s%n", "(итого столбцы)", "", group(rawSum), group(sum),
                String.format("%.1f", rawSum / (double) sum));
        System.out.println();
        System.out.printf("%-26s %14s  -> 0 (в шаблоне: %d Б/строка)%n", "разметка JSON",
                group(e.srcBytes - rawSum), e.template.literalBytes());
        System.out.printf("%-26s %14s%n", "заголовок всего", group(e.headerBytes));
        System.out.printf("%-26s %14s%n", "  в т.ч. словарь фраз", group(e.vocabBytes));
        System.out.printf("%-26s %14s%n", "  в т.ч. шаблон строки", group(e.templateBytes));
        System.out.printf("%-26s %14s%n", "  в т.ч. исключения", group(e.excBytes));
        System.out.printf("%-26s %14s%n", "индекс блоков", group(e.indexBytes));
        System.out.println();
        System.out.printf("исходный файл : %s байт (%.1f МиБ)%n", group(src), src / 1048576.0);
        System.out.printf("закодировано  : %s байт (%.1f МиБ)%n", group(dst), dst / 1048576.0);
        System.out.printf("коэффициент   : %.2fx   (%.3f бит на исходный байт)%n",
                src / (double) dst, dst * 8.0 / src);
    }

    private static String group(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }

    private static void verify(Path a, Path b) throws Exception {
        long sa = Files.size(a), sb = Files.size(b);
        String ha = sha256(a), hb = sha256(b);
        System.out.println("=== проверка идентичности ===");
        System.out.printf("%s  %,d байт  %s%n", a.getFileName(), sa, ha);
        System.out.printf("%s  %,d байт  %s%n", b.getFileName(), sb, hb);
        if (sa == sb && ha.equals(hb)) {
            System.out.println("ОК: файлы совпадают побайтово (sha256 идентичны)");
        } else {
            System.out.println("ОШИБКА: файлы различаются");
            firstDiff(a, b);
            System.exit(1);
        }
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(p)) {
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void firstDiff(Path a, Path b) throws IOException {
        try (InputStream ia = Files.newInputStream(a); InputStream ib = Files.newInputStream(b)) {
            byte[] ba = new byte[1 << 16], bb = new byte[1 << 16];
            long pos = 0;
            while (true) {
                int ra = ia.readNBytes(ba, 0, ba.length);
                int rb = ib.readNBytes(bb, 0, bb.length);
                int n = Math.min(ra, rb);
                for (int i = 0; i < n; i++) {
                    if (ba[i] != bb[i]) {
                        System.out.printf("первое различие на байте %,d: %02x vs %02x%n", pos + i, ba[i], bb[i]);
                        int s = Math.max(0, i - 60);
                        System.out.println("A: " + new String(ba, s, Math.min(120, ra - s)));
                        System.out.println("B: " + new String(bb, s, Math.min(120, rb - s)));
                        return;
                    }
                }
                if (ra != rb) {
                    System.out.printf("длины различаются после байта %,d%n", pos + n);
                    return;
                }
                if (ra <= 0) return;
                pos += ra;
            }
        }
    }
}
