package io.github.mizunadad.dokuphilo

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 送信前のマスク（Sanitizer.kt）の回帰テスト。PC 上で動く（Android の端末は要らない）。
 *
 * 文例は、EGOGUARD の tools/test_sanitizer.mjs から機械的に取り出したものを読む: ../../ref/sanitizer_cases.json
 * （app モジュールのフォルダからの相対パス。文例をこのファイルに書き写さない）
 *
 * 不一致が出たら、Sanitizer.kt も文例も直さずに報告すること（Sanitizer.kt は EGOGUARD 版の流用）。
 */
class SanitizerTest {

    private data class Case(val input: String, val expected: String, val note: String)

    companion object {
        private const val CASES_PATH = "../../ref/sanitizer_cases.json"

        // 最後の1行のための控え。読めなかったものは -1 のまま
        private var caseTotal = -1
        private var caseMatched = -1

        @AfterClass
        @JvmStatic
        fun printSummary() {
            fun n(value: Int) = if (value < 0) "?" else value.toString()
            println("マスクの文例 ${n(caseMatched)}/${n(caseTotal)} 件一致")
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

    private fun loadCases(): List<Case> {
        val root = Json.parseToJsonElement(readUtf8(CASES_PATH)).jsonObject
        val cases = root["cases"]?.jsonArray
            ?: throw AssertionError("$CASES_PATH に cases がありません")
        return cases.map { element ->
            val obj = element.jsonObject
            Case(
                input = obj.getValue("input").jsonPrimitive.content,
                expected = obj.getValue("expected").jsonPrimitive.content,
                note = obj["note"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    /** 全文例で Sanitizer.sanitize(input) が expected と一致する。不一致は全部を並べる */
    @Test
    fun sanitize_matchesAllCases() {
        val cases = loadCases()
        assertTrue("文例が1件もありません: $CASES_PATH", cases.isNotEmpty())

        val mismatches = mutableListOf<String>()
        for (case in cases) {
            val actual = Sanitizer.sanitize(case.input)
            if (actual != case.expected) {
                mismatches += "[${case.note}] 入力=${case.input} 期待=${case.expected} 結果=$actual"
            }
        }
        caseTotal = cases.size
        caseMatched = cases.size - mismatches.size

        if (mismatches.isNotEmpty()) {
            fail(
                "マスクの文例 ${mismatches.size}/${cases.size} 件が不一致:\n" +
                    mismatches.joinToString("\n")
            )
        }
    }
}
