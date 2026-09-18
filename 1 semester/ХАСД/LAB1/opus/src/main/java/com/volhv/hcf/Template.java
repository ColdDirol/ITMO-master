package com.volhv.hcf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Структурный шаблон строки JSONL.
 *
 * <p>Датасет — JSONL с фиксированной схемой: все 53410 строк имеют одинаковый набор и
 * порядок ключей. Значит, вся «разметка» строки (имена ключей, кавычки, запятые,
 * двоеточия, скобки) — это константа, одинаковая для всех строк. Шаблон выделяет её
 * в заголовок файла ровно один раз, а в потоке данных остаются только значения.
 * На этом датасете разметка — 68.8 МБ (13.0 %) исходного размера.
 *
 * <p>Шаблон — последовательность сегментов:
 * <ul>
 *   <li>{@link Lit} — литеральные байты, которые обязаны совпасть;
 *   <li>{@link Val} — слот скалярного значения (строка / число / null), уходит в столбец;
 *   <li>{@link Rep} — повторяющаяся группа (массив {@code sentences}): подшаблон элемента,
 *       литерал-разделитель и слот для длины массива.
 * </ul>
 *
 * <p>Шаблон выводится автоматически из первой строки файла и затем проверяется на
 * каждой строке. Строка, не совпавшая с шаблоном, уходит в поток исключений «как есть»,
 * поэтому побайтовая точность гарантирована для любых входных данных.
 */
public final class Template {

    public static final int KIND_NULL = 0;
    public static final int KIND_STR = 1;
    public static final int KIND_NUM = 2;

    public abstract static class Seg {
    }

    public static final class Lit extends Seg {
        public final byte[] b;

        Lit(byte[] b) {
            this.b = b;
        }
    }

    /** Слот скалярного значения. */
    public static final class Val extends Seg {
        public final int slot;

        Val(int slot) {
            this.slot = slot;
        }
    }

    /** Повторяющаяся группа: массив объектов. */
    public static final class Rep extends Seg {
        public final Template elem;
        public final byte[] sep;
        public final int countSlot;

        Rep(Template elem, byte[] sep, int countSlot) {
            this.elem = elem;
            this.sep = sep;
            this.countSlot = countSlot;
        }
    }

    public final List<Seg> segs = new ArrayList<>();
    /** Число скалярных слотов (включая вложенные) и слотов-длин во всём шаблоне. */
    public int slotCount;
    /** true, если слот i находится внутри Rep (значение на каждый элемент массива). */
    public boolean[] repeated;
    /** true, если слот i — длина массива. */
    public boolean[] isCount;
    /** Слот-длина, задающий кратность слота i, или -1 для слотов верхнего уровня. */
    public int[] ownerCount;
    /** Имя слота для отчётов. */
    public String[] names;

    // --------------------------------------------------------------- получатель

    /** Приёмник значений при разборе строки. */
    public interface Sink {
        void scalar(int slot, int kind, byte[] buf, int off, int len);

        void count(int slot, int n);
    }

    // ------------------------------------------------------------------- вывод

    private static final class Deriver {
        final byte[] a;
        int p;
        int slots;
        final List<String> names = new ArrayList<>();
        final List<Boolean> rep = new ArrayList<>();
        final List<Boolean> cnt = new ArrayList<>();
        final List<Integer> owner = new ArrayList<>();
        int curOwner = -1;

        Deriver(byte[] a) {
            this.a = a;
        }

        int newSlot(String name, boolean inRep, boolean isCount) {
            names.add(name);
            rep.add(inRep);
            cnt.add(isCount);
            owner.add(isCount ? curOwner : curOwner);
            return slots++;
        }
    }

    /** Вывести шаблон из одной (первой) строки файла. */
    public static Template derive(byte[] line, int off, int len) {
        Deriver d = new Deriver(line);
        d.p = off;
        Template t = new Template();
        deriveValue(d, t, off + len, false, "");
        // хвост строки (перевод строки) — литерал
        if (d.p < off + len) t.segs.add(new Lit(slice(line, d.p, off + len - d.p)));
        t.slotCount = d.slots;
        t.repeated = new boolean[d.slots];
        t.isCount = new boolean[d.slots];
        t.ownerCount = new int[d.slots];
        t.names = new String[d.slots];
        for (int i = 0; i < d.slots; i++) {
            t.repeated[i] = d.rep.get(i);
            t.isCount[i] = d.cnt.get(i);
            t.ownerCount[i] = d.owner.get(i);
            t.names[i] = d.names.get(i);
        }
        return t;
    }

    /**
     * Разобрать один JSON-объект или значение, дописывая сегменты в {@code t}.
     * Литералы накапливаются между значениями.
     */
    private static void deriveValue(Deriver d, Template t, int end, boolean inRep, String path) {
        byte[] a = d.a;
        int litStart = d.p;
        if (a[d.p] != '{') throw new IllegalArgumentException("ожидался объект JSON");
        d.p++; // '{'
        while (true) {
            skipWs(a, d);
            if (a[d.p] == ',') {
                d.p++;
                skipWs(a, d);
            }
            if (a[d.p] == '}') {
                d.p++;
                break;
            }
            // ключ
            int ks = d.p;
            if (a[d.p] != '"') throw new IllegalArgumentException("ожидался ключ");
            int ke = scanString(a, d.p);
            String key = new String(a, ks + 1, ke - ks - 2, java.nio.charset.StandardCharsets.UTF_8);
            d.p = ke;
            skipWs(a, d);
            if (a[d.p] != ':') throw new IllegalArgumentException("ожидалось ':'");
            d.p++;
            skipWs(a, d);
            String vpath = path.isEmpty() ? key : path + "." + key;

            byte c = a[d.p];
            if (c == '[') {
                // литерал до и включая '['
                t.segs.add(new Lit(slice(a, litStart, d.p + 1 - litStart)));
                d.p++;
                int countSlot = d.newSlot(vpath + "[]", inRep, true);
                skipWs(a, d);
                if (a[d.p] == ']') {
                    // пустой массив в первой строке — подшаблон вывести нельзя
                    throw new IllegalArgumentException("пустой массив в первой строке: " + vpath);
                }
                Template elem = new Template();
                int savedOwner = d.curOwner;
                d.curOwner = countSlot;
                deriveValue(d, elem, end, true, vpath);
                d.curOwner = savedOwner;
                // разделитель между элементами
                int sepStart = d.p;
                while (a[d.p] != '{' && a[d.p] != ']') d.p++;
                byte[] sep = slice(a, sepStart, d.p - sepStart);
                if (sep.length == 0) sep = new byte[]{','};   // в первой строке массив из одного элемента
                // пропустить остальные элементы первой строки
                int depth = 0;
                while (true) {
                    byte q = a[d.p];
                    if (q == '"') {
                        d.p = scanString(a, d.p);
                        continue;
                    }
                    if (q == '{' || q == '[') depth++;
                    else if (q == '}') depth--;
                    else if (q == ']' && depth == 0) break;
                    d.p++;
                }
                // слоты нумеруются глобально, поэтому slotCount подшаблона не используется
                t.segs.add(new Rep(elem, sep, countSlot));
                litStart = d.p; // литерал начинается с ']'
                d.p++;          // ']' поглощён литералом
            } else if (c == '{') {
                t.segs.add(new Lit(slice(a, litStart, d.p - litStart)));
                deriveValue(d, t, end, inRep, vpath);
                litStart = d.p;
                continue;
            } else {
                // скалярное значение
                t.segs.add(new Lit(slice(a, litStart, d.p - litStart)));
                t.segs.add(new Val(d.newSlot(vpath, inRep, false)));
                d.p = scanScalar(a, d.p);
                litStart = d.p;
            }
        }
        t.segs.add(new Lit(slice(a, litStart, d.p - litStart)));
    }

    private static void skipWs(byte[] a, Deriver d) {
        while (a[d.p] == ' ' || a[d.p] == '\t') d.p++;
    }

    private static byte[] slice(byte[] a, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(a, off, r, 0, len);
        return r;
    }

    /** Позиция за закрывающей кавычкой строки, начинающейся в {@code p}. */
    public static int scanString(byte[] a, int p) {
        p++; // открывающая кавычка
        while (true) {
            byte c = a[p];
            if (c == '\\') {
                p += 2;
                continue;
            }
            p++;
            if (c == '"') return p;
        }
    }

    /** Позиция за скалярным значением (строка / число / null / true / false). */
    public static int scanScalar(byte[] a, int p) {
        byte c = a[p];
        if (c == '"') return scanString(a, p);
        if (c == 'n') return p + 4;
        if (c == 't') return p + 4;
        if (c == 'f') return p + 5;
        while (true) {
            c = a[p];
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') p++;
            else return p;
        }
    }

    // ------------------------------------------------------------------ разбор

    /**
     * Разобрать строку по шаблону. Возвращает {@code true}, если строка совпала.
     * При {@code false} состояние {@code sink} может быть частично заполнено —
     * вызывающая сторона обязана откатиться на поток исключений.
     */
    public boolean parse(byte[] a, int off, int len, Sink sink) {
        int[] p = {off};
        int end = off + len;
        return parseSegs(segs, a, p, end, sink) && p[0] == end;
    }

    private static boolean parseSegs(List<Seg> segs, byte[] a, int[] p, int end, Sink sink) {
        for (int si = 0; si < segs.size(); si++) {
            Seg s = segs.get(si);
            if (s instanceof Lit lit) {
                byte[] b = lit.b;
                if (p[0] + b.length > end) return false;
                for (int i = 0; i < b.length; i++) {
                    if (a[p[0] + i] != b[i]) return false;
                }
                p[0] += b.length;
            } else if (s instanceof Val v) {
                int vs = p[0];
                if (vs >= end) return false;
                byte c = a[vs];
                int ve;
                int kind;
                if (c == '"') {
                    ve = scanString(a, vs);
                    kind = KIND_STR;
                    sink.scalar(v.slot, kind, a, vs + 1, ve - vs - 2);
                } else if (c == 'n') {
                    ve = vs + 4;
                    if (ve > end || a[vs + 1] != 'u' || a[vs + 2] != 'l' || a[vs + 3] != 'l') return false;
                    sink.scalar(v.slot, KIND_NULL, a, vs, 0);
                } else if ((c >= '0' && c <= '9') || c == '-') {
                    ve = scanScalar(a, vs);
                    sink.scalar(v.slot, KIND_NUM, a, vs, ve - vs);
                } else {
                    return false;
                }
                if (ve > end) return false;
                p[0] = ve;
            } else {
                Rep r = (Rep) s;
                int n = 0;
                if (p[0] < end && a[p[0]] != ']') {
                    while (true) {
                        if (!parseSegs(r.elem.segs, a, p, end, sink)) return false;
                        n++;
                        byte[] sep = r.sep;
                        boolean more = p[0] + sep.length <= end;
                        if (more) {
                            for (int i = 0; i < sep.length; i++) {
                                if (a[p[0] + i] != sep[i]) {
                                    more = false;
                                    break;
                                }
                            }
                        }
                        if (!more) break;
                        p[0] += sep.length;
                    }
                }
                sink.count(r.countSlot, n);
            }
        }
        return true;
    }

    // ------------------------------------------------------------- восстановление

    /** Источник значений при сборке строки. */
    public interface Source {
        /** Дописать значение слота (со кавычками для строк / {@code null} / число) в {@code out}. */
        void emit(int slot, ByteVec out);

        int count(int slot);
    }

    public void write(Source src, ByteVec out) {
        writeSegs(segs, src, out);
    }

    private static void writeSegs(List<Seg> segs, Source src, ByteVec out) {
        for (int si = 0; si < segs.size(); si++) {
            Seg s = segs.get(si);
            if (s instanceof Lit lit) {
                out.add(lit.b);
            } else if (s instanceof Val v) {
                src.emit(v.slot, out);
            } else {
                Rep r = (Rep) s;
                int n = src.count(r.countSlot);
                for (int i = 0; i < n; i++) {
                    if (i > 0) out.add(r.sep);
                    writeSegs(r.elem.segs, src, out);
                }
            }
        }
    }

    // ------------------------------------------------------- (де)сериализация

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Bits.putUV(o, slotCount);
        for (int i = 0; i < slotCount; i++) {
            byte[] nm = names[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Bits.putUV(o, nm.length);
            o.write(nm);
            o.write((repeated[i] ? 1 : 0) | (isCount[i] ? 2 : 0));
            Bits.putUV(o, ownerCount[i] + 1);
        }
        writeSegsTo(segs, o);
        return o.toByteArray();
    }

    private static void writeSegsTo(List<Seg> segs, ByteArrayOutputStream o) throws IOException {
        Bits.putUV(o, segs.size());
        for (Seg s : segs) {
            if (s instanceof Lit lit) {
                o.write(0);
                Bits.putUV(o, lit.b.length);
                o.write(lit.b);
            } else if (s instanceof Val v) {
                o.write(1);
                Bits.putUV(o, v.slot);
            } else {
                Rep r = (Rep) s;
                o.write(2);
                Bits.putUV(o, r.countSlot);
                Bits.putUV(o, r.sep.length);
                o.write(r.sep);
                writeSegsTo(r.elem.segs, o);
            }
        }
    }

    public static Template deserialize(Bits.ByteCursor c) {
        Template t = new Template();
        t.slotCount = (int) c.uv();
        t.repeated = new boolean[t.slotCount];
        t.isCount = new boolean[t.slotCount];
        t.ownerCount = new int[t.slotCount];
        t.names = new String[t.slotCount];
        for (int i = 0; i < t.slotCount; i++) {
            int n = (int) c.uv();
            t.names[i] = new String(c.bytes(n), java.nio.charset.StandardCharsets.UTF_8);
            int f = c.u8();
            t.repeated[i] = (f & 1) != 0;
            t.isCount[i] = (f & 2) != 0;
            t.ownerCount[i] = (int) c.uv() - 1;
        }
        readSegs(c, t.segs);
        return t;
    }

    private static void readSegs(Bits.ByteCursor c, List<Seg> out) {
        int n = (int) c.uv();
        for (int i = 0; i < n; i++) {
            int k = c.u8();
            if (k == 0) {
                int len = (int) c.uv();
                out.add(new Lit(c.bytes(len)));
            } else if (k == 1) {
                out.add(new Val((int) c.uv()));
            } else {
                int cs = (int) c.uv();
                int sl = (int) c.uv();
                byte[] sep = c.bytes(sl);
                Template elem = new Template();
                readSegs(c, elem.segs);
                out.add(new Rep(elem, sep, cs));
            }
        }
    }

    /** Суммарный размер литералов шаблона (для отчёта). */
    public int literalBytes() {
        return literalBytes(segs);
    }

    private static int literalBytes(List<Seg> segs) {
        int s = 0;
        for (Seg g : segs) {
            if (g instanceof Lit l) s += l.b.length;
            else if (g instanceof Rep r) s += r.sep.length + literalBytes(r.elem.segs);
        }
        return s;
    }
}
