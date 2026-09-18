package com.volhv.hcf;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class IntCodecTest {

    private static void check(long[] v) {
        Bits.Out o = new Bits.Out();
        IntCodec.encode(v, 0, v.length, o);
        long[] back = new long[v.length];
        IntCodec.decode(new Bits.In(o.toArray()), back, 0, v.length);
        assertArrayEquals(v, back);
    }

    @Test
    void constant() {
        long[] v = new long[3000];
        java.util.Arrays.fill(v, 42);
        check(v);
    }

    @Test
    void runs() {
        long[] v = new long[5000];
        for (int i = 0; i < v.length; i++) v[i] = i / 700;
        check(v);
    }

    @Test
    void monotoneWithResets() {
        // как sentences.start_char: растёт внутри документа, сбрасывается на границе
        Random r = new Random(3);
        long[] v = new long[10000];
        long cur = 0;
        for (int i = 0; i < v.length; i++) {
            if (r.nextInt(20) == 0) cur = 0;
            else cur += 20 + r.nextInt(300);
            v[i] = cur;
        }
        check(v);
    }

    @Test
    void narrowRangeWithOutliers() {
        Random r = new Random(5);
        long[] v = new long[4000];
        for (int i = 0; i < v.length; i++) v[i] = r.nextInt(200) + 2;
        v[100] = 1_000_000;
        v[3000] = -500_000;
        check(v);
    }

    @Test
    void negativeAndExtremes() {
        check(new long[]{0});
        check(new long[]{-Columns.NUM_LIMIT, 0, Columns.NUM_LIMIT});
        check(new long[]{-1, -1, -1, 5, 5});
    }

    @Test
    void valuesOutsideTheSupportedRangeAreNotTreatedAsNumbers() {
        byte[] big = "9007199254740993000".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertEquals(Long.MIN_VALUE,
                Columns.parseLong(big, 0, big.length));
    }

    @Test
    void randomWide() {
        Random r = new Random(9);
        long[] v = new long[3000];
        for (int i = 0; i < v.length; i++) v[i] = r.nextLong() >> 20;
        check(v);
    }

    @Test
    void sequentialIndex() {
        // как sentences.sent_id: 0,1,2,..,n-1 с перезапуском
        long[] v = new long[8000];
        int k = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] = k++;
            if (k > 15) k = 0;
        }
        check(v);
    }
}
