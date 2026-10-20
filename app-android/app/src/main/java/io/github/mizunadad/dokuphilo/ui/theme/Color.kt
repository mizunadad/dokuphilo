package io.github.mizunadad.dokuphilo.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ---- 煩悩の色（ADR-028 決定14〜16）----
//
// 煩悩ごとに色を決める。flex は赤紫、bluff は黄橙（どちらも EGOGUARD と同じ系統）、anxious は水色。
// 使う場所: 煩悩のタグ、カードの左の帯、Jev の値の短い表示（閾値に届いたもの）。
//
// - 色は固定（端末の壁紙の色に合わせない）。Theme.kt の色とは別に持つ
// - 明るい画面用と暗い画面用の2組。それぞれ「塗りの色」と「その上に載せる文字の色」を組で決める
//   （文字のコントラストは、どの組も 4.5:1 以上）
// - 色だけで伝えない。使う側は、必ず文字も出す
// - 誤りの色（赤。colorScheme.error）は煩悩に使わない
// 色を変えたら、文字のコントラストを確かめ直すこと。

/** 塗りの色と、その上に載せる文字の色の組 */
data class BonnoColors(
    val container: Color,
    val onContainer: Color,
)

// 明るい画面用。白い地の上でも帯やタグが見えるよう、bluff と anxious は EGOGUARD の色より少し濃くしている
private val FlexLight = BonnoColors(container = Color(0xFFD946EF), onContainer = Color(0xFF2B0033))
private val BluffLight = BonnoColors(container = Color(0xFFD97706), onContainer = Color(0xFF2A1800))
private val AnxiousLight = BonnoColors(container = Color(0xFF0EA5E9), onContainer = Color(0xFF00212E))
private val NeutralLight = BonnoColors(container = Color(0xFF9CA3AF), onContainer = Color(0xFF111827))

// 暗い画面用。flex と bluff は EGOGUARD の flex・bluff と同じ色
private val FlexDark = BonnoColors(container = Color(0xFFD946EF), onContainer = Color(0xFF2B0033))
private val BluffDark = BonnoColors(container = Color(0xFFF59E0B), onContainer = Color(0xFF2A1800))
private val AnxiousDark = BonnoColors(container = Color(0xFF38BDF8), onContainer = Color(0xFF00212E))
private val NeutralDark = BonnoColors(container = Color(0xFF9CA3AF), onContainer = Color(0xFF111827))

/**
 * 煩悩の色。dark は、暗い画面かどうか（isSystemInDarkTheme()）。
 * 表にない煩悩のキーは、中立の色（灰色）にする（落とさない）。
 */
fun bonnoColors(bonno: String, dark: Boolean): BonnoColors = when (bonno) {
    "flex" -> if (dark) FlexDark else FlexLight
    "bluff" -> if (dark) BluffDark else BluffLight
    "anxious" -> if (dark) AnxiousDark else AnxiousLight
    else -> if (dark) NeutralDark else NeutralLight
}
