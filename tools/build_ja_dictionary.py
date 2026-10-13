#!/usr/bin/env python3
"""Mozc のオープンソース辞書から、予測変換用の辞書ファイルを作る。

出力（app/src/main/assets/ 以下）:
  ja_dict.bin          よみ → 単語（Mozc のコストが小さい順）
  ja_prefix.bin        よみの先頭 1〜PREFIX_MAX 文字 → その続きで最もよく使われる単語
  ja_dict_LICENSE.txt  Mozc 辞書（IPAdic など）のライセンス表記

ファイル形式（どちらの .bin も同じ）:
  "MSD1"                 4 バイト
  count                  int32 ビッグエンディアン
  offsets[count]         int32。データ部の先頭からの各レコードの位置
  data                   レコードの UTF-8 バイト列: よみ \t コスト \t 単語 \t コスト \t 単語 ... \n
レコードはよみの UTF-8 バイト列の順に並んでいるので、アプリ側で二分探索できる。

使い方:
  python3 tools/build_ja_dictionary.py [--mozc-dir DIR] [--out-dir app/src/main/assets]
--mozc-dir を省略すると、固定したコミットの辞書を GitHub からダウンロードする。
"""

import argparse
import heapq
import os
import struct
import sys
import urllib.request
from collections import defaultdict

# 辞書の内容が勝手に変わらないよう、取得するコミットを固定する
MOZC_COMMIT = "25fd28cbbb2eed7094ec66fccd927d179d956277"
MOZC_URL = "https://raw.githubusercontent.com/google/mozc/{commit}/src/data/dictionary_oss/{name}"
FILES = [f"dictionary0{i}.txt" for i in range(10)]

WORDS_PER_READING = 12
PREFIX_MAX = 3
WORDS_PER_PREFIX = 12


def download(dest_dir):
    os.makedirs(dest_dir, exist_ok=True)
    for name in FILES + ["README.txt"]:
        path = os.path.join(dest_dir, name)
        if os.path.exists(path) and os.path.getsize(path) > 0:
            continue
        url = MOZC_URL.format(commit=MOZC_COMMIT, name=name)
        print(f"downloading {url}", file=sys.stderr)
        with urllib.request.urlopen(url) as r, open(path, "wb") as f:
            f.write(r.read())
    return dest_dir


def load_entries(mozc_dir):
    """よみ → {単語: 最小コスト}"""
    entries = defaultdict(dict)
    for name in FILES:
        with open(os.path.join(mozc_dir, name), encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 5:
                    continue
                reading, cost, word = parts[0], int(parts[3]), parts[4]
                if not reading or not word or "\t" in word:
                    continue
                words = entries[reading]
                if word not in words or cost < words[word]:
                    words[word] = cost
    return entries


def write_file(path, records):
    """records: [(reading, [(cost, word), ...])] をよみの UTF-8 順に書く"""
    encoded = []
    for reading, words in records:
        line = reading + "".join(f"\t{cost}\t{word}" for cost, word in words) + "\n"
        encoded.append((reading.encode("utf-8"), line.encode("utf-8")))
    encoded.sort(key=lambda x: x[0])

    offsets = []
    pos = 0
    for _, data in encoded:
        offsets.append(pos)
        pos += len(data)
    with open(path, "wb") as f:
        f.write(b"MSD1")
        f.write(struct.pack(">I", len(encoded)))
        f.write(struct.pack(f">{len(offsets)}I", *offsets))
        for _, data in encoded:
            f.write(data)
    return len(encoded), os.path.getsize(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mozc-dir", help="Mozc の dictionary_oss ディレクトリ（省略時はダウンロード）")
    ap.add_argument("--out-dir", default=os.path.join("app", "src", "main", "assets"))
    ap.add_argument("--cache-dir", default=os.path.join("build", "mozc-dictionary"))
    args = ap.parse_args()

    mozc_dir = args.mozc_dir or download(args.cache_dir)
    entries = load_entries(mozc_dir)
    os.makedirs(args.out_dir, exist_ok=True)

    # よみ → 単語（コストの小さい順）
    main_records = []
    for reading, words in entries.items():
        ranked = sorted((cost, word) for word, cost in words.items())[:WORDS_PER_READING]
        main_records.append((reading, ranked))
    n, size = write_file(os.path.join(args.out_dir, "ja_dict.bin"), main_records)
    print(f"ja_dict.bin: {n} readings, {size / 1e6:.1f} MB", file=sys.stderr)

    # よみの先頭 → 続きの単語（よみそのものと完全一致するものは除く）
    best = defaultdict(list)  # prefix -> heap of (-cost, word)  上位 WORDS_PER_PREFIX 件を保持
    for reading, words in entries.items():
        top = sorted((cost, word) for word, cost in words.items())[:WORDS_PER_PREFIX]
        for k in range(1, min(PREFIX_MAX, len(reading) - 1) + 1):
            heap = best[reading[:k]]
            for cost, word in top:
                item = (-cost, word)
                if len(heap) < WORDS_PER_PREFIX:
                    heapq.heappush(heap, item)
                elif item > heap[0]:
                    heapq.heapreplace(heap, item)
                else:
                    break
    prefix_records = []
    for prefix, heap in best.items():
        ranked = []
        seen = set()
        for neg_cost, word in sorted(heap, key=lambda x: (-x[0], x[1])):
            if word not in seen:
                seen.add(word)
                ranked.append((-neg_cost, word))
        prefix_records.append((prefix, ranked))
    n, size = write_file(os.path.join(args.out_dir, "ja_prefix.bin"), prefix_records)
    print(f"ja_prefix.bin: {n} prefixes, {size / 1e6:.1f} MB", file=sys.stderr)

    with open(os.path.join(mozc_dir, "README.txt"), encoding="utf-8") as src, \
            open(os.path.join(args.out_dir, "ja_dict_LICENSE.txt"), "w", encoding="utf-8") as dst:
        dst.write("予測変換の日本語辞書は Mozc のオープンソース辞書（dictionary_oss）を元にしています。\n")
        dst.write(f"https://github.com/google/mozc/tree/{MOZC_COMMIT}/src/data/dictionary_oss\n\n")
        dst.write(src.read())


if __name__ == "__main__":
    main()
