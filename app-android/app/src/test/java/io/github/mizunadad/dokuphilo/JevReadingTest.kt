package io.github.mizunadad.dokuphilo

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Jev の値の読み方（JevReading.kt）のテスト。PC 上で動く（Android の端末は要らない）。通信しない。
 *
 * 辞典は、アプリに同梱するものをそのまま読む: src/main/assets/dictionary.json
 * 文は、このテストのために辞典のキーワードから組み立てる（ref/keyword_cases.json の文例を書き写さない）。
 */
class JevReadingTest {

    private companion object {
        const val DICTIONARY_PATH = "src/main/assets/dictionary.json"
    }

    private fun loadDictionary(): Dictionary {
        val file = File(DICTIONARY_PATH)
        if (!file.isFile) {
            fail(
                "ファイルが見つかりません: $DICTIONARY_PATH（探した場所: ${file.absolutePath}）。" +
                    "テストは app モジュールのフォルダを作業フォルダにして実行してください。"
            )
        }
        return parseDictionary(file.readText(Charsets.UTF_8))
    }

    // ---- 値と閾値から結果を作る ----

    @Test
    fun readJevValues_usesThresholdAndMeetsThreshold() {
        assertEquals(
            listOf(JevReading("flex", 0.8731, 80, true)),
            readJevValues(mapOf("flex" to 0.8731), mapOf("flex" to 80)),
        )
        assertEquals(
            listOf(JevReading("flex", 0.7999, 80, false)),
            readJevValues(mapOf("flex" to 0.7999), mapOf("flex" to 80)),
        )
        // 境目は「届いた」
        assertEquals(
            listOf(JevReading("flex", 0.80, 80, true)),
            readJevValues(mapOf("flex" to 0.80), mapOf("flex" to 80)),
        )
        // 同じ値でも、閾値が違えば結果が違う（使った閾値を結果が持つ）
        assertEquals(
            listOf(JevReading("flex", 0.60, 55, true)),
            readJevValues(mapOf("flex" to 0.60), mapOf("flex" to 55)),
        )
        assertEquals(
            listOf(JevReading("flex", 0.60, 65, false)),
            readJevValues(mapOf("flex" to 0.60), mapOf("flex" to 65)),
        )
    }

    /** 届いたかどうかは、meetsThreshold と同じ */
    @Test
    fun readJevValues_metAgreesWithMeetsThreshold() {
        val random = Random(20261004)
        for (bonno in JEV_QUESTIONS.keys) {
            for (percent in 40..90 step 5) {
                repeat(100) {
                    val value = random.nextDouble()
                    val reading = readJevValues(mapOf(bonno to value), mapOf(bonno to percent)).single()
                    assertEquals(bonno, reading.bonno)
                    assertEquals(meetsThreshold(value, percent), reading.met)
                    assertEquals(percent, reading.thresholdPercent)
                }
            }
        }
    }

    /** 閾値がない・範囲や刻みに合わないときは、その煩悩の既定の閾値。値のない煩悩と、定義にない煩悩は結果に入れない */
    @Test
    fun readJevValues_fallbacks() {
        assertEquals(
            listOf(JevReading("flex", 0.85, 80, true)),
            readJevValues(mapOf("flex" to 0.85), emptyMap()),
        )
        assertEquals(
            listOf(JevReading("flex", 0.85, 80, true)),
            readJevValues(mapOf("flex" to 0.85), mapOf("flex" to 82)),
        )
        // 既定は煩悩ごと（flex 0.80、bluff 0.55、anxious 0.55）
        assertEquals(
            listOf(
                JevReading("flex", 0.60, 80, false),
                JevReading("bluff", 0.60, 55, true),
                JevReading("anxious", 0.60, 55, true),
            ),
            readJevValues(mapOf("flex" to 0.60, "bluff" to 0.60, "anxious" to 0.60), emptyMap()),
        )
        assertEquals(
            listOf(
                JevReading("flex", 0.60, 80, false),
                JevReading("bluff", 0.60, 55, true),
                JevReading("anxious", 0.60, 55, true),
            ),
            readJevValues(
                mapOf("flex" to 0.60, "bluff" to 0.60, "anxious" to 0.60),
                mapOf("flex" to 35, "bluff" to 95, "anxious" to 57),
            ),
        )
        // 範囲が広がった分（0.40・0.45）は、そのまま使う
        assertEquals(
            listOf(JevReading("bluff", 0.42, 40, true), JevReading("anxious", 0.42, 45, false)),
            readJevValues(mapOf("bluff" to 0.42, "anxious" to 0.42), mapOf("bluff" to 40, "anxious" to 45)),
        )
        assertEquals(emptyList<JevReading>(), readJevValues(emptyMap(), mapOf("flex" to 80)))
        assertEquals(
            listOf(JevReading("flex", 0.10, 80, false)),
            readJevValues(mapOf("other" to 0.99, "flex" to 0.10), mapOf("flex" to 80, "other" to 50)),
        )
    }

    /** 並びは記述順（order） */
    @Test
    fun readJevValues_followsOrder() {
        val readings = readJevValues(
            values = mapOf("c" to 0.9, "a" to 0.6, "b" to 0.7),
            thresholds = mapOf("a" to 50, "b" to 80, "c" to 90),
            order = listOf("a", "b", "c"),
        )
        assertEquals(
            listOf(
                JevReading("a", 0.6, 50, true),
                JevReading("b", 0.7, 80, false),
                JevReading("c", 0.9, 90, true),
            ),
            readings,
        )
    }

    // ---- 届いた煩悩から選ぶ ----

    @Test
    fun pickJevReading_flexOnly() {
        val hit = JevReading("flex", 0.9, 80, true)
        val miss = JevReading("flex", 0.5, 80, false)
        assertEquals(hit, pickJevReading(listOf(hit)))
        assertNull(pickJevReading(listOf(miss)))
        assertNull(pickJevReading(emptyList()))
    }

    /** 届いたもののうち値が最大。届かなかったものは、値が大きくても選ばない */
    @Test
    fun pickJevReading_picksLargestAmongMet() {
        val order = listOf("a", "b", "c")
        val a = JevReading("a", 0.60, 50, true)
        val b = JevReading("b", 0.89, 90, false)
        val c = JevReading("c", 0.75, 70, true)
        assertEquals(c, pickJevReading(listOf(a, b, c), order))
        assertEquals(c, pickJevReading(listOf(c, b, a), order))
        assertEquals(a, pickJevReading(listOf(a, b), order))
        assertNull(pickJevReading(listOf(b), order))
    }

    /** 同じ値なら記述順が先のもの（渡した並びには左右されない） */
    @Test
    fun pickJevReading_tieGoesToEarlierInOrder() {
        val order = listOf("a", "b", "c")
        val a = JevReading("a", 0.85, 80, true)
        val b = JevReading("b", 0.85, 80, true)
        val c = JevReading("c", 0.85, 80, true)
        assertEquals(a, pickJevReading(listOf(a, b, c), order))
        assertEquals(a, pickJevReading(listOf(c, b, a), order))
        assertEquals(b, pickJevReading(listOf(c, b), order))
        // order にない煩悩は選ばない
        assertNull(pickJevReading(listOf(JevReading("x", 0.99, 50, true)), order))
    }

    /** 既定の並び（flex → bluff → anxious）で、同じ値なら先のもの（ADR-028 決定5） */
    @Test
    fun pickJevReading_tieFollowsFlexBluffAnxious() {
        val flex = JevReading("flex", 0.85, 80, true)
        val bluff = JevReading("bluff", 0.85, 55, true)
        val anxious = JevReading("anxious", 0.85, 55, true)
        assertEquals(flex, pickJevReading(listOf(anxious, bluff, flex)))
        assertEquals(bluff, pickJevReading(listOf(anxious, bluff)))
        assertEquals(bluff, pickJevReading(listOf(anxious, bluff, flex.copy(met = false))))
        assertEquals(anxious, pickJevReading(listOf(anxious)))
    }

    /**
     * 既定の閾値（flex 0.80、bluff 0.55、anxious 0.55）で、オフライン実験の値から選ばれる煩悩。
     * 値は ref/ISSUES.md「Jev オフライン実験の結果（2026-10-05）」の表から（flex / bluff_b / anxious_a の順）。
     * ここに書くのは Jev の値だけ。文そのものは書かない。
     */
    @Test
    fun pickJevReading_withDefaultThresholdsOnExperimentValues() {
        data class Row(val id: String, val flex: Double, val bluff: Double, val anxious: Double, val expected: String?)

        val rows = listOf(
            Row("B4", 0.62, 0.62, 0.15, "bluff"),
            Row("B6", 0.03, 0.86, 0.53, "bluff"),
            // anxious も届く（0.55）が、値が最大のものを選ぶ
            Row("B7", 0.14, 0.98, 0.55, "bluff"),
            Row("A6", 0.03, 0.11, 0.94, "anxious"),
            Row("M3", 0.88, 0.17, 0.05, "flex"),
            Row("N3", 0.32, 0.39, 0.09, null),
        )
        val defaults = JEV_QUESTIONS.keys.associateWith { defaultThreshold(it) }
        assertEquals(mapOf("flex" to 80, "bluff" to 55, "anxious" to 55), defaults)

        val wrong = mutableListOf<String>()
        for (row in rows) {
            val values = mapOf("flex" to row.flex, "bluff" to row.bluff, "anxious" to row.anxious)
            // 閾値を渡したときと、渡さないとき（既定に落ちる）で同じ
            for (thresholds in listOf(defaults, emptyMap())) {
                val readings = readJevValues(values, thresholds)
                assertEquals(listOf("flex", "bluff", "anxious"), readings.map { it.bonno })
                val picked = pickJevReading(readings)?.bonno
                if (picked != row.expected) wrong += "${row.id}: 期待=${row.expected ?: "なし"} 結果=${picked ?: "なし"}"
            }
        }
        if (wrong.isNotEmpty()) fail("選ばれる煩悩が違う:\n" + wrong.joinToString("\n"))

        // B7 では anxious も届いている（届いたうえで、bluff のほうが大きい）
        val b7 = readJevValues(mapOf("flex" to 0.14, "bluff" to 0.98, "anxious" to 0.55), defaults)
        assertEquals(listOf(false, true, true), b7.map { it.met })
        // B6 の anxious（0.53）は届かない
        val b6 = readJevValues(mapOf("flex" to 0.03, "bluff" to 0.86, "anxious" to 0.53), defaults)
        assertEquals(listOf(false, true, false), b6.map { it.met })
    }

    // ---- 画面用の文字列 ----

    @Test
    fun formatJevValue_examples() {
        assertEquals("0.799", formatJevValue(0.7999))
        assertEquals("0.800", formatJevValue(0.8))
        assertEquals("1.000", formatJevValue(1.0))
        assertEquals("0.000", formatJevValue(0.0))
        assertEquals("0.873", formatJevValue(0.8731))
        assertEquals("0.999", formatJevValue(0.9999))
        assertEquals("0.000", formatJevValue(0.0009))
        assertEquals("0.001", formatJevValue(0.001))
        // 二進の値をそのまま十進に展開する書き方だと、1つ小さく出てしまう値（0.7 は二進では 0.6999… になる）
        assertEquals("0.700", formatJevValue(0.7))
        assertEquals("0.570", formatJevValue(0.57))
        assertEquals("0.580", formatJevValue(0.58))
        assertEquals("0.290", formatJevValue(0.29))
        assertEquals("0.699", formatJevValue(Math.nextDown(0.7)))
    }

    /** 閾値ちょうどの値は、formatThreshold の末尾に 0 を付けた文字列と同じ（40〜90 のすべて。念のため 0〜100 も） */
    @Test
    fun formatJevValue_atThresholdEqualsThresholdText() {
        val wrong = mutableListOf<String>()
        for (percent in 0..100) {
            val expected = formatThreshold(percent) + "0"
            val actual = formatJevValue(percent / 100.0)
            if (actual != expected) wrong += "$percent: 期待=$expected 結果=$actual"
        }
        if (wrong.isNotEmpty()) fail("閾値ちょうどの値の表示が違う:\n" + wrong.joinToString("\n"))
    }

    /**
     * 閾値のすぐ下の値（Math.nextDown）は、届かず、表示も閾値より小さい。
     * 届かなかった値が届いたように見えない（40〜90 のすべて）
     */
    @Test
    fun formatJevValue_justBelowThresholdShowsSmaller() {
        val wrong = mutableListOf<String>()
        for (percent in 40..90) {
            val value = Math.nextDown(percent / 100.0)
            val shown = formatJevValue(value)
            val thresholdText = formatThreshold(percent) + "0"
            if (meetsThreshold(value, percent)) wrong += "$percent: 届いたことになっている"
            // どちらも "0.xxx" の形なので、文字列の大小が数の大小と同じ
            if (shown.length != 5 || shown >= thresholdText) wrong += "$percent: 表示=$shown 閾値=$thresholdText"
            // 1/1000 だけ小さい表示になる
            val expected = formatJevValue((percent * 10 - 1) / 1000.0)
            if (shown != expected) wrong += "$percent: 表示=$shown 期待=$expected"
        }
        if (wrong.isNotEmpty()) fail("閾値のすぐ下の値の表示が違う:\n" + wrong.joinToString("\n"))
    }

    /** 逆もない: 届いた値は閾値以上に見え、届かなかった値は閾値より小さく見える（境目の前後と、乱数の値で確かめる） */
    @Test
    fun formatJevValue_agreesWithMeetsThreshold() {
        val random = Random(20261004)
        val values = mutableListOf<Double>()
        for (k in 0..1000) {
            val v = k / 1000.0
            values += v
            if (k > 0) values += Math.nextDown(v)
            if (k < 1000) values += Math.nextUp(v)
        }
        repeat(20000) { values += random.nextDouble() }

        val wrong = mutableListOf<String>()
        for (value in values) {
            val shown = formatJevValue(value)
            if (shown.length != 5) { wrong += "$value: 表示=$shown"; continue }
            for (percent in 40..90 step 5) {
                val thresholdText = formatThreshold(percent) + "0"
                val met = meetsThreshold(value, percent)
                val looksMet = shown >= thresholdText
                if (met != looksMet && wrong.size < 20) wrong += "$value 閾値=$percent: 届いた=$met 表示=$shown"
            }
        }
        if (wrong.isNotEmpty()) fail("表示と判定が食い違う:\n" + wrong.joinToString("\n"))
    }

    /** 1/1000 の刻みの値は、そのまま出る。すぐ下の値は、1つ小さい刻みで出る（切り捨て） */
    @Test
    fun formatJevValue_truncatesToThreeDecimals() {
        for (k in 0..1000) {
            val expected = "${k / 1000}.${(k % 1000).toString().padStart(3, '0')}"
            assertEquals(expected, formatJevValue(k / 1000.0))
            if (k > 0) {
                val below = "${(k - 1) / 1000}.${((k - 1) % 1000).toString().padStart(3, '0')}"
                assertEquals(below, formatJevValue(Math.nextDown(k / 1000.0)))
            }
        }
    }

    /** 数でない値と負の値では落ちない */
    @Test
    fun formatJevValue_doesNotThrowOnOddValues() {
        assertEquals("-", formatJevValue(Double.NaN))
        assertEquals("-", formatJevValue(Double.POSITIVE_INFINITY))
        assertEquals("-", formatJevValue(-0.1))
        assertEquals("0.000", formatJevValue(Double.MIN_VALUE))
        assertEquals("1.500", formatJevValue(1.5))
    }

    // ---- Jev が煩悩ありとしたときの判定結果 ----

    /** bonno は Jev の結果。カードは3枚で、重複がなく、全員がその煩悩を affinity に持ち、セリフもその煩悩のもの */
    @Test
    fun buildJevJudgement_usesJevBonnoAndPicksCards() {
        val dictionary = loadDictionary()
        val random = Random(20261004)
        // キーワードに当たらない文（Jev に回るのは、こういう文）
        val text = "  きょうは晴れていて風がここちよいです  "
        assertNull("この文はキーワードに当たらないはず", detectBest(text, dictionary.bonnos))

        for (bonno in JEV_QUESTIONS.keys) {
            repeat(100) {
                val judgement = buildJevJudgement(text, bonno, dictionary, random)
                assertEquals(text.trim(), judgement.raw)
                assertEquals(bonno, judgement.bonno)
                assertEquals(detectBest(text.trim(), dictionary.situations), judgement.situation)
                assertEquals(3, judgement.cards.size)
                assertEquals(3, judgement.cards.map { it.sageId }.toSet().size)
                for (card in judgement.cards) {
                    assertEquals(dictionary.sages[card.sageId], card.sage)
                    assertTrue("${card.sageId} は $bonno を affinity に持たない", bonno in card.sage.affinity)
                    val line = card.line
                    assertNotNull("${card.sageId} × $bonno のセリフがない", line)
                    assertEquals(card.sageId, line!!.sage)
                    assertEquals(bonno, line.bonno)
                }
            }
        }
    }

    /** situation は元の文でキーワード判定する。そのマスにセリフがある賢者は、そのシチュエーションのセリフになる */
    @Test
    fun buildJevJudgement_detectsSituationFromOriginalText() {
        val dictionary = loadDictionary()
        val random = Random(20261004)
        val bonno = "flex"
        var checkedSituations = 0

        for ((situation, entry) in dictionary.situations) {
            // そのシチュエーションだけに当たり、煩悩のキーワードには当たらない文を、辞典のキーワードから組み立てる
            val text = entry.keywords
                .map { keyword -> "それは${keyword}のことでした" }
                .firstOrNull { candidate ->
                    detectBest(candidate, dictionary.bonnos) == null &&
                        detectBest(candidate, dictionary.situations) == situation
                } ?: continue
            checkedSituations++

            repeat(50) {
                val judgement = buildJevJudgement(text, bonno, dictionary, random)
                assertEquals(situation, judgement.situation)
                for (card in judgement.cards) {
                    val line = card.line ?: continue
                    val cellHasLine = dictionary.lines.any {
                        it.sage == card.sageId && it.bonno == bonno && it.situation == situation
                    }
                    if (cellHasLine) assertEquals(situation, line.situation)
                    assertEquals(bonno, line.bonno)
                }
            }
        }
        assertTrue("シチュエーションの文を1つも組み立てられなかった", checkedSituations > 0)
    }

    /** シチュエーションに当たらない文では situation は null で、同じ煩悩のセリフから選ぶ */
    @Test
    fun buildJevJudgement_noSituation() {
        val dictionary = loadDictionary()
        val text = "きょうは晴れていて風がここちよいです"
        assertNull(detectBest(text, dictionary.situations))
        val judgement = buildJevJudgement(text, "flex", dictionary, Random(1))
        assertNull(judgement.situation)
        assertFalse(judgement.cards.isEmpty())
        assertTrue(judgement.cards.all { it.line?.bonno == "flex" })
    }

    /** キーワード判定（judge）で煩悩ありのときと、同じ形の結果になる（同じ乱数なら同じカード） */
    @Test
    fun buildJevJudgement_sameShapeAsKeywordJudgement() {
        val dictionary = loadDictionary()
        val keyword = dictionary.bonnos.getValue("flex").keywords.first()
        val text = "それでね、${keyword}という話なんだ"
        val byKeyword = judge(text, dictionary, Random(7))
        assertNotNull(byKeyword)
        assertEquals("flex", byKeyword!!.bonno)
        val byJev = buildJevJudgement(text, "flex", dictionary, Random(7))
        assertEquals(byKeyword, byJev)
    }
}
