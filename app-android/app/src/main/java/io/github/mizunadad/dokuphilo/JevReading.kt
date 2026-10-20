package io.github.mizunadad.dokuphilo

import kotlin.random.Random

// Jev の値の読み方（ADR-027 決定1・3、ADR-028 決定4・5）。値と閾値から結果を作り、画面用の文字列にし、判定結果を組み立てる。
// Android と通信の import を持たない（PC 上の単体テストで確かめる）。
// 判定関数（Judge.kt）はそのまま使う。入力文をログに出さない。保存もしない。

/** 煩悩1つ分の Jev の結果。使った閾値を一緒に持つ（あとで閾値を変えても、出ている表示は変わらない） */
data class JevReading(
    val bonno: String,
    /** Jev の値（0.0〜1.0） */
    val value: Double,
    /** 使った閾値（百分率。0.80 なら 80） */
    val thresholdPercent: Int,
    /** 値が閾値に届いたか */
    val met: Boolean,
)

/** 判定の出どころ（ADR-027 決定3）。カードを出すときに画面に出す */
sealed interface JudgementSource {
    /** キーワード判定 */
    data object Keyword : JudgementSource

    /** Jev。値と、使った閾値を持つ */
    data class Jev(val reading: JevReading) : JudgementSource
}

/**
 * Jev の値と閾値から、煩悩ごとの結果を作る。並びは order（既定は JEV_QUESTIONS の記述順）。
 * 値のない煩悩は結果に入れない。閾値のない煩悩と、範囲や刻みに合わない閾値は、その煩悩の既定の閾値を使う。
 */
fun readJevValues(
    values: Map<String, Double>,
    thresholds: Map<String, Int>,
    order: Collection<String> = JEV_QUESTIONS.keys,
): List<JevReading> =
    order.mapNotNull { bonno ->
        val value = values[bonno] ?: return@mapNotNull null
        val percent = normalizeThreshold(bonno, thresholds[bonno] ?: defaultThreshold(bonno))
        JevReading(bonno, value, percent, meetsThreshold(value, percent))
    }

/**
 * 閾値に届いた煩悩のうち、値が最大のものを選ぶ。同じ値なら order（既定は JEV_QUESTIONS の記述順）で先のもの。
 * どれも届かなければ null。
 */
fun pickJevReading(
    readings: List<JevReading>,
    order: Collection<String> = JEV_QUESTIONS.keys,
): JevReading? {
    var best: JevReading? = null
    for (bonno in order) {
        val reading = readings.firstOrNull { it.bonno == bonno && it.met } ?: continue
        if (best == null || reading.value > best.value) best = reading
    }
    return best
}

/**
 * Jev の値を画面用の文字列にする。小数3桁、切り捨て（0.7999 → "0.799"、0.8 → "0.800"、1.0 → "1.000"）。
 * 端末の言語設定で変わらない。
 *
 * 表示は「k/1000.0 が値以下になる最大の整数 k」で決める。閾値と比べるとき（meetsThreshold）と同じく
 * Double どうしの比較だけを使うので、次のことが必ず成り立つ:
 * - 値が閾値ちょうど（t/100.0）なら、formatThreshold(t) の末尾に 0 を付けた文字列と同じになる
 * - 閾値に届かなかった値は、閾値より小さい数として出る。届いた値は、閾値以上の数として出る
 *
 * 値を 1000 倍して切り捨てる書き方や、二進の値をそのまま十進に展開する書き方は、0.7 などでこれを外すので使わない。
 * Jev の値は 0.0〜1.0（parseJevResponse が確かめている）。数でない値と負の値は "-" を返す。
 */
fun formatJevValue(value: Double): String {
    if (value.isNaN() || value.isInfinite() || value < 0.0 || value > 1_000_000.0) return "-"
    // おおよその位置から始めて、Double の比較で合わせる（ずれは高々1なので、すぐ止まる）
    var k = (value * 1000.0).toLong()
    while ((k + 1) / 1000.0 <= value) k++
    while (k > 0 && k / 1000.0 > value) k--
    return "${k / 1000}.${(k % 1000).toString().padStart(3, '0')}"
}

/**
 * Jev が煩悩ありとしたときの判定結果を作る（ADR-027 決定1）。キーワードのときと同じ流れでカードを出す。
 * - bonno: Jev の結果
 * - situation: 元の文（マスクしていない文）で、キーワード判定（detectBest）
 * - カード: pickSages と pickLine（Judge.kt の関数をそのまま使う）
 */
fun buildJevJudgement(
    text: String,
    bonno: String,
    dictionary: Dictionary,
    random: Random = Random.Default,
): Judgement {
    val raw = text.trim()
    val situation = detectBest(raw, dictionary.situations)
    val cards = pickSages(bonno, dictionary.sages, random).map { sageId ->
        SageCard(
            sageId = sageId,
            sage = dictionary.sages.getValue(sageId),
            line = pickLine(sageId, bonno, situation, dictionary.lines, random),
        )
    }
    return Judgement(raw, bonno, situation, cards)
}
