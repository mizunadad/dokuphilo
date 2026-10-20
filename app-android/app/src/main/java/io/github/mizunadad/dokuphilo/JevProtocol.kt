package io.github.mizunadad.dokuphilo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Jev（Typesafe 社の API）の要求と応答を扱う関数（ADR-027・ADR-028）。
// このファイルは通信しない。文字列を組み立てる・読む・分類するだけ。送受信は JevClient.kt がここの関数を使って行う。
// Android と通信の部品の import を持たない（PC 上の単体テストで確かめる）。JSON は kotlinx-serialization-json だけで扱う。
// 送る文・返事・キーをログに出さない。

/** 送り先。通信してよいのはここだけ（ADR-027 決定7）。使うのは JevClient.kt だけ */
const val JEV_ENDPOINT = "https://api.typesafe.ai/v1/systemone"

const val JEV_MODEL = "jev-latest"

/** Jev に尋ねる1件分の定義。型は noul（0.0〜1.0 の値が返る） */
data class JevQuestion(
    val instructions: String,
    val criteriaTrue: String,
    val criteriaFalse: String,
)

/**
 * Jev で判定する煩悩の定義（煩悩のキー → 定義）。flex・bluff・anxious の3件（ADR-028 決定1）。
 * 3件を1回の要求で送る。記述順（flex → bluff → anxious）は、値が同じときの優先順にもなる（ADR-028 決定5）。
 *
 * 定義文は tools/jev_defs_test.py の QUESTIONS が正（ADR-028 決定2）。1文字も変えないこと（オフライン実験と条件をそろえる）。
 * - flex は QUESTIONS の flex、bluff は bluff_b、anxious は anxious_a の instructions・true・false
 * - Jev に送るキーは、煩悩のキー（flex・bluff・anxious）。bluff_b・anxious_a にはしない（ADR-028 決定3）
 * JevProtocolTest が ref/jev_defs_test.py と突き合わせる。tools/jev_offline_test.py は flex の実験の記録で、突き合わせ先ではない。
 */
val JEV_QUESTIONS: Map<String, JevQuestion> = linkedMapOf(
    // tools/jev_defs_test.py の flex
    "flex" to JevQuestion(
        instructions = "文脈に不要な自慢話やおのろけ、自己顕示が含まれるか / Unsolicited bragging or self-display",
        criteriaTrue = "文脈に不要な自慢、自己顕示、人脈や身内の誇示が含まれる",
        criteriaFalse = "自慢や自己顕示は含まれない",
    ),
    // tools/jev_defs_test.py の bluff_b
    "bluff" to JevQuestion(
        instructions = "知ったかぶりや、分かったふりが含まれるか / Pretending to know",
        criteriaTrue = "知らないことを知っているかのように語っている、または分かったふりをしている",
        criteriaFalse = "知ったかぶりや分かったふりは含まれない",
    ),
    // tools/jev_defs_test.py の anxious_a
    "anxious" to JevQuestion(
        instructions = "話し手が、嫌われることや対立を恐れて、本心を抑えて相手に合わせているか。または、そうしたと自分で述べているか / People-pleasing",
        criteriaTrue = "嫌われたくない・波風を立てたくないために、本音を言わずに同調している、断れずに引き受けている、必要以上に謝ったりへりくだったりしている、またはそうしたと述べている",
        criteriaFalse = "過剰な迎合は含まれない。理由があって賛成している、通常の礼儀や敬語である、自分の意見や断りを伝えている、またはただの雑談や事実の報告である",
    ),
)

/**
 * Jev に送る要求の本文（JSON 文字列）を作る。
 * 形: { model, state, questions: { <煩悩のキー>: { type: "noul", instructions, criteria: { true, false } } } }
 *
 * @param sanitized 必ず Sanitizer.sanitize() を通した文を渡すこと（ADR-027 決定5）
 */
fun buildJevRequestBody(sanitized: String): String =
    buildJsonObject {
        put("model", JEV_MODEL)
        put("state", sanitized)
        putJsonObject("questions") {
            for ((bonno, question) in JEV_QUESTIONS) {
                putJsonObject(bonno) {
                    put("type", "noul")
                    put("instructions", question.instructions)
                    putJsonObject("criteria") {
                        put("true", question.criteriaTrue)
                        put("false", question.criteriaFalse)
                    }
                }
            }
        }
    }.toString()

/** Jev の応答（成功時の本文）を読んだ結果 */
sealed interface JevParseResult {
    /** 値が読めた。煩悩のキー → 値（0.0〜1.0） */
    data class Values(val values: Map<String, Double>) : JevParseResult

    /** 想定外の応答。応答の本文は持たない（画面やログに流れ出さないようにするため） */
    data object Unexpected : JevParseResult
}

/**
 * Jev の応答の本文を読む。例外を外に出さない。
 *
 * 定義にある煩悩（JEV_QUESTIONS）のすべてについて、answers.<キー>.noul が 0.0〜1.0 の数値なら「値が読めた」。
 * JSON でない、answers がない、キーがない、noul が数値でない、範囲の外は「想定外」。
 * type の有無は見ない。知らないキーは無視する。
 */
fun parseJevResponse(body: String): JevParseResult {
    try {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return JevParseResult.Unexpected
        val answers = root["answers"] as? JsonObject ?: return JevParseResult.Unexpected
        val values = LinkedHashMap<String, Double>()
        for (bonno in JEV_QUESTIONS.keys) {
            values[bonno] = readNoul(answers[bonno] as? JsonObject) ?: return JevParseResult.Unexpected
        }
        return JevParseResult.Values(values)
    } catch (e: Exception) {
        return JevParseResult.Unexpected
    }
}

/** noul が JSON の数値で、0.0〜1.0 ならその値。それ以外（文字列、null、真偽値、範囲の外、NaN）は null */
private fun readNoul(answer: JsonObject?): Double? {
    val noul = answer?.get("noul") as? JsonPrimitive ?: return null
    if (noul.isString) return null
    val value = noul.doubleOrNull ?: return null
    return if (value in 0.0..1.0) value else null
}

/** Jev が成功以外の応答を返したときの分類（ADR-027 決定8） */
sealed interface JevFailure {
    /** キーの誤り（401・403）。キーを入れ直すかアプリを再起動するまで送らない */
    data object BadKey : JevFailure

    /** 残高切れ（402、または 4xx の応答の本文から判断）。キーを入れ直すかアプリを再起動するまで送らない */
    data object NoCredit : JevFailure

    /** その他。表示して、次の入力でまた試す */
    data class Other(val code: Int) : JevFailure
}

/** 残高切れを示す本文のヒント語（402 が返らないときの保険）。ref/egoguard/JevClient.kt の CREDIT_EXHAUSTED_HINT と同じ */
private val CREDIT_EXHAUSTED_HINT =
    Regex("insufficient|credit|balance|payment required|out of funds|quota exceeded", RegexOption.IGNORE_CASE)

/**
 * 成功以外の応答を分類する。上から順に見る。
 * - 401・403 → キーの誤り
 * - 402 → 残高切れ
 * - 400〜499 で、本文が CREDIT_EXHAUSTED_HINT に当たる → 残高切れ
 * - それ以外 → その他（コードつき）
 *
 * 本文で残高切れとするのは 4xx のときだけ。ヒント語は広く、"load balancer" の balance などにも当たるので、
 * 5xx のエラーページで送信が止まらないようにする。
 * 本文は分類に使うだけで、結果には持たせない。
 */
fun classifyJevFailure(code: Int, body: String): JevFailure = when {
    code == 401 || code == 403 -> JevFailure.BadKey
    code == 402 -> JevFailure.NoCredit
    code in 400..499 && CREDIT_EXHAUSTED_HINT.containsMatchIn(body) -> JevFailure.NoCredit
    else -> JevFailure.Other(code)
}

/** Jev に1回送った結果（JevClient.kt が返す）。応答の本文と例外のメッセージは持たない */
sealed interface JevCallResult {
    /** 値が読めた。煩悩のキー → 値（0.0〜1.0） */
    data class Values(val values: Map<String, Double>) : JevCallResult

    /** 成功以外の応答 */
    data class Failure(val failure: JevFailure) : JevCallResult

    /** 通信できなかった（タイムアウトを含む） */
    data object NetworkError : JevCallResult

    /** 成功の応答だったが、中身が想定外 */
    data object Unexpected : JevCallResult
}
