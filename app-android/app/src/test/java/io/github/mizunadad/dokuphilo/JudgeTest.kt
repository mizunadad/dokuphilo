package io.github.mizunadad.dokuphilo

import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 判定関数（Judge.kt）の回帰テスト。PC 上で動く（Android の端末は要らない）。
 *
 * - 辞典は、アプリに同梱するものをそのまま読む: src/main/assets/dictionary.json
 * - 文例は、tools/test_keywords.mjs の CASES を機械的に取り出したものを読む: ../../ref/keyword_cases.json
 *   （どちらも app モジュールのフォルダからの相対パス。文例をこのファイルに書き写さない）
 */
class JudgeTest {

    private data class Case(val text: String, val expected: String?)

    companion object {
        private const val DICTIONARY_PATH = "src/main/assets/dictionary.json"
        private const val CASES_PATH = "../../ref/keyword_cases.json"

        // 最後の1行のための控え。読めなかったものは -1 のまま
        private var sageCount = -1
        private var lineCount = -1
        private var caseTotal = -1
        private var caseMatched = -1

        @AfterClass
        @JvmStatic
        fun printSummary() {
            fun n(value: Int) = if (value < 0) "?" else value.toString()
            println("賢者 ${n(sageCount)} 名、セリフ ${n(lineCount)} 件、文例 ${n(caseMatched)}/${n(caseTotal)} 件一致")
        }
    }

    private fun readUtf8(path: String): String {
        val file = File(path)
        if (!file.isFile) {
            fail(
                "ファイルが見つかりません: $path（探した場所: ${file.absolutePath}）。" +
                    "テストは app モジュールのフォルダを作業フォルダにして実行してください。"
            )
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun loadDictionary(): Dictionary {
        val dictionary = parseDictionary(readUtf8(DICTIONARY_PATH))
        sageCount = dictionary.sages.size
        lineCount = dictionary.lines.size
        return dictionary
    }

    private fun loadCases(): List<Case> {
        val root = Json.parseToJsonElement(readUtf8(CASES_PATH)).jsonObject
        val cases = root["cases"]?.jsonArray
            ?: throw AssertionError("$CASES_PATH に cases がありません")
        return cases.map { element ->
            val obj = element.jsonObject
            Case(
                text = obj.getValue("text").jsonPrimitive.content,
                // expected: flex / bluff / anxious / null（煩悩なし）
                expected = obj.getValue("expected").jsonPrimitive.contentOrNull,
            )
        }
    }

    /** (a) 全文例で detectBest(text, bonnos) が expected と一致する。不一致は全部を並べる */
    @Test
    fun detectBest_matchesAllKeywordCases() {
        val dictionary = loadDictionary()
        val cases = loadCases()
        assertTrue("文例が1件もありません: $CASES_PATH", cases.isNotEmpty())

        val mismatches = mutableListOf<String>()
        for (case in cases) {
            val actual = detectBest(case.text, dictionary.bonnos)
            if (actual != case.expected) {
                mismatches += "期待=${case.expected ?: "なし"} 結果=${actual ?: "なし"} ${case.text}"
            }
        }
        caseTotal = cases.size
        caseMatched = cases.size - mismatches.size

        if (mismatches.isNotEmpty()) {
            fail(
                "文例 ${mismatches.size}/${cases.size} 件が不一致:\n" +
                    mismatches.joinToString("\n")
            )
        }
    }

    /** (b) pickSages: 各煩悩で200回、常に3名・重複なし・全員がその煩悩を affinity に持つ */
    @Test
    fun pickSages_returnsThreeDistinctSagesWithAffinity() {
        val dictionary = loadDictionary()
        val random = Random(20261004)
        assertTrue("煩悩の定義がありません", dictionary.bonnos.isNotEmpty())

        for (bonno in dictionary.bonnos.keys) {
            repeat(200) {
                val picked = pickSages(bonno, dictionary.sages, random)
                assertEquals("$bonno: 3名ではありません $picked", 3, picked.size)
                assertEquals("$bonno: 重複があります $picked", 3, picked.toSet().size)
                for (sageId in picked) {
                    val sage = dictionary.sages[sageId]
                    assertNotNull("$bonno: 辞典にない賢者 $sageId", sage)
                    assertTrue("$bonno: $sageId は affinity に $bonno を持ちません", bonno in sage!!.affinity)
                }
            }
        }
    }

    /**
     * (c) pickLine: 全賢者 × affinity の各煩悩 ×（3シチュエーションと null）で、
     * 返るセリフの sage と bonno が一致する。そのマスにセリフがあるときは situation も一致する
     */
    @Test
    fun pickLine_staysWithinSageAndBonno() {
        val dictionary = loadDictionary()
        val random = Random(20261004)
        val situations: List<String?> = dictionary.situations.keys.toList() + listOf(null)

        for ((sageId, sage) in dictionary.sages) {
            for (bonno in sage.affinity) {
                val sameBonno = dictionary.lines.filter { it.sage == sageId && it.bonno == bonno }
                for (situation in situations) {
                    val cellHasLine = situation != null && sameBonno.any { it.situation == situation }
                    repeat(50) {
                        val where = "$sageId × $bonno × ${situation ?: "null"}"
                        val line = pickLine(sageId, bonno, situation, dictionary.lines, random)
                        if (sameBonno.isEmpty()) {
                            assertEquals("$where: セリフがないのに返りました", null, line)
                        } else {
                            assertNotNull("$where: セリフが返りません", line)
                            assertEquals("$where: 別の賢者のセリフ ${line!!.id}", sageId, line.sage)
                            assertEquals("$where: 別の煩悩のセリフ ${line.id}", bonno, line.bonno)
                            if (cellHasLine) {
                                assertEquals("$where: 別のシチュエーションのセリフ ${line.id}", situation, line.situation)
                            }
                        }
                    }
                }
            }
        }
    }
}
