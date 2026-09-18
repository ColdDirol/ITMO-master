package com.volhv;

import java.nio.file.Files;
import java.nio.file.Path;

public class RoundTripTest {

    public static void main(String[] args) throws Exception {
        Path source = Path.of("dataset.jsonl");
        Path encoded = Path.of("dataset.bin");
        Path decoded = Path.of("dataset.decoded.jsonl");

        long t0 = System.nanoTime();
        Codec.Stats stats = Codec.encode(source, encoded);
        long t1 = System.nanoTime();
        Codec.decode(encoded, decoded);
        long t2 = System.nanoTime();

        long sourceSize = Files.size(source);
        long encodedSize = Files.size(encoded);
        long mismatch = Files.mismatch(source, decoded);
        double encodeSeconds = (t1 - t0) / 1e9;
        double decodeSeconds = (t2 - t1) / 1e9;
        double gigabytes = sourceSize / 1e9;

        System.out.printf("Rows:              %,d (raw fallback: %,d)%n", stats.rows, stats.rawRows);
        System.out.printf("Source size:       %,d bytes%n", sourceSize);
        System.out.printf("Encoded size:      %,d bytes%n", encodedSize);
        System.out.printf("Compression ratio: %.2fx (%.1f%% of source)%n", (double) sourceSize / encodedSize, 100.0 * encodedSize / sourceSize);
        System.out.printf("Encode time:       %.1f s (%.1f s/GB)%n", encodeSeconds, encodeSeconds / gigabytes);
        System.out.printf("Decode time:       %.1f s (%.1f s/GB)%n", decodeSeconds, decodeSeconds / gigabytes);
        System.out.println("Encoded size by column:");
        System.out.printf("  %-18s %,15d bytes  %5.1f%%%n", "header+vocabulary", stats.dictBytes, 100.0 * stats.dictBytes / encodedSize);
        for (int c = 0; c < Codec.COLUMNS; c++) {
            System.out.printf("  %-18s %,15d bytes  %5.1f%%%n", Codec.columnName(c), stats.columnBytes[c], 100.0 * stats.columnBytes[c] / encodedSize);
        }
        System.out.println(mismatch == -1 ? "INTEGRITY: OK, files are identical" : "INTEGRITY: FAILED at byte " + mismatch);
        System.exit(mismatch == -1 ? 0 : 1);
    }
}
