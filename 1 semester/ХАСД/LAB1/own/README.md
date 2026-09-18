INPUT: dataset.jsonl
OUTPUT: dataset.bin

```out
Rows:              53,410 (raw fallback: 0)
Source size:       529,735,512 bytes
Encoded size:      100,028,288 bytes
Compression ratio: 5.30x (18.9% of source)
Encode time:       16.0 s (30.3 s/GB)
Decode time:       9.9 s (18.6 s/GB)

Encoded size by column:
header+vocabulary        3,493,500 bytes    3.5%
doc_id                      66,579 bytes    0.1%
lang                            17 bytes    0.0%
domain                          77 bytes    0.0%
source_dataset                  70 bytes    0.0%
title                    2,237,414 bytes    2.2%
date                       179,773 bytes    0.2%
full_text               46,492,698 bytes   46.5%
n_sentences                 53,471 bytes    0.1%
sentences               47,504,534 bytes   47.5%
label                           26 bytes    0.0%
source_model                    11 bytes    0.0%
generation_type                 11 bytes    0.0%
seed_doc_id                     11 bytes    0.0%
raw                             11 bytes    0.0%
INTEGRITY: OK, files are identical
```