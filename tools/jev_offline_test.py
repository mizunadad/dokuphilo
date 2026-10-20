#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Jev オフライン実験: 判定の取りこぼし集を文脈判定できるか（ADR-016）

目的: docs/ISSUES.md「判定の取りこぼし集」の12文を Jev の flex（noul）で判定し、
      キーワード判定では区別できなかった自慢と、自虐・愚痴・呼び出しを分けられるかを見る。
      WSL から手動で実行する実験用。index.html からは通信しない（ADR-016）。
      依存パッケージなし（標準ライブラリのみ）。

使い方:
    export TYPESAFE_API_KEY="apikey_..."
    python3 tools/jev_offline_test.py

守ること:
    - APIキーは環境変数 TYPESAFE_API_KEY からだけ読む。ファイルに書かない・画面に出さない
    - 送るのは下の SENTENCES の12文だけ
    - 結果はターミナルに出すだけ。ファイルには書かない
"""

import json
import os
import socket
import sys
import unicodedata
import urllib.error
import urllib.request

ENDPOINT = "https://api.typesafe.ai/v1/systemone"
MODEL = "jev-latest"
TIMEOUT_SEC = 30
THRESHOLD = 0.8  # EGOGUARD の CONFIG.NOUL_THRESHOLD と同じ
BORDER = 0.05    # 閾値 ± この幅は「境界」として印を付ける（実行ごとに判定が揺れうる）

# EGOGUARD index.html の METRICS の flex 定義を写したもの（実行時に EGOGUARD は読まない）
QUESTIONS = {
    "flex": {
        "type": "noul",
        "instructions": "文脈に不要な自慢話やおのろけ、自己顕示が含まれるか / Unsolicited bragging or self-display",
        "criteria": {
            "true": "文脈に不要な自慢、自己顕示、人脈や身内の誇示が含まれる",
            "false": "自慢や自己顕示は含まれない",
        },
    },
}

# docs/ISSUES.md「判定の取りこぼし集」から（期待: "flex" / None = 煩悩なし）
#   M1〜M6: 表の「文」
#   N1〜N6: 表の「キーワード化した場合の誤検知の例」の矢印の右側（括弧内の注記は送らない）
SENTENCES = [
    ("M1", "今日若手に説教したら、先生って呼ばれちゃってさ", "flex"),
    ("M2", "生成AIでコーパスを繋いでつくるフレームワークを提唱したら誰も理解してもらえなかったよ", "flex"),
    ("M3", "アファメーションを自動配信するアプリ作ったんだけどさ、やっぱり僕はなんでもできちゃうよ", "flex"),
    ("M4", "社長くらいやったら僕でもできるけど。", "flex"),
    ("M5", "どうして僕にはこんなに簡単なことなのに、できないのかな", "flex"),
    ("M6", "取引先の昇進の推薦者になってって言われちゃったよ", "flex"),
    ("N1", "上司に呼ばれちゃって", None),
    ("N2", "説明が下手で、誰も理解してもらえなかった", None),
    ("N3", "このアプリならなんでもできちゃう", None),
    ("N4", "僕でもできる簡単レシピ", None),
    ("N5", "こんなに簡単なことができないなんて", None),
    ("N6", "文句を言われちゃった", None),
]


def fail(msg, api_key=""):
    """エラーを表示して終了する。万一キー文字列が含まれていたら伏せる"""
    if api_key:
        msg = msg.replace(api_key, "[REDACTED]")
    print(msg, file=sys.stderr)
    sys.exit(1)


def call(api_key, state):
    """flex の noul 値を返す。エラー・タイムアウト時は表示して終了する"""
    payload = {"model": MODEL, "state": state, "questions": QUESTIONS}
    req = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8",
                 "Authorization": "Bearer " + api_key},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as res:
            body = res.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        fail("API エラー: HTTP {}\n{}".format(e.code, e.read().decode("utf-8", errors="replace")), api_key)
    except (socket.timeout, TimeoutError):
        fail("タイムアウト: {} 秒以内に応答がありませんでした".format(TIMEOUT_SEC))
    except urllib.error.URLError as e:
        if isinstance(e.reason, (socket.timeout, TimeoutError)):
            fail("タイムアウト: {} 秒以内に応答がありませんでした".format(TIMEOUT_SEC))
        fail("通信エラー: {}".format(e.reason), api_key)

    try:
        return float(json.loads(body)["answers"]["flex"]["noul"])
    except (ValueError, KeyError, TypeError):
        fail("想定外のレスポンス:\n{}".format(body), api_key)


def width(s):
    return sum(2 if unicodedata.east_asian_width(c) in ("W", "F") else 1 for c in s)


def fit(s, w):
    """表示幅 w に切り詰め（超える分は …）、右を空白で埋める"""
    if width(s) > w:
        out = ""
        for c in s:
            if width(out + c) > w - 1:
                break
            out += c
        s = out + "…"
    return s + " " * (w - width(s))


def main():
    api_key = os.environ.get("TYPESAFE_API_KEY", "").strip()
    if not api_key:
        fail("環境変数 TYPESAFE_API_KEY が設定されていません。API は呼ばずに終了します。\n"
             '  export TYPESAFE_API_KEY="apikey_..."')

    print("Jev flex（noul）/ 閾値 {} / {} 文\n".format(THRESHOLD, len(SENTENCES)))
    cols = [("#", 3), ("文", 40), ("期待", 8), ("flex", 6), ("判定", 8), ("一致", 7)]
    print("  ".join(fit(n, w) for n, w in cols))
    print("  ".join("-" * w for _, w in cols))

    caught = false_pos = 0
    for sid, text, expected in SENTENCES:
        v = call(api_key, text)
        got = "flex" if v >= THRESHOLD else None
        ok = got == expected
        if expected == "flex" and got == "flex":
            caught += 1
        if expected is None and got == "flex":
            false_pos += 1
        mark = "○" if ok else "×"
        if abs(v - THRESHOLD) <= BORDER:
            mark += " 境界"
        row = [sid, text, expected or "なし", "{:.3f}".format(v), got or "なし", mark]
        print("  ".join(fit(c, w) for c, (_, w) in zip(row, cols)).rstrip())

    n_flex = sum(1 for s in SENTENCES if s[2] == "flex")
    n_none = len(SENTENCES) - n_flex
    print("\n拾えた自慢:   {} / {}".format(caught, n_flex))
    print("誤って反応:   {} / {}".format(false_pos, n_none))
    print("（境界 = 閾値 ±{} 以内。実行ごとに判定が変わりうる）".format(BORDER))


if __name__ == "__main__":
    main()
