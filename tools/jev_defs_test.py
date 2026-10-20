#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Jev オフライン実験: bluff・anxious の定義文の候補を比べる（ADR-027 決定2）

目的: bluff・anxious の定義文の候補（a / b / EGOGUARD 版 e）と flex を Jev（noul）にかけ、
      煩悩を期待する文と期待しない文で値がどう分かれるかを見る。
      定義文の正はこのファイル（ADR-028 決定2。アプリは flex・bluff_b・anxious_a を使う）。flex は tools/jev_offline_test.py と同じ文。
      この実験では閾値を決めない（0.5 と 0.8 の件数は参考として出すだけ）。
      WSL から手動で実行する実験用。依存パッケージなし（標準ライブラリのみ）。

使い方:
    export TYPESAFE_API_KEY="apikey_..."
    python3 tools/jev_defs_test.py          # 同時: 1文につき6件の定義をまとめて送る（38回）
    python3 tools/jev_defs_test.py --both   # 同時に加えて、定義を1件ずつ送る（単独。228回）

守ること:
    - APIキーは環境変数 TYPESAFE_API_KEY からだけ読む。ファイルに書かない・画面に出さない
    - 送るのは下の SENTENCES の文だけ
    - 結果はターミナルに出すだけ。ファイルには書かない
"""

import json
import os
import socket
import statistics
import sys
import unicodedata
import urllib.error
import urllib.request

# (id, 文, 期待, 型)  期待: "flex" / "bluff" / "anxious" / None = 煩悩なし  型: "実演" / "自己申告" / "" = 型なし
#   M1〜N6: tools/jev_offline_test.py の SENTENCES の12文
SENTENCES = [
    ("M1", "今日若手に説教したら、先生って呼ばれちゃってさ", "flex", ""),
    ("M2", "生成AIでコーパスを繋いでつくるフレームワークを提唱したら誰も理解してもらえなかったよ", "flex", ""),
    ("M3", "アファメーションを自動配信するアプリ作ったんだけどさ、やっぱり僕はなんでもできちゃうよ", "flex", ""),
    ("M4", "社長くらいやったら僕でもできるけど。", "flex", ""),
    ("M5", "どうして僕にはこんなに簡単なことなのに、できないのかな", "flex", ""),
    ("M6", "取引先の昇進の推薦者になってって言われちゃったよ", "flex", ""),
    ("N1", "上司に呼ばれちゃって", None, ""),
    ("N2", "説明が下手で、誰も理解してもらえなかった", None, ""),
    ("N3", "このアプリならなんでもできちゃう", None, ""),
    ("N4", "僕でもできる簡単レシピ", None, ""),
    ("N5", "こんなに簡単なことができないなんて", None, ""),
    ("N6", "文句を言われちゃった", None, ""),
    ("B1", "ああ、それね、要はDXでしょ。常識だよ", "bluff", "実演"),
    ("B2", "量子コンピュータって結局、全部の計算を同時にやるだけの話だからね", "bluff", "実演"),
    ("B3", "その本は読んでないけど、だいたい言いたいことは分かるよ", "bluff", "実演"),
    ("B4", "AIのことなら大体わかってるから、細かい説明はいいよ", "bluff", "実演"),
    ("B5", "あの理論ね、はいはい、有名なやつでしょ", "bluff", "実演"),
    ("B6", "分かってないのに、うんうんってうなずいちゃった", "bluff", "自己申告"),
    ("B7", "聞いたことない単語だったけど、知ってる体で話を進めた", "bluff", "自己申告"),
    ("NB1", "その分野は詳しくないので、教えてください", None, ""),
    ("NB2", "量子コンピュータは量子ビットの重ね合わせを使うと、本で読みました", None, ""),
    ("NB3", "たしか来週だったと思うけど、あとで確認するね", None, ""),
    ("NB4", "DXの定義は、資料に書いてあるとおりに説明します", None, ""),
    ("NB5", "それ、聞いたことないな", None, ""),
    ("NB6", "要は、締め切りは金曜ってことだよね", None, ""),
    ("A1", "いえいえ、私はなんでも大丈夫です。みんなに合わせます", "anxious", "実演"),
    ("A2", "あ、うん、僕もそう思ってた。ほんとそのとおりだよね", "anxious", "実演"),
    ("A3", "すみません、私なんかが意見してしまって。忘れてください", "anxious", "実演"),
    ("A4", "本当は行きたくないけど、まあ、行くよ。大丈夫大丈夫", "anxious", "実演"),
    ("A5", "のうと言えない性格なんだよね", "anxious", "自己申告"),
    ("A6", "違うと思ったけど、波風立てたくなくて黙ってた", "anxious", "自己申告"),
    ("A7", "頼まれると、つい引き受けちゃうんだよね", "anxious", "自己申告"),
    ("NA1", "その案に賛成です。コストが一番低いので", None, ""),
    ("NA2", "申し訳ありませんが、その日は都合がつきません", None, ""),
    ("NA3", "ありがとうございます、助かりました", None, ""),
    ("NA4", "私はどちらでも構いませんが、あえて言えば前者です", None, ""),
    ("NA5", "すみません、ちょっと通ります", None, ""),
    ("NA6", "その意見には反対です", None, ""),
]

ENDPOINT = "https://api.typesafe.ai/v1/systemone"
MODEL = "jev-latest"
TIMEOUT_SEC = 30

# 定義の対象の煩悩は、キーの「_」より前（flex / bluff / anxious）
#   flex: tools/jev_offline_test.py の QUESTIONS の flex と同じ文
#   bluff_e: EGOGUARD の bluff のまま（比較の基準）
QUESTIONS = {
    "flex": {
        "type": "noul",
        "instructions": "文脈に不要な自慢話やおのろけ、自己顕示が含まれるか / Unsolicited bragging or self-display",
        "criteria": {
            "true": "文脈に不要な自慢、自己顕示、人脈や身内の誇示が含まれる",
            "false": "自慢や自己顕示は含まれない",
        },
    },
    "bluff_a": {
        "type": "noul",
        "instructions": "話し手が、よく知らないことを知っているかのように語っているか。または、知ったかぶりをしたと自分で述べているか / Pretending to know",
        "criteria": {
            "true": "中身を説明しないまま流行語や専門用語で分かったふうに断定している、受け売りを自分の知識のように語っている、または知らないのに知っているふりをしたと述べている",
            "false": "知ったかぶりは含まれない。中身や根拠を説明している、知らないことを知らないと述べている、伝聞を伝聞として話している、またはただの雑談や事実の報告である",
        },
    },
    "bluff_b": {
        "type": "noul",
        "instructions": "知ったかぶりや、分かったふりが含まれるか / Pretending to know",
        "criteria": {
            "true": "知らないことを知っているかのように語っている、または分かったふりをしている",
            "false": "知ったかぶりや分かったふりは含まれない",
        },
    },
    "bluff_e": {
        "type": "noul",
        "instructions": "曖昧な言葉でごまかしたり知ったかぶりをしている兆候があるか",
        "criteria": {
            "true": "根拠を示さずバズワードや曖昧語で断定している、知ったかぶりの兆候がある",
            "false": "根拠を示している、または知らないことを知らないと述べている",
        },
    },
    "anxious_a": {
        "type": "noul",
        "instructions": "話し手が、嫌われることや対立を恐れて、本心を抑えて相手に合わせているか。または、そうしたと自分で述べているか / People-pleasing",
        "criteria": {
            "true": "嫌われたくない・波風を立てたくないために、本音を言わずに同調している、断れずに引き受けている、必要以上に謝ったりへりくだったりしている、またはそうしたと述べている",
            "false": "過剰な迎合は含まれない。理由があって賛成している、通常の礼儀や敬語である、自分の意見や断りを伝えている、またはただの雑談や事実の報告である",
        },
    },
    "anxious_b": {
        "type": "noul",
        "instructions": "過剰な迎合や、本音を抑えた同調が含まれるか / People-pleasing",
        "criteria": {
            "true": "嫌われるのを恐れて相手に合わせている、または本音を言えずにいる",
            "false": "過剰な迎合は含まれない",
        },
    },
}

TYPES = ["実演", "自己申告", ""]  # 集計の順。"" は型なし
REF_LEVELS = (0.5, 0.8)           # 参考の件数を出すだけ。閾値ではない

USAGE = "使い方: python3 tools/jev_defs_test.py [--both]"


def fail(msg, api_key=""):
    """エラーを表示して終了する。万一キー文字列が含まれていたら伏せる"""
    if api_key:
        msg = msg.replace(api_key, "[REDACTED]")
    print(msg, file=sys.stderr)
    sys.exit(1)


def call(api_key, state, questions):
    """questions の各キーの noul 値を dict で返す。エラー・タイムアウト時は表示して終了する"""
    payload = {"model": MODEL, "state": state, "questions": questions}
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

    out = {}
    try:
        answers = json.loads(body)["answers"]
        for key in questions:
            v = answers[key]["noul"]
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not 0.0 <= v <= 1.0:
                raise ValueError
            out[key] = float(v)
    except (ValueError, KeyError, TypeError):
        fail("想定外のレスポンス:\n{}".format(body), api_key)
    return out


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


def target(key):
    return key.split("_")[0]


def print_table(title, results):
    """results: {id: {キー: 値}}"""
    print("■ {}\n".format(title))
    cols = [("#", 4), ("型", 8), ("期待", 8)] + [(k, 9) for k in QUESTIONS]
    print("  ".join(fit(n, w) for n, w in cols) + "  文")
    print("  ".join("-" * w for _, w in cols) + "  " + "-" * 40)
    for sid, text, expected, kind in SENTENCES:
        row = [sid, kind or "-", expected or "なし"] + ["{:.3f}".format(results[sid][k]) for k in QUESTIONS]
        print("  ".join(fit(c, w) for c, (_, w) in zip(row, cols)) + "  " + text)
    print()


def print_summary(title, results):
    print("■ {}: 定義ごとの集計\n".format(title))
    for key in QUESTIONS:
        bonno = target(key)
        print("[{}]（対象: {}）".format(key, bonno))
        print("  期待あり（期待 = {}）".format(bonno))
        for kind in TYPES:
            vs = [results[s[0]][key] for s in SENTENCES if s[2] == bonno and s[3] == kind]
            label = fit(kind or "型なし", 8)
            if vs:
                print("    {}  件数 {:>2}  最小 {:.3f}  中央 {:.3f}  最大 {:.3f}".format(
                    label, len(vs), min(vs), statistics.median(vs), max(vs)))
            else:
                print("    {}  件数  0  -".format(label))
        others = [(s[0], results[s[0]][key]) for s in SENTENCES if s[2] != bonno]
        top_id, top_v = max(others, key=lambda x: x[1])
        counts = "  ".join("{} 以上 {:>2}".format(lv, sum(1 for _, v in others if v >= lv)) for lv in REF_LEVELS)
        print("  期待なし（期待 ≠ {}）".format(bonno))
        print("    件数 {:>2}  最大 {:.3f}（{}）  {}".format(len(others), top_v, top_id, counts))
        print()


def print_diff(together, alone):
    print("■ 同時と単独の差（|同時 − 単独|）\n")
    cols = [("定義", 10), ("最大", 7), ("平均", 7), ("最大の文", 8)]
    print("  ".join(fit(n, w) for n, w in cols).rstrip())
    print("  ".join("-" * w for _, w in cols))
    for key in QUESTIONS:
        diffs = [(s[0], abs(together[s[0]][key] - alone[s[0]][key])) for s in SENTENCES]
        top_id, top_d = max(diffs, key=lambda x: x[1])
        mean = sum(d for _, d in diffs) / len(diffs)
        row = [key, "{:.3f}".format(top_d), "{:.3f}".format(mean), top_id]
        print("  ".join(fit(c, w) for c, (_, w) in zip(row, cols)).rstrip())
    print()


def main():
    args = sys.argv[1:]
    if args not in ([], ["--both"]):
        fail(USAGE)
    both = args == ["--both"]

    api_key = os.environ.get("TYPESAFE_API_KEY", "").strip()
    if not api_key:
        fail("環境変数 TYPESAFE_API_KEY が設定されていません。API は呼ばずに終了します。\n"
             '  export TYPESAFE_API_KEY="apikey_..."')

    n = len(SENTENCES)
    print("Jev 定義文の比較（noul）/ {} 文 / 定義 {} 件 / {}\n".format(
        n, len(QUESTIONS), "同時 + 単独" if both else "同時"))

    together = {}
    for i, (sid, text, _, _) in enumerate(SENTENCES, 1):
        print("\r同時 {}/{}".format(i, n), end="", file=sys.stderr, flush=True)
        together[sid] = call(api_key, text, QUESTIONS)
    print(file=sys.stderr)

    alone = None
    if both:
        alone = {}
        total = n * len(QUESTIONS)
        done = 0
        for sid, text, _, _ in SENTENCES:
            alone[sid] = {}
            for key in QUESTIONS:
                done += 1
                print("\r単独 {}/{}".format(done, total), end="", file=sys.stderr, flush=True)
                alone[sid].update(call(api_key, text, {key: QUESTIONS[key]}))
        print(file=sys.stderr)

    print_table("同時", together)
    if both:
        print_table("単独", alone)
    print_summary("同時", together)
    if both:
        print_summary("単独", alone)
        print_diff(together, alone)
    print("0.5 と 0.8 は参考。この実験では閾値を決めない")


if __name__ == "__main__":
    main()
