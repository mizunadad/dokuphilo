package io.github.mizunadad.dokuphilo

import kotlin.random.Random

// 判定関数。index.html の <script> 内の同名の関数を移植したもの（ADR-012 の引き当てルール、ADR-026 決定6）。
// index.html と同じ結果になること。直すときは index.html を先に直し、同じ変更をここに写す。
// Android の import を持たない（PC 上の単体テストで、tools/test_keywords.mjs と同じ文例を使って確かめる）。
// 入力文をログに出さない。保存もしない。

/** 文に含まれるキーワードの個数（出現回数ではない）。大文字と小文字は区別しない */
fun countHits(text: String, keywords: List<String>): Int {
    val lower = text.lowercase()
    return keywords.count { lower.contains(it.lowercase()) }
}

/** 文に区切る。「、」では区切らない（ADR-016） */
fun splitSentences(text: String): List<String> =
    text.split('。', '．', '！', '!', '？', '?', '\n')

/** 組み合わせ条件: all の全グループの語が同じ文に1語以上あれば1ヒット。1つの combo は最大1（ADR-016） */
fun countCombos(text: String, combos: List<Combo>): Int {
    val sentences = splitSentences(text)
    return combos.count { combo ->
        sentences.any { sentence ->
            combo.all.all { group -> countHits(sentence, group) > 0 }
        }
    }
}

/** 当たった数が最多のキー。同数なら記述順が先のもの。どれにも当たらなければ null */
fun detectBest(text: String, table: Map<String, KeywordEntry>): String? {
    var best: String? = null
    var bestCount = 0
    for ((key, entry) in table) {
        val n = countHits(text, entry.keywords) + countCombos(text, entry.combos)
        if (n > bestCount) {
            best = key
            bestCount = n
        }
    }
    return best
}

/** その煩悩を affinity に持つ賢者をシャッフルし、先頭の最大3名の ID を返す（ADR-015） */
fun pickSages(
    bonno: String,
    sages: Map<String, Sage>,
    random: Random = Random.Default,
): List<String> =
    sages.filter { (_, sage) -> bonno in sage.affinity }
        .keys
        .shuffled(random)
        .take(3)

/**
 * 同じ賢者・同じ煩悩のセリフから1件を選ぶ。別の煩悩には絶対に広げない（B4）。
 * シチュエーションがあり、一致するセリフがあればその中から。なければ候補の全部から。候補がなければ null
 */
fun pickLine(
    sageId: String,
    bonno: String,
    situation: String?,
    lines: List<Line>,
    random: Random = Random.Default,
): Line? {
    val sameBonno = lines.filter { it.sage == sageId && it.bonno == bonno }
    var candidates = sameBonno
    if (!situation.isNullOrEmpty()) {
        val exact = sameBonno.filter { it.situation == situation }
        if (exact.isNotEmpty()) candidates = exact
    }
    if (candidates.isEmpty()) return null
    return candidates[random.nextInt(candidates.size)]
}

/** 3賢者のカード1枚分。line が null のときは「該当セリフなし」 */
data class SageCard(
    val sageId: String,
    val sage: Sage,
    val line: Line?,
)

/** 1回の判定の結果。raw は trim 済みの入力文（画面に出すだけで、保存しない） */
data class Judgement(
    val raw: String,
    val bonno: String?,
    val situation: String?,
    val cards: List<SageCard>,
)

/**
 * 入力文を判定する。空（空白だけ）なら null を返し、何もしない。
 * シチュエーションは、煩悩が判定されたときだけ判定する。
 * 煩悩に当たらなければカードを出さない（B5）。別の煩悩のセリフで代用しない。
 */
fun judge(
    text: String,
    dictionary: Dictionary,
    random: Random = Random.Default,
): Judgement? {
    val raw = text.trim()
    if (raw.isEmpty()) return null

    val bonno = detectBest(raw, dictionary.bonnos)
    val situation = if (bonno != null) detectBest(raw, dictionary.situations) else null
    if (bonno == null) return Judgement(raw, null, null, emptyList())

    val cards = pickSages(bonno, dictionary.sages, random).map { sageId ->
        SageCard(
            sageId = sageId,
            sage = dictionary.sages.getValue(sageId),
            line = pickLine(sageId, bonno, situation, dictionary.lines, random),
        )
    }
    return Judgement(raw, bonno, situation, cards)
}
