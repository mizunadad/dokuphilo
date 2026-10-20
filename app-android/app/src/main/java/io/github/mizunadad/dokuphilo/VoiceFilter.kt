package io.github.mizunadad.dokuphilo

// 音声の歯止め（ADR-024 決定3）。音声の確定結果だけに使う。テキスト入力とテストボタンには使わない。
// Android の import を持たない（PC 上の単体テストで確かめる）。
// 発話の文をログに出さない。保存もしない。

/** 音声の確定結果を判定に回す最小の文字数 */
const val MIN_VOICE_CHARS = 4

/**
 * 音声の確定結果を、判定に回すかどうかを決める。
 *
 * - trim して4文字未満なら null（判定しない）
 * - 直前に受け付けた文（lastAccepted）と同じなら null（判定しない）
 * - それ以外は trim した文を返す
 *
 * 文字数は index.html（Array.from(t).length）と同じ数え方にする（サロゲートペアの字も1文字）。
 */
fun acceptVoiceText(raw: String, lastAccepted: String?): String? {
    val text = raw.trim()
    if (text.codePointCount(0, text.length) < MIN_VOICE_CHARS) return null
    if (text == lastAccepted) return null
    return text
}
