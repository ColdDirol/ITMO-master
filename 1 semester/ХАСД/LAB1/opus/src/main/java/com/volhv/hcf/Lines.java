package com.volhv.hcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Потоковое чтение строк большого файла без создания объектов.
 * Текущая строка доступна как срез {@code buf[off, off+len)} и включает
 * завершающий {@code '\n'} (если он есть в файле).
 */
public final class Lines implements Closeable {

    private final InputStream in;
    private byte[] b;
    private int start, end;
    private boolean eof;

    public byte[] buf;
    public int off, len;
    public long lineNo;

    public Lines(InputStream in, int cap) {
        this.in = in;
        this.b = new byte[cap];
    }

    public boolean next() throws IOException {
        while (true) {
            for (int i = start; i < end; i++) {
                if (b[i] == '\n') {
                    buf = b;
                    off = start;
                    len = i - start + 1;
                    start = i + 1;
                    lineNo++;
                    return true;
                }
            }
            if (eof) {
                if (start < end) {
                    buf = b;
                    off = start;
                    len = end - start;
                    start = end;
                    lineNo++;
                    return true;
                }
                return false;
            }
            fill();
        }
    }

    private void fill() throws IOException {
        if (start > 0) {
            System.arraycopy(b, start, b, 0, end - start);
            end -= start;
            start = 0;
        }
        if (end == b.length) b = Arrays.copyOf(b, b.length * 2);
        int r = in.read(b, end, b.length - end);
        if (r < 0) eof = true;
        else end += r;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
