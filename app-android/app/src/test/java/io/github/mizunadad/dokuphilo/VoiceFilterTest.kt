package io.github.mizunadad.dokuphilo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音声の歯止め（VoiceFilter.kt）のテスト。PC 上で動く（Android の端末は要らない）。
 * 文はこのテストのために作ったもの（ref/keyword_cases.json の文例ではない）。
 */
class VoiceFilterTest {

    /** 4文字未満は受け付けない（空、空白だけ、1〜3文字） */
    @Test
    fun shorterThanFourChars_isRejected() {
        assertNull(acceptVoiceText("", null))
        assertNull(acceptVoiceText("   ", null))
        assertNull(acceptVoiceText("あ", null))
        assertNull(acceptVoiceText("えっと", null))
    }

    /** ちょうど4文字は受け付ける */
    @Test
    fun exactlyFourChars_isAccepted() {
        assertEquals("おはよう", acceptVoiceText("おはよう", null))
    }

    /** 前後の空白（全角の空白・改行も）は取り除いて返す。文中の空白は残す */
    @Test
    fun surroundingWhitespace_isTrimmed() {
        assertEquals("駅まで歩きます", acceptVoiceText("  駅まで歩きます \n", null))
        assertEquals("駅まで歩きます", acceptVoiceText("　駅まで歩きます　", null))
        assertEquals("駅まで 歩きます", acceptVoiceText(" 駅まで 歩きます ", null))
    }

    /** 空白を取り除いた結果が4文字未満なら受け付けない */
    @Test
    fun shortAfterTrim_isRejected() {
        assertNull(acceptVoiceText("  はい   ", null))
    }

    /** 直前に受け付けた文と同じなら受け付けない（前後の空白だけが違う場合も同じとみなす） */
    @Test
    fun sameAsLastAccepted_isRejected() {
        assertNull(acceptVoiceText("お茶を入れました", "お茶を入れました"))
        assertNull(acceptVoiceText(" お茶を入れました ", "お茶を入れました"))
    }

    /** 直前と違う文は受け付ける。直前がない（null）ときも受け付ける */
    @Test
    fun differentFromLastAccepted_isAccepted() {
        assertEquals("窓を開けました", acceptVoiceText("窓を開けました", "お茶を入れました"))
        assertEquals("窓を開けました", acceptVoiceText("窓を開けました", null))
    }

    /** 文字数は見た目の1文字で数える（サロゲートペアの字を2文字に数えない） */
    @Test
    fun surrogatePair_countsAsOneChar() {
        // 「𠮷」は UTF-16 では2単位。3文字なので受け付けない
        assertNull(acceptVoiceText("𠮷田だ", null))
        assertEquals("𠮷田です", acceptVoiceText("𠮷田です", null))
    }
}
