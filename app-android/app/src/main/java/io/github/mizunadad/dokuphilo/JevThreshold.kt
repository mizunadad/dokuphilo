package io.github.mizunadad.dokuphilo

// Jev の閾値（ADR-027 決定3、ADR-028 決定4）。アプリ上で変えられる。煩悩ごとに持つ。
// Android の import を持たない（PC 上の単体テストで確かめる）。
//
// 閾値は整数の百分率で持つ（0.80 を 80）。小数のまま足し引きすると、0.05 刻みがずれるため。
// 範囲（0.40〜0.90）と刻み（0.05）は全煩悩で同じ。既定だけを煩悩ごとに持つ。

/** 最小（0.40）。全煩悩で同じ。flex は 0.50 より下で自慢と非自慢の順位が入れ替わった（ADR-027）が、範囲は分けない */
const val JEV_THRESHOLD_MIN = 40

/** 最大（0.90） */
const val JEV_THRESHOLD_MAX = 90

/** 刻み（0.05） */
const val JEV_THRESHOLD_STEP = 5

/**
 * 煩悩ごとの既定（flex 0.80、bluff 0.55、anxious 0.55）。表はここだけに置く。
 * 0.55 は暫定で、使いながら貯めたメモで見直す（ADR-028 決定4）。
 */
val JEV_THRESHOLD_DEFAULTS: Map<String, Int> = mapOf(
    "flex" to 80,
    "bluff" to 55,
    "anxious" to 55,
)

/** 表にない煩悩の既定（0.80） */
private const val JEV_THRESHOLD_DEFAULT_FOR_UNKNOWN = 80

/** その煩悩の既定の閾値。表にない煩悩は 80 */
fun defaultThreshold(bonno: String): Int =
    JEV_THRESHOLD_DEFAULTS[bonno] ?: JEV_THRESHOLD_DEFAULT_FOR_UNKNOWN

/** 40〜90 で 5 の倍数ならそのまま。それ以外は、その煩悩の既定 */
fun normalizeThreshold(bonno: String, percent: Int): Int =
    if (percent in JEV_THRESHOLD_MIN..JEV_THRESHOLD_MAX && percent % JEV_THRESHOLD_STEP == 0) {
        percent
    } else {
        defaultThreshold(bonno)
    }

/** 1刻み上げる。最大では動かない。範囲の外や刻みに合わない値は、先にその煩悩の既定に直す */
fun raiseThreshold(bonno: String, percent: Int): Int {
    val current = normalizeThreshold(bonno, percent)
    return if (current >= JEV_THRESHOLD_MAX) current else current + JEV_THRESHOLD_STEP
}

/** 1刻み下げる。最小では動かない。範囲の外や刻みに合わない値は、先にその煩悩の既定に直す */
fun lowerThreshold(bonno: String, percent: Int): Int {
    val current = normalizeThreshold(bonno, percent)
    return if (current <= JEV_THRESHOLD_MIN) current else current - JEV_THRESHOLD_STEP
}

/** まだ上げられるか（「＋」ボタンを押せるか） */
fun canRaiseThreshold(bonno: String, percent: Int): Boolean =
    normalizeThreshold(bonno, percent) < JEV_THRESHOLD_MAX

/** まだ下げられるか（「−」ボタンを押せるか） */
fun canLowerThreshold(bonno: String, percent: Int): Boolean =
    normalizeThreshold(bonno, percent) > JEV_THRESHOLD_MIN

/** Jev の値が閾値以上なら、煩悩ありとする */
fun meetsThreshold(noul: Double, percent: Int): Boolean = noul >= percent / 100.0

/** 画面用の文字列（80 → "0.80"）。端末の言語設定で小数点が変わらないよう、自分で組み立てる */
fun formatThreshold(percent: Int): String =
    "${percent / 100}.${(percent % 100).toString().padStart(2, '0')}"
