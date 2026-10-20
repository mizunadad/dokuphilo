package io.github.mizunadad.dokuphilo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jev の閾値（JevThreshold.kt）のテスト。PC 上で動く（Android の端末は要らない）。
 * 範囲は全煩悩で 40〜90、5 刻み。既定は煩悩ごと（flex 80・bluff 55・anxious 55。ADR-028 決定4）。
 */
class JevThresholdTest {

    private val bonnos = listOf("flex", "bluff", "anxious")
    private val grid = (40..90 step 5).toList()

    /** 範囲と刻み */
    @Test
    fun range_is40To90Step5() {
        assertEquals(40, JEV_THRESHOLD_MIN)
        assertEquals(90, JEV_THRESHOLD_MAX)
        assertEquals(5, JEV_THRESHOLD_STEP)
    }

    /** 煩悩ごとの既定。表にない煩悩は 80 */
    @Test
    fun defaultThreshold_perBonno() {
        assertEquals(80, defaultThreshold("flex"))
        assertEquals(55, defaultThreshold("bluff"))
        assertEquals(55, defaultThreshold("anxious"))
        assertEquals(80, defaultThreshold("unknown"))
        assertEquals(80, defaultThreshold(""))
    }

    /** 既定の表は、Jev で判定するすべての煩悩を持ち、どれも範囲と刻みに合う */
    @Test
    fun defaults_coverAllJevQuestionsAndAreOnTheGrid() {
        assertEquals(JEV_QUESTIONS.keys, JEV_THRESHOLD_DEFAULTS.keys)
        for ((bonno, percent) in JEV_THRESHOLD_DEFAULTS) {
            assertTrue("$bonno の既定 $percent が範囲の外", percent in JEV_THRESHOLD_MIN..JEV_THRESHOLD_MAX)
            assertEquals("$bonno の既定 $percent が刻みに合わない", 0, percent % JEV_THRESHOLD_STEP)
            assertEquals(percent, defaultThreshold(bonno))
            // 既定そのものは、直されない
            assertEquals(percent, normalizeThreshold(bonno, percent))
        }
    }

    /** 40〜90 で 5 の倍数はそのまま（どの煩悩でも） */
    @Test
    fun normalize_keepsValidValues() {
        for (bonno in bonnos + "unknown") {
            for (percent in grid) {
                assertEquals("$bonno $percent", percent, normalizeThreshold(bonno, percent))
            }
        }
        // 範囲が広がった分（0.40・0.45）も、そのまま
        assertEquals(40, normalizeThreshold("flex", 40))
        assertEquals(45, normalizeThreshold("flex", 45))
    }

    /** 範囲の外と、刻みに合わない値は、その煩悩の既定 */
    @Test
    fun normalize_replacesInvalidValuesWithDefaultOfTheBonno() {
        val invalid = listOf(35, 95, 82, 0, -5, 100, 39, 41, 91, Int.MAX_VALUE, Int.MIN_VALUE)
        for (percent in invalid) {
            assertEquals("flex $percent", 80, normalizeThreshold("flex", percent))
            assertEquals("bluff $percent", 55, normalizeThreshold("bluff", percent))
            assertEquals("anxious $percent", 55, normalizeThreshold("anxious", percent))
            assertEquals("unknown $percent", 80, normalizeThreshold("unknown", percent))
        }
    }

    /** 上げる: 1刻み。最大では動かない */
    @Test
    fun raise_stepsUpAndStopsAtMax() {
        for (bonno in bonnos) {
            assertEquals(85, raiseThreshold(bonno, 80))
            assertEquals(90, raiseThreshold(bonno, 85))
            assertEquals(90, raiseThreshold(bonno, 90))
            assertEquals(45, raiseThreshold(bonno, 40))
            assertTrue(canRaiseThreshold(bonno, 85))
            assertFalse(canRaiseThreshold(bonno, 90))
        }
    }

    /** 下げる: 1刻み。最小（40）では動かない */
    @Test
    fun lower_stepsDownAndStopsAtMin() {
        for (bonno in bonnos) {
            assertEquals(75, lowerThreshold(bonno, 80))
            assertEquals(45, lowerThreshold(bonno, 50))
            assertEquals(40, lowerThreshold(bonno, 45))
            assertEquals(40, lowerThreshold(bonno, 40))
            assertEquals(85, lowerThreshold(bonno, 90))
            assertTrue(canLowerThreshold(bonno, 45))
            assertFalse(canLowerThreshold(bonno, 40))
        }
    }

    /** 合わない値から上げ下げするときは、先にその煩悩の既定に直す */
    @Test
    fun raiseAndLower_startFromDefaultWhenInvalid() {
        assertEquals(85, raiseThreshold("flex", 82))
        assertEquals(75, lowerThreshold("flex", 0))
        assertEquals(60, raiseThreshold("bluff", 82))
        assertEquals(50, lowerThreshold("bluff", 0))
        assertEquals(60, raiseThreshold("anxious", 100))
        assertEquals(50, lowerThreshold("anxious", 37))
        assertTrue(canRaiseThreshold("bluff", 95))
        assertTrue(canLowerThreshold("bluff", 35))
    }

    /** 最小から最大まで上げ切り、最小まで下げ切っても、範囲と刻みから外れない */
    @Test
    fun raiseAndLower_stayOnTheGrid() {
        for (bonno in bonnos) {
            var percent = 40
            val up = mutableListOf(percent)
            repeat(13) { percent = raiseThreshold(bonno, percent); up += percent }
            assertEquals(listOf(40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 90, 90, 90), up)
            val down = mutableListOf(percent)
            repeat(12) { percent = lowerThreshold(bonno, percent); down += percent }
            assertEquals(listOf(90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 40, 40), down)
        }
    }

    /** 閾値以上なら真（境目は真）。40〜90 のすべての刻みで確かめる */
    @Test
    fun meetsThreshold_boundaries() {
        assertTrue(meetsThreshold(0.80, 80))
        assertFalse(meetsThreshold(0.7999, 80))
        assertTrue(meetsThreshold(0.55, 55))
        assertFalse(meetsThreshold(0.5499, 55))
        assertTrue(meetsThreshold(0.40, 40))
        assertFalse(meetsThreshold(0.3999, 40))
        assertTrue(meetsThreshold(0.90, 90))
        for (percent in grid) {
            val edge = formatThreshold(percent).toDouble()
            // 境目は真（小数の誤差で偽にならないこと）。すぐ下は偽、すぐ上は真
            assertTrue("境目 $percent", meetsThreshold(edge, percent))
            assertFalse("境目のすぐ下 $percent", meetsThreshold(Math.nextDown(edge), percent))
            assertTrue("境目のすぐ上 $percent", meetsThreshold(Math.nextUp(edge), percent))
        }
        assertTrue(meetsThreshold(1.0, 90))
        assertFalse(meetsThreshold(0.0, 40))
    }

    /** 画面用の文字列 */
    @Test
    fun formatThreshold_showsTwoDecimals() {
        assertEquals("0.40", formatThreshold(40))
        assertEquals("0.45", formatThreshold(45))
        assertEquals("0.55", formatThreshold(55))
        assertEquals("0.80", formatThreshold(80))
        assertEquals("0.90", formatThreshold(90))
    }
}
