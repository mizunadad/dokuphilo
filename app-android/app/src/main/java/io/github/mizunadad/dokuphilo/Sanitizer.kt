package io.github.mizunadad.dokuphilo

/**
 * 端末内ローカルサニタイズ
 *
 * 設計上の要点:
 *  1. Regex はトップレベルで1回だけコンパイルする。
 *     @Composable のローカル関数として定義すると、リコンポジションのたびに
 *     Regex が再コンパイルされ UI スレッドに効いてくる（v1 prototype の問題）。
 *  2. 置換は「長く・具体的なもの」から順に行う。
 *     数値置換を先に走らせると、メールアドレスや電話番号の中の数字が先に
 *     [NUM] へ置換され、後続パターンが永久にマッチしなくなる。
 *  3. 敬称の直前は漢字・カタカナのみに限定する。
 *     ひらがなを含めると「仕様」「模様」「皆様」等が [NAME]様 へ誤爆する。
 *
 * 限界（README にも明記すること）:
 *   敬称のつかない人名（「田中が言ってた」）は構造上マスクできない。
 *   これは完全な匿名化ではなく、ベストエフォートのマスキングである。
 *
 * テスト: tools/test_sanitizer.mjs と同じケース表で期待値を突き合わせること。
 */
object Sanitizer {

    // 置換順序がそのまま仕様。並べ替えないこと。
    private val RULES: List<Pair<Regex, String>> = listOf(
        // 1. 完全なURL
        Regex("""https?://[^\s、。]+""") to "[URL]",
        // 2. メールアドレス（ドメイン単体URLより前、数値置換より前）
        Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""") to "[CONTACT]",
        // 3. スキームなしのドメイン
        Regex("""\b[a-z0-9\-]+\.(co\.jp|com|net|org|jp|io|ai|dev)\b(/[^\s、。]*)?""", RegexOption.IGNORE_CASE) to "[URL]",
        // 4. 電話番号（数値置換より前。日本の番号は 0 始まりに限定して誤爆を防ぐ）
        Regex("""\b0\d{1,4}[-(\s]?\d{1,4}[-)\s]?\d{3,4}\b""") to "[CONTACT]",
        Regex("""\b\d{3}[-.\s]\d{3}[-.\s]\d{4}\b""") to "[CONTACT]",
        // 5. 英語の人名＋敬称・役職
        Regex("""\b(Mr|Mrs|Ms|Dr|Prof)\.?\s+[A-Z][a-z]+""") to "[NAME]",
        Regex("""\b(CEO|CTO|CFO|COO|Director|Manager|President)\s+[A-Z][a-z]+""") to "[NAME]",
        // 6. 日本語の人名＋敬称・役職
        Regex("""[一-龠ァ-ヶー]{1,5}(さん|くん|部長|課長|次長|係長|専務|常務|社長|会長|先生|教授)""") to "[NAME]$1",
        //    「様」は誤爆が多いので、直前が2文字以上の漢字かつ直後が句読点・文末のときだけ
        Regex("""[一-龠]{2,5}様(?=[、。\s]|$)""") to "[NAME]様",
        // 7. 組織名
        Regex("""(株式会社|有限会社|合同会社)[A-Za-z一-龠ァ-ヶー]{1,10}""") to "[ORG]",
        Regex("""[A-Za-z一-龠ァ-ヶー]{2,10}(株式会社|銀行|信用金庫|コーポレーション)""") to "[ORG]",
        Regex("""\b[A-Z][a-zA-Z0-9]+\s+(Inc|LLC|Corp|Corporation|Ltd|GmbH)\b\.?""") to "[ORG]",
        // 8. 通貨つき数値（裸の数値より前）
        Regex("""[¥${'$'}€£]\s?\d[\d,]*(\.\d+)?(\s?([kKmMbB]|万|億|兆))?""") to "[NUM]",
        Regex("""\d[\d,]*(\.\d+)?\s?(万|億|兆|千)?\s?(円|ドル|ユーロ|元|ウォン)""") to "[NUM]",
        // 9. 裸の数値（必ず最後）
        Regex("""\d[\d,]*(\.\d+)?(\s?(万|億|兆|%|割|人|件|個|年|月|日|時|分|秒))?""") to "[NUM]"
    )

    fun sanitize(raw: String): String {
        var text = raw
        for ((regex, replacement) in RULES) {
            text = regex.replace(text, replacement)
        }
        return text
    }
}
