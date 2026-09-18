package com.volhv.hcf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TemplateTest {

    private static final String LINE =
            "{\"id\": \"a_0001\", \"t\": null, \"n\": 2, \"items\": [{\"i\": 0, \"s\": \"аб\"}, "
                    + "{\"i\": 1, \"s\": \"вг\"}], \"tail\": \"x\"}\n";

    /** Приёмник, который тут же складывает значения обратно в исходную форму. */
    private static final class Echo implements Template.Sink, Template.Source {
        final List<byte[]> vals = new ArrayList<>();
        final List<Integer> kinds = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        int vi, ci;

        @Override
        public void scalar(int slot, int kind, byte[] buf, int off, int len) {
            kinds.add(kind);
            vals.add(java.util.Arrays.copyOfRange(buf, off, off + len));
        }

        @Override
        public void count(int slot, int c) {
            counts.add(c);
        }

        @Override
        public void emit(int slot, ByteVec out) {
            int kind = kinds.get(vi);
            byte[] b = vals.get(vi++);
            if (kind == Template.KIND_NULL) out.addAscii("null");
            else if (kind == Template.KIND_STR) {
                out.add((byte) '"');
                out.add(b);
                out.add((byte) '"');
            } else out.add(b);
        }

        @Override
        public int count(int slot) {
            return counts.get(ci++);
        }
    }

    @Test
    void deriveParseWriteIsByteExact() {
        byte[] line = LINE.getBytes(StandardCharsets.UTF_8);
        Template t = Template.derive(line, 0, line.length);
        Echo e = new Echo();
        assertTrue(t.parse(line, 0, line.length, e));
        ByteVec out = new ByteVec();
        t.write(e, out);
        assertArrayEquals(line, out.toArray());
    }

    @Test
    void templateSurvivesSerialization() throws Exception {
        byte[] line = LINE.getBytes(StandardCharsets.UTF_8);
        Template t = Template.derive(line, 0, line.length);
        byte[] ser = t.serialize();
        Template t2 = Template.deserialize(new Bits.ByteCursor(ser, 0));
        assertEquals(t.slotCount, t2.slotCount);
        assertArrayEquals(t.names, t2.names);
        assertArrayEquals(t.isCount, t2.isCount);
        assertArrayEquals(t.ownerCount, t2.ownerCount);

        Echo e = new Echo();
        assertTrue(t2.parse(line, 0, line.length, e));
        ByteVec out = new ByteVec();
        t2.write(e, out);
        assertArrayEquals(line, out.toArray());
    }

    @Test
    void repeatedSlotsKnowTheirCountSlot() {
        byte[] line = LINE.getBytes(StandardCharsets.UTF_8);
        Template t = Template.derive(line, 0, line.length);
        int itemsCount = -1;
        for (int i = 0; i < t.slotCount; i++) if (t.isCount[i]) itemsCount = i;
        assertTrue(itemsCount >= 0, "слот-длина массива должен существовать");
        for (int i = 0; i < t.slotCount; i++) {
            if (t.repeated[i]) assertEquals(itemsCount, t.ownerCount[i], t.names[i]);
            else if (!t.isCount[i]) assertEquals(-1, t.ownerCount[i], t.names[i]);
        }
        // слот-длина всегда объявляется раньше слотов, кратность которых он задаёт
        for (int i = 0; i < t.slotCount; i++) {
            if (t.ownerCount[i] >= 0) assertTrue(t.ownerCount[i] < i);
        }
    }

    @Test
    void mismatchedLineIsRejectedNotMisparsed() {
        byte[] line = LINE.getBytes(StandardCharsets.UTF_8);
        Template t = Template.derive(line, 0, line.length);
        // другой порядок ключей — шаблон не должен совпасть
        byte[] other = "{\"t\": null, \"id\": \"a_0002\", \"n\": 2, \"items\": [{\"i\": 0, \"s\": \"x\"}], \"tail\": \"y\"}\n"
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(t.parse(other, 0, other.length, new Echo()));
    }

    @Test
    void variableArrayLengthParses() {
        byte[] line = LINE.getBytes(StandardCharsets.UTF_8);
        Template t = Template.derive(line, 0, line.length);
        byte[] five = ("{\"id\": \"a_0009\", \"t\": \"з\", \"n\": 5, \"items\": ["
                + "{\"i\": 0, \"s\": \"a\"}, {\"i\": 1, \"s\": \"b\"}, {\"i\": 2, \"s\": \"c\"}, "
                + "{\"i\": 3, \"s\": \"d\"}, {\"i\": 4, \"s\": \"e\"}], \"tail\": \"z\"}\n")
                .getBytes(StandardCharsets.UTF_8);
        Echo e = new Echo();
        assertTrue(t.parse(five, 0, five.length, e));
        assertEquals(5, e.counts.get(0));
        ByteVec out = new ByteVec();
        t.write(e, out);
        assertArrayEquals(five, out.toArray());
    }
}
