package com.volhv.hcf;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BitsTest {

    @Test
    void varintIsLosslessForAllK() {
        long[] vals = {0, 1, 2, 31, 32, 33, 1023, 1024, 65535, 1 << 20, (1 << 21) - 1, 1_000_000_000L};
        for (int k = 1; k <= 26; k++) {
            Bits.Out o = new Bits.Out();
            for (long v : vals) o.putVar(v, k);
            Bits.In in = new Bits.In(o.toArray());
            for (long v : vals) assertEquals(v, in.getVar(k), "k=" + k + " v=" + v);
        }
    }

    @Test
    void varintCodeSpaceIsComplete() {
        // g групп кодируют ровно [base(g), base(g)+2^(g*k)) — коды не перекрываются
        for (int k = 2; k <= 8; k++) {
            for (long v = 0; v < 5000; v++) {
                int g = Bits.varGroups(v, k);
                assertTrue(v >= Bits.varBase(k, g), "k=" + k + " v=" + v);
                assertTrue(v < Bits.varBase(k, g + 1), "k=" + k + " v=" + v);
                assertEquals(g * (k + 1), Bits.varBits(v, k));
            }
        }
    }

    @Test
    void signedVarintIsLossless() {
        Random r = new Random(7);
        long[] vals = new long[2000];
        for (int i = 0; i < vals.length; i++) vals[i] = r.nextInt(200001) - 100000;
        for (int k = 1; k <= 12; k++) {
            Bits.Out o = new Bits.Out();
            for (long v : vals) o.putSVar(v, k);
            Bits.In in = new Bits.In(o.toArray());
            for (long v : vals) assertEquals(v, in.getSVar(k));
        }
    }

    @Test
    void fixedWidthFieldsAreLossless() {
        Random r = new Random(11);
        int n = 5000;
        int[] widths = new int[n];
        long[] vals = new long[n];
        Bits.Out o = new Bits.Out();
        for (int i = 0; i < n; i++) {
            widths[i] = 1 + r.nextInt(29);
            vals[i] = r.nextLong() >>> (64 - widths[i]);
            o.put(vals[i], widths[i]);
        }
        Bits.In in = new Bits.In(o.toArray());
        for (int i = 0; i < n; i++) assertEquals(vals[i], in.get(widths[i]), "i=" + i);
    }

    @Test
    void byteVarintIsLossless() throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        long[] vals = {0, 1, 127, 128, 300, 16383, 16384, 1L << 40};
        for (long v : vals) Bits.putUV(bo, v);
        Bits.ByteCursor c = new Bits.ByteCursor(bo.toByteArray(), 0);
        for (long v : vals) {
            assertEquals(v, c.uv());
        }
        for (long v : vals) assertTrue(Bits.uvLen(v) >= 1);
    }
}
