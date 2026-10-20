package io.github.mizunadad.dokuphilo

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Jev の要求と応答を扱う関数（JevProtocol.kt）のテスト。PC 上で動く（Android の端末は要らない）。このテストは通信しない。
 *
 * 定義文は、オフライン実験のスクリプトと突き合わせる: ../../ref/jev_defs_test.py（ADR-028 決定2）
 * （app モジュールのフォルダからの相対パス。定義文をこのファイルに書き写さない）
 * ../../ref/jev_offline_test.py は flex の実験の記録で、ここでは読まない。
 *
 * キーは、このテストには出てこない（要求の本文にキーは入らない）。
 */
class JevProtocolTest {

    private companion object {
        const val DEFS_TEST_PATH = "../../ref/jev_defs_test.py"

        /** 煩悩のキー → jev_defs_test.py の QUESTIONS のキー（ADR-028 決定2） */
        val DEFS_KEYS = mapOf("flex" to "flex", "bluff" to "bluff_b", "anxious" to "anxious_a")

        /** 採らなかった候補。これらの文を写していたら失敗させる */
        val OTHER_CANDIDATES = listOf("bluff_a", "bluff_e", "anxious_b")
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

    /** JSON の文字列の値として読む（文字列でなければ失敗） */
    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive ?: throw AssertionError("$key がありません")
        assertTrue("$key が文字列ではありません", value.isString)
        return value.content
    }

    /**
     * jev_defs_test.py から、1つのキーの定義のかたまりを取り出す。
     * `    "キー": {` の行から、行頭が空白4つの `},` の行まで。
     */
    private fun definitionBlock(script: String, defsKey: String): String {
        val lines = script.lines().map { it.trimEnd('\r') }
        val startLine = "    \"$defsKey\": {"
        val starts = lines.indices.filter { lines[it] == startLine }
        assertEquals("$DEFS_TEST_PATH に「$startLine」の行が1つだけあるはず", 1, starts.size)
        val start = starts.single()
        val end = (start + 1 until lines.size).firstOrNull { lines[it] == "    }," }
            ?: throw AssertionError("$DEFS_TEST_PATH の $defsKey の定義が閉じていません")
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun quoted(text: String): String = "\"" + text + "\""

    // ---- 要求 ----

    /** Jev に尋ねるのは flex・bluff・anxious の3件で、この順（ADR-028 決定1・5） */
    @Test
    fun questions_areFlexBluffAnxiousInOrder() {
        assertEquals(listOf("flex", "bluff", "anxious"), JEV_QUESTIONS.keys.toList())
    }

    /** 要求の本文の questions が3件で、それぞれ type・instructions・criteria（true・false）を持つ */
    @Test
    fun requestBody_hasModelStateAndThreeQuestions() {
        val state = "これはテスト用の文です"
        val root = Json.parseToJsonElement(buildJevRequestBody(state)).jsonObject

        assertEquals(setOf("model", "state", "questions"), root.keys)
        assertEquals("jev-latest", root.string("model"))
        assertEquals(state, root.string("state"))

        val questions = root.getValue("questions").jsonObject
        // Jev に送るキーは煩悩のキー（bluff_b・anxious_a ではない。ADR-028 決定3）
        assertEquals(listOf("flex", "bluff", "anxious"), questions.keys.toList())

        for ((bonno, definition) in JEV_QUESTIONS) {
            val question = questions.getValue(bonno).jsonObject
            assertEquals("$bonno の項目", setOf("type", "instructions", "criteria"), question.keys)
            assertEquals("noul", question.string("type"))
            assertEquals(definition.instructions, question.string("instructions"))

            val criteria = question.getValue("criteria").jsonObject
            assertEquals("$bonno の criteria", setOf("true", "false"), criteria.keys)
            assertEquals(definition.criteriaTrue, criteria.string("true"))
            assertEquals(definition.criteriaFalse, criteria.string("false"))
        }
    }

    /** state に「"」・改行・「\」などを含む文でも、JSON として読み直すと元の文に戻る */
    @Test
    fun requestBody_roundTripsSpecialCharacters() {
        val states = listOf(
            "彼は\"すごい\"と言った",
            "1行目\n2行目\r\n3行目",
            "円記号ではなく \\ です。末尾にも \\",
            "\"\\\n が続く文 \\\" \\n",
            "タブ\tと、制御文字\u0001と、絵文字😀と、行区切り\u2028",
            "{\"state\":\"入れ子に見える文\"}",
            "",
        )
        for (state in states) {
            val root = Json.parseToJsonElement(buildJevRequestBody(state)).jsonObject
            assertEquals(state, root.string("state"))
            // 文の中身で、ほかの項目が壊れないこと
            assertEquals("jev-latest", root.string("model"))
            assertEquals(setOf("flex", "bluff", "anxious"), root.getValue("questions").jsonObject.keys)
        }
    }

    // ---- 定義文の突き合わせ ----

    /** 突き合わせの表が、定義のすべてのキーを持つ */
    @Test
    fun definition_everyKeyHasItsSource() {
        assertEquals(JEV_QUESTIONS.keys, DEFS_KEYS.keys)
    }

    /**
     * キーごとに、jev_defs_test.py のそのキーの定義のかたまりの中に、
     * instructions・true・false が " で囲まれた形でそのまま含まれる
     * （flex → "flex"、bluff → "bluff_b"、anxious → "anxious_a"）
     */
    @Test
    fun definition_matchesDefsTestScript() {
        val script = readUtf8(DEFS_TEST_PATH)
        val missing = mutableListOf<String>()
        for ((bonno, definition) in JEV_QUESTIONS) {
            val defsKey = DEFS_KEYS.getValue(bonno)
            val block = definitionBlock(script, defsKey)
            assertTrue("$defsKey の定義は noul のはず", block.contains("\"type\": \"noul\""))
            for ((name, text) in listOf(
                "instructions" to definition.instructions,
                "true" to definition.criteriaTrue,
                "false" to definition.criteriaFalse,
            )) {
                assertTrue("$bonno の $name が空です", text.isNotEmpty())
                if (!block.contains(quoted(text))) missing += "$bonno（$defsKey）の $name: $text"
            }
        }
        if (missing.isNotEmpty()) {
            fail("$DEFS_TEST_PATH と一致しない定義文:\n" + missing.joinToString("\n"))
        }
    }

    /** 取り出したかたまりは、そのキーの定義だけ（ほかのキーの定義を含まない） */
    @Test
    fun definition_blockHoldsOneDefinitionOnly() {
        val script = readUtf8(DEFS_TEST_PATH)
        for (defsKey in DEFS_KEYS.values + OTHER_CANDIDATES) {
            val block = definitionBlock(script, defsKey)
            assertEquals("$defsKey のかたまりの instructions の数", 1, Regex("\"instructions\":").findAll(block).count())
            assertEquals("$defsKey のかたまりの true の数", 1, Regex("\"true\":").findAll(block).count())
            assertEquals("$defsKey のかたまりの false の数", 1, Regex("\"false\":").findAll(block).count())
        }
    }

    /** 採らなかった候補（bluff_a・bluff_e・anxious_b）の文を写していない */
    @Test
    fun definition_isNotAnotherCandidate() {
        val script = readUtf8(DEFS_TEST_PATH)
        val wrong = mutableListOf<String>()
        for (candidate in OTHER_CANDIDATES) {
            val block = definitionBlock(script, candidate)
            for ((bonno, definition) in JEV_QUESTIONS) {
                // instructions は英語の部分が候補どうしで同じことがあるので、3つの文をそれぞれ見る
                for ((name, text) in listOf(
                    "instructions" to definition.instructions,
                    "true" to definition.criteriaTrue,
                    "false" to definition.criteriaFalse,
                )) {
                    if (block.contains(quoted(text))) wrong += "$bonno の $name が $candidate の文と同じ"
                }
            }
        }
        if (wrong.isNotEmpty()) fail("採らなかった候補の文:\n" + wrong.joinToString("\n"))
    }

    /** 送り先とモデルも、jev_defs_test.py と同じ */
    @Test
    fun endpointAndModel_matchDefsTestScript() {
        val script = readUtf8(DEFS_TEST_PATH)
        assertEquals("https://api.typesafe.ai/v1/systemone", JEV_ENDPOINT)
        assertEquals("jev-latest", JEV_MODEL)
        assertTrue(script.contains("ENDPOINT = " + quoted(JEV_ENDPOINT)))
        assertTrue(script.contains("MODEL = " + quoted(JEV_MODEL)))
    }

    // ---- 応答 ----

    /**
     * answers の中身を、煩悩ごとの JSON のかけらから組み立てる（null なら、その煩悩を入れない）。
     * 既定は、3件とも正しい値。
     */
    private fun answers(
        flex: String? = """{"type":"noul","noul":0.11}""",
        bluff: String? = """{"type":"noul","noul":0.22}""",
        anxious: String? = """{"type":"noul","noul":0.33}""",
    ): String {
        val parts = listOf("flex" to flex, "bluff" to bluff, "anxious" to anxious)
            .mapNotNull { (key, value) -> value?.let { "\"$key\":$it" } }
        return """{"model":"jev-latest","answers":{${parts.joinToString(",")}},"usage":{"input_tokens":120,"output_tokens":0}}"""
    }

    private fun assertValues(expected: Map<String, Double>, body: String) {
        val result = parseJevResponse(body)
        assertTrue("値が読めるはず: $body → $result", result is JevParseResult.Values)
        val values = (result as JevParseResult.Values).values
        assertEquals(expected.keys.toList(), values.keys.toList())
        for ((key, value) in expected) assertEquals(key, value, values.getValue(key), 0.0)
    }

    /** 3件とも読める */
    @Test
    fun response_readsThreeNouls() {
        assertValues(
            mapOf("flex" to 0.03, "bluff" to 0.86, "anxious" to 0.53),
            """{"model":"jev-latest","answers":{"flex":{"type":"noul","noul":0.03},"bluff":{"type":"noul","noul":0.86},"anxious":{"type":"noul","noul":0.53}},"usage":{"input_tokens":120,"output_tokens":0}}""",
        )
        assertValues(mapOf("flex" to 0.11, "bluff" to 0.22, "anxious" to 0.33), answers())
        // 応答の中の並びが違っても、結果は定義の順（flex・bluff・anxious）
        assertValues(
            mapOf("flex" to 0.8731, "bluff" to 0.5, "anxious" to 0.25),
            """{"answers":{"anxious":{"noul":0.25},"flex":{"noul":0.8731},"bluff":{"noul":0.5}}}""",
        )
    }

    /** 範囲の端（0.0 と 1.0）は読める。整数の書き方でも読める */
    @Test
    fun response_acceptsRangeEnds() {
        assertValues(
            mapOf("flex" to 0.0, "bluff" to 1.0, "anxious" to 0.0),
            answers(flex = """{"noul":0.0}""", bluff = """{"noul":1.0}""", anxious = """{"noul":0}"""),
        )
        assertValues(
            mapOf("flex" to 1.0, "bluff" to 0.0, "anxious" to 1.0),
            answers(flex = """{"noul":1}""", bluff = """{"noul":0}""", anxious = """{"noul":1.0}"""),
        )
    }

    /** type の有無は見ない。知らないキーは無視する */
    @Test
    fun response_ignoresTypeAndUnknownKeys() {
        val expected = mapOf("flex" to 0.25, "bluff" to 0.5, "anxious" to 0.75)
        assertValues(
            expected,
            answers(flex = """{"noul":0.25}""", bluff = """{"noul":0.5}""", anxious = """{"noul":0.75}"""),
        )
        assertValues(
            expected,
            answers(
                flex = """{"type":"score","noul":0.25,"extra":[1,2]}""",
                bluff = """{"noul":0.5,"confidence":0.9}""",
                anxious = """{"type":"noul","noul":0.75}""",
            ),
        )
        // 実験で使ったキー（bluff_b・anxious_a）や、ほかのキーが混ざっていても、定義の3件だけを読む
        assertValues(
            expected,
            """{"answers":{"other":{"noul":0.99},"flex":{"noul":0.25},"bluff_b":{"noul":0.01},"bluff":{"noul":0.5},"anxious":{"noul":0.75},"more":"x"},"unknown":{"a":1}}""",
        )
    }

    /** 1件でも欠けると想定外（flex・bluff・anxious のどれが欠けても） */
    @Test
    fun response_missingOneIsUnexpected() {
        val bodies = listOf(
            answers(flex = null) to "flex なし",
            answers(bluff = null) to "bluff なし",
            answers(anxious = null) to "anxious なし",
            answers(flex = null, bluff = null) to "anxious だけ",
            answers(bluff = null, anxious = null) to "flex だけ（S2b までの応答の形）",
            answers(flex = null, bluff = null, anxious = null) to "answers が空",
            // 実験のキーで返っても、煩悩のキーがなければ想定外
            """{"answers":{"flex":{"noul":0.1},"bluff_b":{"noul":0.2},"anxious_a":{"noul":0.3}}}""" to "bluff_b・anxious_a のキー",
        )
        assertAllUnexpected(bodies)
    }

    /** どれか1件の noul が不正なら想定外（ほかの2件が正しくても） */
    @Test
    fun response_oneInvalidNoulIsUnexpected() {
        val invalid = listOf(
            "null" to "null",
            "0.8" to "数値そのもの",
            """{"type":"noul"}""" to "noul なし",
            """{"noul":"0.8"}""" to "noul が文字列",
            """{"noul":null}""" to "noul が null",
            """{"noul":true}""" to "noul が真偽値",
            """{"noul":{"value":0.8}}""" to "noul がオブジェクト",
            """{"noul":1.5}""" to "1.5（範囲の外）",
            """{"noul":-0.1}""" to "-0.1（範囲の外）",
            """{"noul":1.0000001}""" to "1 をわずかに超える",
        )
        val bodies = mutableListOf<Pair<String, String>>()
        for ((fragment, name) in invalid) {
            bodies += answers(flex = fragment) to "flex が $name"
            bodies += answers(bluff = fragment) to "bluff が $name"
            bodies += answers(anxious = fragment) to "anxious が $name"
        }
        assertAllUnexpected(bodies)
    }

    /** 形そのものが想定外の例。例外を外に出さない */
    @Test
    fun response_unexpectedShapes() {
        assertAllUnexpected(
            listOf(
                "" to "空文字",
                "   " to "空白だけ",
                "これは JSON ではありません" to "JSON でない",
                "<html><body>502 Bad Gateway</body></html>" to "HTML",
                answers().dropLast(1) to "閉じていない JSON",
                "null" to "null",
                "[]" to "配列",
                "0.8" to "数値だけ",
                """{"model":"jev-latest"}""" to "answers なし",
                """{"answers":null}""" to "answers が null",
                """{"answers":[]}""" to "answers が配列",
                """{"answers":{}}""" to "answers が空",
                """{"answers":{"other":{"noul":0.9}}}""" to "ほかのキーだけ",
            )
        )
    }

    private fun assertAllUnexpected(bodies: List<Pair<String, String>>) {
        assertTrue(bodies.isNotEmpty())
        val wrong = mutableListOf<String>()
        for ((body, name) in bodies) {
            val result = parseJevResponse(body)
            if (result != JevParseResult.Unexpected) wrong += "$name → $result"
        }
        if (wrong.isNotEmpty()) fail("想定外になるはずの応答:\n" + wrong.joinToString("\n"))
    }

    /** 「想定外」は応答の本文を持たない */
    @Test
    fun response_unexpectedDoesNotCarryBody() {
        val marker = "HONBUN-NO-MEJIRUSHI"
        val result = parseJevResponse("""{"error":"$marker"}""")
        assertEquals(JevParseResult.Unexpected, result)
        assertFalse(result.toString().contains(marker))
    }

    // ---- 誤りの分類 ----

    @Test
    fun failure_badKey() {
        assertEquals(JevFailure.BadKey, classifyJevFailure(401, ""))
        assertEquals(JevFailure.BadKey, classifyJevFailure(403, ""))
        assertEquals(JevFailure.BadKey, classifyJevFailure(401, """{"error":"unauthorized"}"""))
        // 401・403 を先に見る（本文に残高切れのヒント語があっても、キーの誤り）
        assertEquals(JevFailure.BadKey, classifyJevFailure(403, """{"error":"insufficient credit"}"""))
    }

    @Test
    fun failure_noCredit() {
        assertEquals(JevFailure.NoCredit, classifyJevFailure(402, ""))
        assertEquals(JevFailure.NoCredit, classifyJevFailure(402, """{"error":"payment required"}"""))
        // 402 が返らないときの保険: 4xx の本文から判断する
        assertEquals(JevFailure.NoCredit, classifyJevFailure(400, """{"error":"insufficient credit"}"""))
        assertEquals(JevFailure.NoCredit, classifyJevFailure(400, "Insufficient Credit"))
    }

    @Test
    fun failure_other() {
        assertEquals(JevFailure.Other(500), classifyJevFailure(500, ""))
        assertEquals(JevFailure.Other(500), classifyJevFailure(500, """{"error":"internal server error"}"""))
        assertEquals(JevFailure.Other(429), classifyJevFailure(429, ""))
        assertEquals(JevFailure.Other(429), classifyJevFailure(429, """{"error":"too many requests"}"""))
        assertEquals(JevFailure.Other(400), classifyJevFailure(400, """{"error":"bad request"}"""))
    }

    /** 本文のヒント語で残高切れとするのは 4xx のときだけ。5xx・3xx の本文にあっても「その他」 */
    @Test
    fun failure_hintInBodyCountsOnlyFor4xx() {
        val bodies = listOf(
            "<html><body>503 Service Unavailable: upstream load balancer error</body></html>",
            """{"error":"balance"}""",
            """{"error":"credit"}""",
            """{"error":"insufficient credit"}""",
            """{"error":"payment required"}""",
            """{"error":"out of funds"}""",
            """{"error":"quota exceeded"}""",
            "INSUFFICIENT",
        )
        val wrong = mutableListOf<String>()
        for (body in bodies) {
            for (code in listOf(500, 502, 503, 504, 599, 301, 302, 307, 399, 200, 600)) {
                val result = classifyJevFailure(code, body)
                if (result != JevFailure.Other(code)) wrong += "$code $body → $result（その他のはず）"
            }
            for (code in listOf(400, 404, 409, 422, 429, 499)) {
                val result = classifyJevFailure(code, body)
                if (result != JevFailure.NoCredit) wrong += "$code $body → $result（残高切れのはず）"
            }
            // 401・403・402 は、本文にかかわらず決まる
            if (classifyJevFailure(401, body) != JevFailure.BadKey) wrong += "401 $body"
            if (classifyJevFailure(403, body) != JevFailure.BadKey) wrong += "403 $body"
            if (classifyJevFailure(402, body) != JevFailure.NoCredit) wrong += "402 $body"
        }
        if (wrong.isNotEmpty()) fail("分類が違うもの:\n" + wrong.joinToString("\n"))
    }

    /** 4xx でも、本文にヒント語がなければ「その他」 */
    @Test
    fun failure_4xxWithoutHintIsOther() {
        for (code in listOf(400, 404, 409, 422, 429, 499)) {
            assertEquals(JevFailure.Other(code), classifyJevFailure(code, ""))
            assertEquals(JevFailure.Other(code), classifyJevFailure(code, """{"error":"something else"}"""))
        }
    }

    /** 3xx は「その他」（リダイレクトは追わない） */
    @Test
    fun failure_redirectIsOther() {
        assertEquals(JevFailure.Other(301), classifyJevFailure(301, ""))
        assertEquals(JevFailure.Other(302), classifyJevFailure(302, "<html>Moved</html>"))
        assertEquals(JevFailure.Other(307), classifyJevFailure(307, ""))
    }
}
