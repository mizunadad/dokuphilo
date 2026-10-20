package io.github.mizunadad.dokuphilo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 辞典データ（assets/dictionary.json）の型と読み込み。
// index.html の埋め込み JSON（ADR-012）と同じ形を、そのまま Kotlin の型に写す。
// Android の import を持たない（PC 上の単体テストからも使う）。
// 登場賢者とセリフは辞典のものだけを使う。このファイルに賢者名やセリフを書かない。

/** 組み合わせ条件（ADR-016）。all の各グループの語が同じ文に1つずつ揃えば1ヒット */
data class Combo(
    val id: String,
    val all: List<List<String>>,
)

/** bonnos / situations の1件。combos がないものは空リスト */
data class KeywordEntry(
    val label: String,
    val keywords: List<String>,
    val combos: List<Combo>,
)

data class Sage(
    val name: String,
    val title: String,
    val lifespan: String,
    val affinity: List<String>,
)

data class Study(
    val core: String,
    val reasoning: String,
    val praxis: String,
)

data class Line(
    val id: String,
    val sage: String,
    val bonno: String,
    val situation: String,
    val punchline: String,
    val study: Study,
)

/** bonnos・situations・sages は、JSON に書かれたキーの順を保つ（detectBest の同数時の優先順に使う） */
data class Dictionary(
    val bonnos: Map<String, KeywordEntry>,
    val situations: Map<String, KeywordEntry>,
    val sages: Map<String, Sage>,
    val lines: List<Line>,
)

/**
 * 辞典の JSON 文字列を読む。形が合わなければ例外を投げる（呼び出し側で受けて「読み込めませんでした」を出す）。
 * JsonObject はキーの記述順を保つので、LinkedHashMap に順に詰め替える。
 */
fun parseDictionary(jsonText: String): Dictionary {
    val root = Json.parseToJsonElement(jsonText).jsonObject
    return Dictionary(
        bonnos = keywordTable(root.req("bonnos").jsonObject),
        situations = keywordTable(root.req("situations").jsonObject),
        sages = orderedMap(root.req("sages").jsonObject) { parseSage(it.jsonObject) },
        lines = root.req("lines").jsonArray.map { parseLine(it.jsonObject) },
    )
}

private fun JsonObject.req(key: String): JsonElement =
    this[key] ?: throw IllegalArgumentException("辞典データに $key がありません")

private fun JsonObject.str(key: String): String = req(key).jsonPrimitive.content

private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

private fun <T> orderedMap(obj: JsonObject, convert: (JsonElement) -> T): Map<String, T> {
    val out = LinkedHashMap<String, T>()
    for ((key, value) in obj) out[key] = convert(value)
    return out
}

private fun keywordTable(obj: JsonObject): Map<String, KeywordEntry> =
    orderedMap(obj) { parseKeywordEntry(it.jsonObject) }

private fun parseKeywordEntry(obj: JsonObject): KeywordEntry = KeywordEntry(
    label = obj.str("label"),
    keywords = obj.req("keywords").strings(),
    combos = obj["combos"]?.jsonArray?.map { parseCombo(it.jsonObject) } ?: emptyList(),
)

private fun parseCombo(obj: JsonObject): Combo = Combo(
    id = obj.str("id"),
    all = obj.req("all").jsonArray.map { it.strings() },
)

private fun parseSage(obj: JsonObject): Sage = Sage(
    name = obj.str("name"),
    title = obj.str("title"),
    lifespan = obj.str("lifespan"),
    affinity = obj.req("affinity").strings(),
)

private fun parseLine(obj: JsonObject): Line {
    val study = obj.req("study").jsonObject
    return Line(
        id = obj.str("id"),
        sage = obj.str("sage"),
        bonno = obj.str("bonno"),
        situation = obj.str("situation"),
        punchline = obj.str("punchline"),
        study = Study(
            core = study.str("core"),
            reasoning = study.str("reasoning"),
            praxis = study.str("praxis"),
        ),
    )
}
