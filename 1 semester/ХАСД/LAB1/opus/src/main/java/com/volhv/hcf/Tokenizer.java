package com.volhv.hcf;

/**
 * Разбиение текста на «атомы» — минимальные единицы словаря.
 *
 * <p>Атом — это либо максимальный пробег «словных» символов (ASCII-буквы/цифры и
 * кириллица U+0400–U+04FF), либо максимальный пробег всех остальных символов
 * (пробелы, знаки препинания, кавычки, тире). Разбиение однозначно и обратимо:
 * конкатенация атомов даёт исходные байты. Работа идёт прямо на UTF-8 байтах,
 * без декодирования в String — это заметно быстрее и позволяет обрабатывать
 * значение JSON как есть, вместе с escape-последовательностями.
 */
public final class Tokenizer {

    /** Ограничение длины атома в байтах: защита от вырожденно длинных пробегов. */
    public static final int MAX_ATOM = 48;

    private Tokenizer() {
    }

    /** Длина словного символа в байтах в позиции i, или 0 если символ не словный. */
    public static int wordCharLen(byte[] a, int i, int end) {
        int b = a[i] & 0xFF;
        if ((b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')) return 1;
        if (b >= 0xD0 && b <= 0xD3 && i + 1 < end) {
            int n = a[i + 1] & 0xFF;
            if (n >= 0x80 && n <= 0xBF) return 2;
        }
        return 0;
    }

    /** Конец атома, начинающегося в позиции i. */
    public static int atomEnd(byte[] a, int i, int end) {
        int lim = Math.min(end, i + MAX_ATOM);
        int w = wordCharLen(a, i, end);
        int j = i;
        if (w > 0) {
            int l;
            while (j < lim && (l = wordCharLen(a, j, end)) > 0) {
                if (j + l > lim) break;
                j += l;
            }
        } else {
            while (j < lim && wordCharLen(a, j, end) == 0) j += utf8Len(a, j, end);
        }
        return j == i ? i + 1 : j;
    }

    /** Длина UTF-8 символа по ведущему байту (устойчиво к некорректным данным). */
    public static int utf8Len(byte[] a, int i, int end) {
        int b = a[i] & 0xFF;
        int n = b < 0x80 ? 1 : b < 0xC0 ? 1 : b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
        return Math.min(n, end - i);
    }
}
