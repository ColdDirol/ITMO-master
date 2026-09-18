package com.volhv;

import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "encode";
        boolean encode = mode.equals("encode");
        Path in = Path.of(args.length > 1 ? args[1] : encode ? "dataset.jsonl" : "dataset.bin");
        Path out = Path.of(args.length > 2 ? args[2] : encode ? "dataset.bin" : "dataset.decoded.jsonl");
        if (encode) Codec.encode(in, out);
        else Codec.decode(in, out);
    }
}
