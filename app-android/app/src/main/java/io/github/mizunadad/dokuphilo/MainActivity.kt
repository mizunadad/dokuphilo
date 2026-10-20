package io.github.mizunadad.dokuphilo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.mizunadad.dokuphilo.ui.theme.DokuPhiloTheme
import io.github.mizunadad.dokuphilo.ui.theme.bonnoColors

// DokuPhilo Android 版の画面（1画面 + Study View + 設定）。S1a: テキスト入力と判定。S1b: マイク入力。
// S2a: 設定の画面（Jev のキーと閾値）。S2b: Jev による判定（キーワードで判定できなかった文だけ、マスクして送る）。
// ADR-028: 配置と色（マイクのボタンは下の固定バー、タグは1行、煩悩ごとの色、「テストと手入力」は折りたたみ）。
//
// メインの画面の並び（ADR-028 決定8 と、実機で見たあとの直し。スクロールの中、上から）:
//   タイトルと「設定」／リード文（判定結果がまだないときだけ）／Jev の誤り（あるときだけ）／聞き取りの表示／
//   判定結果（発話、タグ、カード3枚）／Jev の欄／プライバシー表記／「テストと手入力」
//   カード3枚が1画面に収まるよう、上の部分とカードの余白を詰めている
// 下の固定バー（スクロールの外。上から）:
//   マイクのボタンと応答時間／判定の行（flex・bluff・anxious。いちばん新しい入力の結果）／免責／同席者への注意
//   マイクのボタン・応答時間・判定の行は、設定の画面では出さない。免責と同席者への注意は、どの画面でも出す
//
// 安全ルール（CLAUDE.md の Phase 3、ADR-026 決定8、ADR-027、ADR-028）:
//  - 免責表示と同席者への注意を常時表示する（Scaffold の bottomBar。スクロール中も Study View を開いている間も見える）
//  - 辞典の文字列・入力文・発話は Compose の Text で出す。HTML を解釈する表示は使わない
//  - 入力文と発話（途中経過・確定結果）を保存しない。ログにも出さない
//  - 登場賢者とセリフは辞典（assets/dictionary.json）のものだけ。このファイルに賢者名やセリフを書かない
//  - 音声認識は端末内のものだけ。使えない端末では、オンラインに切り替えずに止まる（SpeechSource.kt）
//  - 通信するのは JevClient.kt だけ。送り先は Jev の1か所だけ。キーが未設定なら一切通信しない（ADR-027 決定7）。
//    このファイルは通信しない。ViewModel の状態を出すだけ
//  - Jev に送った文（マスク後）と値は画面に出す。保存しない。ログにも出さない
//  - Jev のキーの文字は、一部でも画面に出さない。入力中の文字は伏せ字にし、保存されるしくみに入れない（ADR-027 決定6）
//  - 色だけで伝えない。色を付ける表示にも、必ず文字を出す。誤りの色（error）は煩悩に使わない（ADR-028 決定16）
//
// 横に並べるタグは WrapRow に入れる。幅が足りなければ次の行へ折り返し、切れない。
// 判定の行は幅を3等分し、文字が大きい端末では枠の中で折り返す（切らない・省略しない）。
//
// 文字の大きさは MaterialTheme.typography のスタイルで決める（fontSize と lineHeight が組になっている）。
// Text に fontSize を直接指定するときは、必ず lineHeight も指定すること（折り返したときに行が重なるため）。

// ---- 画面の文言（index.html と同じ）----
private const val TITLE = "DokuPhilo"
private const val LEAD = "発話に潜む煩悩に、故人の賢者たちがツッコみます。"
private const val DISCLAIMER = "※セリフは思想をもとにした創作であり、本人の発言ではありません"
private const val INPUT_PLACEHOLDER = "発話を入力（例: 会議でシナジーを語った）"
private const val JUDGE_BUTTON = "判定"
private const val NOTICE_NO_BONNO = "煩悩は検知されませんでした。賢者たちは静観しています。"
private const val NO_LINE = "該当セリフなし"
// カードの名前の行の右端（セリフのあるカードだけ。タップで Study View を開く）
private const val EXPLAIN_HINT = "解説 ▸"
// 前の判定が残っているとき（いちばん新しい入力の判定ではないとき）に、発話の行の頭に付ける
private const val PREVIOUS_JUDGEMENT = "前の判定: "
private const val HEADING_PUNCHLINE = "パンチライン"
private const val HEADING_CORE = "核心概念"
private const val HEADING_REASONING = "解説"
private const val HEADING_PRAXIS = "実践的問い（Praxis）"
private const val CLOSE_BUTTON = "閉じる"
private const val LOAD_FAILED = "辞典データを読み込めませんでした"

// ---- タグの文言（ADR-028 決定11）----
private const val TAG_NO_BONNO = "検知なし"
private const val TAG_NO_SITUATION = "場面なし"
private const val TAG_SOURCE_KEYWORD = "キーワード"

// ---- 下の固定バーの文言（判定の行、応答時間）----
private const val JUDGE_KEYWORD = "KW"
private const val JUDGE_NONE = "–"
private const val JUDGE_ASKING = "…"
private const val RESPONSE_LABEL = "応答"

// ---- 「テストと手入力」（折りたたみ。ADR-028 決定8）----
private const val TEST_INPUT_TITLE = "テストと手入力"
private const val TEST_INPUT_CLOSED = "▸ $TEST_INPUT_TITLE"
private const val TEST_INPUT_OPEN = "▾ $TEST_INPUT_TITLE"

// ---- マイクの文言（S1b）----
// プライバシー表記。キーの有無で切り替える（ADR-027 決定9。文言は ADR のとおり）
private const val PRIVACY = "DokuPhilo は入力や音声を送信・保存しません。音声の文字起こしには、端末内の音声認識だけを使います"
private const val PRIVACY_WITH_KEY =
    "音声は端末内で文字にします。キーワードで判定できなかった文は、名前や数字を伏せたうえで Jev（Typesafe 社の API）に送って判定します。音声そのものは送りません。DokuPhilo は入力や音声を保存しませんが、送った文の扱いは Typesafe 社の規約によります。敬称のない人名などは伏せきれないことがあります"
private const val BYSTANDER = "周りの人の声も拾います。使う前に同席者に伝えてください"
private const val MIC_START = "マイクで聞く"
// 聞き取り中は、ボタンの文言と色で示す（ADR-028 決定7）
private const val MIC_STOP = "● 聞き取りを止める"
private const val HEARD = "直前の聞き取り"
private const val MIC_UNAVAILABLE =
    "この端末では端末内の音声認識が使えないため、マイク入力は使えません（オンラインの音声認識には切り替えません）。「$TEST_INPUT_TITLE」を開くと、テキスト入力は使えます"
private const val MIC_DENIED = "マイクの権限がないため、音声入力は使えません。「$TEST_INPUT_TITLE」を開くと、テキスト入力は使えます"

// ---- 設定の文言（S2a）----
private const val SETTINGS = "設定"
private const val KEY_HEADING = "Jev のキー"
private const val KEY_SET = "設定済み"
private const val KEY_UNSET = "未設定"
private const val KEY_PLACEHOLDER = "キーを入力"
private const val KEY_SAVE = "保存"
private const val KEY_DELETE = "削除"
private const val KEY_SAVE_FAILED = "キーを保存できませんでした"
private const val KEY_NOTE = "キーを保存すると、キーワードで判定できなかった文を、名前や数字を伏せたうえで Jev に送ります"
private const val THRESHOLD_HEADING = "Jev の閾値"
private const val THRESHOLD_DESCRIPTION = "Jev の値がこの閾値以上のときに、煩悩ありとします"
private const val THRESHOLD_DOWN = "−"
private const val THRESHOLD_UP = "＋"

// ---- Jev の文言（S2b）----
private const val JEV_NO_KEY = "Jev: キー未設定（キーワードだけで判定しています）"
private const val JEV_READY = "Jev: キーワードで判定できなかった文だけ、Jev に送ります"
private const val JEV_ASKING = "Jev: 問い合わせ中…"
private const val JEV_NETWORK_ERROR = "Jev: 通信できませんでした。次の入力でまた試します"
private const val JEV_UNEXPECTED = "Jev: 想定外の応答でした。次の入力でまた試します"
private const val JEV_BAD_KEY = "Jev: キーが正しくないようです。キーを入れ直すかアプリを再起動するまで、Jev には送りません"
private const val JEV_NO_CREDIT = "Jev: 残高が足りないようです。キーを入れ直すかアプリを再起動するまで、Jev には送りません"

private fun jevHttpErrorText(code: Int): String =
    "Jev: エラーが返りました（HTTP $code）。次の入力でまた試します"

private fun jevStatusText(status: JevStatus): String = when (status) {
    JevStatus.NoKey -> JEV_NO_KEY
    JevStatus.Ready -> JEV_READY
    JevStatus.Asking -> JEV_ASKING
    JevStatus.NetworkError -> JEV_NETWORK_ERROR
    JevStatus.Unexpected -> JEV_UNEXPECTED
    is JevStatus.HttpError -> jevHttpErrorText(status.code)
    JevStatus.BadKey -> JEV_BAD_KEY
    JevStatus.NoCredit -> JEV_NO_CREDIT
}

/** 誤りの5つ（通信できなかった・想定外の応答・その他のエラー・キーの誤り・残高切れ）は、エラーの色で、画面の先頭に出す */
private fun jevStatusIsError(status: JevStatus): Boolean = when (status) {
    JevStatus.NoKey, JevStatus.Ready, JevStatus.Asking -> false
    JevStatus.NetworkError, JevStatus.Unexpected, is JevStatus.HttpError,
    JevStatus.BadKey, JevStatus.NoCredit -> true
}

/** マスク後の文は、ここで1回だけ「」を付ける */
private fun jevSentTextLine(sanitized: String): String = "Jev に送った文: 「$sanitized」"

/** Jev の値の詳しい1行（Jev の欄）。届かなかった値も出す（ADR-027 決定3）。閾値は、その結果に使った値 */
private fun jevReadingLine(reading: JevReading, dictionary: Dictionary?): String {
    val name = bonnoDisplayName(reading.bonno, dictionary)
    val value = formatJevValue(reading.value)
    val threshold = formatThreshold(reading.thresholdPercent)
    return if (reading.met) {
        "Jev の値（$name）: $value（閾値 $threshold 以上）"
    } else {
        "Jev の値（$name）: $value（閾値 $threshold 未満）"
    }
}

/** 判定の行の1つ分。highlighted は、煩悩ありとした煩悩（煩悩の色の塗りと太字にする） */
private data class JudgeCell(val bonno: String, val text: String, val highlighted: Boolean)

/**
 * 下の固定バーの判定の行の中身。いちばん新しい入力の結果を、Jev で判定する煩悩の順（flex・bluff・anxious）に並べる。
 * - Jev の値があるとき: 「flex 0.123」（煩悩のキーと値）。閾値に届いたものを目立たせる
 * - Jev に問い合わせ中: 「flex …」
 * - キーワードで煩悩ありのとき（keywordBonno。Jev には送っていない）: 当たった煩悩は「flex KW」、ほかは「bluff –」
 * - それ以外（まだ入力がない、値がない）: 「flex –」
 */
private fun judgeCells(
    readings: List<JevReading>,
    asking: Boolean,
    keywordBonno: String?,
): List<JudgeCell> = JEV_QUESTIONS.keys.map { bonno ->
    val reading = readings.firstOrNull { it.bonno == bonno }
    when {
        reading != null -> JudgeCell(bonno, "$bonno ${formatJevValue(reading.value)}", reading.met)
        readings.isNotEmpty() -> JudgeCell(bonno, "$bonno $JUDGE_NONE", false)
        asking -> JudgeCell(bonno, "$bonno $JUDGE_ASKING", false)
        bonno == keywordBonno -> JudgeCell(bonno, "$bonno $JUDGE_KEYWORD", true)
        else -> JudgeCell(bonno, "$bonno $JUDGE_NONE", false)
    }
}

/**
 * 応答時間の文字。秒で小数2桁、切り捨て、末尾に s（340 → 0.34s、5 → 0.00s、5012 → 5.01s）。
 * 端末の言語設定で小数点が変わらないよう、自分で組み立てる（formatThreshold と同じ考え方）。
 */
private fun formatResponseTime(millis: Long): String {
    val ms = millis.coerceAtLeast(0L)
    return "${ms / 1000}.${((ms % 1000) / 10).toString().padStart(2, '0')}s"
}

/** 応答時間の表示。値がないときは「–」、Jev に問い合わせ中は「…」 */
private fun responseTimeText(millis: Long?, asking: Boolean): String = when {
    millis != null -> formatResponseTime(millis)
    asking -> JUDGE_ASKING
    else -> JUDGE_NONE
}

/** 出どころのタグ（Jev のとき）。値つき（例: Jev 0.862） */
private fun jevSourceTagText(reading: JevReading): String = "Jev ${formatJevValue(reading.value)}"

/** MIC_ERROR。code は SpeechRecognizer のエラー番号 */
private fun micErrorText(code: Int): String =
    "音声認識が止まりました（エラー $code）。もう一度「$MIC_START」を押してください"

private fun micMessageText(message: MicMessage): String = when (message) {
    MicMessage.Unavailable -> MIC_UNAVAILABLE
    MicMessage.Denied -> MIC_DENIED
    is MicMessage.Error -> micErrorText(message.code)
}

// テストボタン（煩悩ごとに1つ＋煩悩なし1つ）。index.html の TEST_CASES と同じ
private data class TestCase(val label: String, val text: String)

private val TEST_CASES = listOf(
    TestCase("自己顕示（flex）", "昨日も社長とサウナで、新規事業のアドバイスを求められちゃってさ"),
    TestCase("知ったかぶり（bluff）", "定義も曖昧なまま、会議でWeb3とシナジーの重要性を語った"),
    TestCase("過剰迎合（anxious）", "嫌われたくなくて、相手の意見に全部笑顔で同調しちゃった"),
    TestCase("煩悩なし", "今日はいい天気なので、散歩してから本を読みます"),
)

class MainActivity : ComponentActivity() {

    private val vm: DokuPhiloViewModel by viewModels()

    // マイクの権限の要求は、マイクのボタンを押したときだけ行う（起動時には要求しない）。
    // 拒否されたら、落とさずに知らせる
    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startListening() else vm.onMicPermissionDenied() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DokuPhiloTheme {
                DokuPhiloApp(
                    vm = vm,
                    lifecycle = lifecycle,
                    onMicClick = { onMicClick() },
                )
            }
        }
    }

    /** 画面を離れたら聞き取りを止める。戻っても自動では再開しない。回転では止めない */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.stopListening()
    }

    /** マイクのボタン。聞き取り中なら止める。止まっていれば、権限を確かめてから始める */
    private fun onMicClick() {
        if (vm.listening) {
            vm.stopListening()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) vm.startListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/**
 * 聞き取り中だけ画面を消さない。止めたら必ず戻す。
 *
 * ref/egoguard/MainActivity.kt の KeepScreenOn と同じ考え方（LocalView に付ける。ON_STOP で落とす）。
 * 違い: Lifecycle は Activity から受け取る（部品を足さないため）。
 * 画面を離れると聞き取りは止まり、戻っても再開しないので、ON_START では点け直さない。
 * 回転では Activity と View が作り直され、新しい View に付け直される。
 */
@Composable
private fun KeepScreenOn(enabled: Boolean, lifecycle: Lifecycle) {
    val view = LocalView.current
    DisposableEffect(view, enabled, lifecycle) {
        view.keepScreenOn = enabled
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) view.keepScreenOn = false
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = false
        }
    }
}

@Composable
fun DokuPhiloApp(
    vm: DokuPhiloViewModel,
    lifecycle: Lifecycle,
    onMicClick: () -> Unit,
) {
    KeepScreenOn(enabled = vm.listening, lifecycle = lifecycle)

    val study = vm.study
    val studyLine = study?.line
    // Study View から戻ったときに、元のスクロール位置に戻れるよう、ここで持つ
    val mainScroll = rememberScrollState()

    // 判定結果を置き換えたら、メインの画面を1回だけ先頭へ動かす（ADR-028 決定13）。
    // きっかけは ViewModel の「置き換えた回数」。済ませた回数はここで覚える（起動や回転の直後は、今の回数を済みとする）。
    // Study View や設定から戻っただけ、回転しただけでは動かない。Study View を開いている間に置き換わったときは、
    // その場で先頭にしておく（戻ったときに先頭になっている）
    val judgementVersion = vm.judgementVersion
    val scrolledVersion = remember { intArrayOf(judgementVersion) }
    LaunchedEffect(judgementVersion) {
        if (scrolledVersion[0] != judgementVersion) {
            scrolledVersion[0] = judgementVersion
            mainScroll.scrollTo(0)
        }
    }

    // 端末の戻る操作で、設定または Study View を閉じる
    BackHandler(enabled = vm.settingsOpen || study != null) {
        if (vm.settingsOpen) vm.closeSettings() else vm.closeStudy()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        bottomBar = {
            val asking = vm.jevStatus == JevStatus.Asking
            // 画面の判定結果が、いちばん新しい入力のもので、出どころがキーワードのとき（Jev には送っていない）
            val keywordBonno = if (!vm.judgementStale && vm.judgementSource == JudgementSource.Keyword) {
                vm.judgement?.bonno
            } else {
                null
            }
            BottomBar(
                // 設定を開いている間は聞き取らないので、マイクのボタン・応答時間・判定の行は出さない
                showControls = !vm.settingsOpen,
                listening = vm.listening,
                // 辞典を読めなかったときは無効
                micEnabled = vm.dictionary != null,
                onMicClick = onMicClick,
                responseText = responseTimeText(vm.responseMillis, asking),
                judgeCells = judgeCells(vm.jevReadings, asking, keywordBonno),
            )
        },
    ) { innerPadding ->
        // 設定と Study View は同じ Scaffold の中で切り替える（免責と同席者への注意が見えたままになる）
        if (vm.settingsOpen) {
            SettingsView(
                vm = vm,
                onClose = { vm.closeSettings() },
                modifier = Modifier.padding(innerPadding),
            )
        } else if (study != null && studyLine != null) {
            StudyView(
                sage = study.sage,
                line = studyLine,
                onClose = { vm.closeStudy() },
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            MainScreen(
                vm = vm,
                scrollState = mainScroll,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/** 表にない煩悩のキー。bonnoColors に渡すと、中立の色（灰色）が返る。判定の行の、目立たせない枠に使う */
private const val NEUTRAL_COLOR_KEY = ""

/** 応答時間の表示の最小の幅。値が変わっても、マイクのボタンの幅が動かないようにする */
private val RESPONSE_MIN_WIDTH = 56.dp

/**
 * 下の固定バー（スクロールの外。ADR-028 決定7 と、実機で見たあとの直し）。
 * 上から: マイクのボタンと応答時間、判定の行（この2行は、メインの画面と Study View で出す）、
 * 免責、同席者への注意（どの画面でも出す。CLAUDE.md 安全ルール2、Phase 3 のルール）。
 */
@Composable
private fun BottomBar(
    showControls: Boolean,
    listening: Boolean,
    micEnabled: Boolean,
    onMicClick: () -> Unit,
    responseText: String,
    judgeCells: List<JudgeCell>,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showControls) {
                // マイクのボタンが残りの幅を取り、右に応答時間を置く
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MicButton(
                        listening = listening,
                        enabled = micEnabled,
                        onClick = onMicClick,
                        modifier = Modifier.weight(1f),
                    )
                    ResponseTime(text = responseText)
                }
                JudgeRow(cells = judgeCells)
            }
            NoticeLine(DISCLAIMER)
            NoticeLine(BYSTANDER)
        }
    }
}

/**
 * 免責と同席者への注意の1行。常時表示する。文言は変えない。
 * 幅360dpで1行に収まるよう、文字を小さくしている（fontSize を直接指定するので、lineHeight も指定する）。
 * 収まらない端末では折り返す（切らない・省略しない）。
 */
@Composable
private fun NoticeLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * マイクのボタン。聞き取り中かどうかを、文言と塗りの色で示す（色だけにしない）。
 * 止まっているときは既定の色（primary）、聞き取り中は tertiary。煩悩の3色と誤りの色は使わない。
 */
@Composable
private fun MicButton(
    listening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = if (listening) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text = if (listening) MIC_STOP else MIC_START, textAlign = TextAlign.Center)
    }
}

/**
 * 応答時間（マイクのボタンの右）。2行で「応答」と値。
 * 文字起こしの確定（または手入力の「判定」）から、判定が決まるまでの時間。持つのは直近の1つだけ。
 */
@Composable
private fun ResponseTime(text: String) {
    Column(
        modifier = Modifier.widthIn(min = RESPONSE_MIN_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = RESPONSE_LABEL,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 判定の行。flex・bluff・anxious の3つを、幅を3等分して並べる。常に3つとも出す。
 * 煩悩ありとしたものは煩悩の色の塗りと太字、それ以外は灰色の塗り（色のほかに、文字と太字でも分かる）。
 * 文字が大きい端末では、枠の中で折り返す（切らない・省略しない）。
 */
@Composable
private fun JudgeRow(cells: List<JudgeCell>) {
    val dark = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (cell in cells) {
            val colors = bonnoColors(if (cell.highlighted) cell.bonno else NEUTRAL_COLOR_KEY, dark)
            Surface(
                color = colors.container,
                contentColor = colors.onContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = cell.text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (cell.highlighted) FontWeight.Bold else null,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    vm: DokuPhiloViewModel,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val dictionary = vm.dictionary
    val loaded = dictionary != null
    val judgement = vm.judgement
    val jevStatus = vm.jevStatus
    val jevError = jevStatusIsError(jevStatus)
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    // 直前の聞き取りは、表示中の判定結果の発話と同じなら出さない（同じ文が2回並ぶため）。
    // 違うとき（判定結果がまだない、前の判定が残っている、歯止めで判定しなかった）は出す
    val heardToShow = vm.lastHeard?.takeUnless { judgement != null && it.trim() == judgement.raw }

    // 判定するときは、キーボードを閉じてから結果を出す
    val runJudge: (String) -> Unit = { text ->
        focusManager.clearFocus()
        vm.judgeText(text)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. タイトルの行。右端に「設定」（タイトルが残りの幅を取るので、幅360dpでもはみ出さない）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TITLE,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.openSettings() }) {
                Text(SETTINGS)
            }
        }

        // 2. リード文は、まだ判定結果がないときだけ
        if (judgement == null) {
            Text(text = LEAD, style = MaterialTheme.typography.bodyMedium)
        }

        // 3. 辞典を読めなかったときは、落とさずに知らせ、判定しない
        if (!loaded) {
            Text(
                text = LOAD_FAILED,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // 4. Jev の誤りは、状態の行を先頭に出す（黙って切り替えない。ADR-027 決定8、ADR-028 決定10）
        if (jevError) {
            Text(
                text = jevStatusText(jevStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // 5. 聞き取りの表示（途中経過、直前の聞き取り、マイクのメッセージ）
        if (vm.partial.isNotEmpty() || heardToShow != null || vm.micMessage != null) {
            HeardSection(partial = vm.partial, lastHeard = heardToShow, message = vm.micMessage)
        }

        // 6. 判定結果（発話、タグ、カード3枚）。Jev の値の短い表示は、下の固定バーの判定の行に出す
        if (dictionary != null && judgement != null) {
            ResultSection(
                judgement = judgement,
                source = vm.judgementSource,
                stale = vm.judgementStale,
                dictionary = dictionary,
                onOpenStudy = { vm.openStudy(it) },
            )
        }

        // 7. Jev の欄（誤りでないときの状態の行、送った文、値と閾値の詳しい行）
        if (!jevError || vm.jevSentText != null || vm.jevReadings.isNotEmpty()) {
            JevSection(vm = vm, showStatus = !jevError)
        }

        // 8. プライバシー表記（常に出す）。キーがあるときは、Jev に送ることを書いた表記に切り替える
        Text(
            text = if (vm.keyConfigured) PRIVACY_WITH_KEY else PRIVACY,
            style = MaterialTheme.typography.bodySmall,
            color = faint,
        )

        // 9. 「テストと手入力」（折りたたみ。既定は閉じる）
        TestInputSection(vm = vm, enabled = loaded, onJudge = runJudge)
    }
}

/**
 * 聞き取りの表示。上から順に: 途中経過（薄い色）、直前の確定結果（薄い色）、マイクのメッセージ。
 * 発話は Text で出すだけ。保存しない。聞き取り中かどうかは、下の固定バーのマイクのボタンが示す。
 * lastHeard は、出すときだけ渡す（表示中の判定結果の発話と同じときは null）。
 */
@Composable
private fun HeardSection(partial: String, lastHeard: String?, message: MicMessage?) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (partial.isNotEmpty()) {
            Text(text = partial, style = MaterialTheme.typography.bodyMedium, color = faint)
        }
        if (lastHeard != null) {
            Text(
                text = "$HEARD: $lastHeard",
                style = MaterialTheme.typography.bodyMedium,
                color = faint,
            )
        }
        if (message != null) {
            Text(
                text = micMessageText(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Jev の欄（判定結果の下）。
 * 上から順に: 状態の行（誤りでないときだけ。誤りのときは画面の先頭に出す）、今の入力について Jev に送った文
 * （マスク後。薄い色）、Jev の値と閾値の詳しい行（煩悩ごとに1行。薄い色）。
 * どれも Text で出すだけ。保存しない。
 */
@Composable
private fun JevSection(vm: DokuPhiloViewModel, showStatus: Boolean) {
    val sentText = vm.jevSentText
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showStatus) {
            Text(text = jevStatusText(vm.jevStatus), style = MaterialTheme.typography.bodyMedium)
        }
        if (sentText != null) {
            Text(
                text = jevSentTextLine(sentText),
                style = MaterialTheme.typography.bodyMedium,
                color = faint,
            )
        }
        for (reading in vm.jevReadings) {
            Text(
                text = jevReadingLine(reading, vm.dictionary),
                style = MaterialTheme.typography.bodyMedium,
                color = faint,
            )
        }
    }
}

/**
 * 「テストと手入力」。見出しの行を押すと開閉する。既定は閉じる。
 * 開閉は ViewModel が持つ（Study View や設定から戻っても、回転しても変わらない。端末には残さない）。
 * 中身は、テストボタン4つ・入力欄・「判定」のボタン（動きは S1a のまま）。
 */
@Composable
private fun TestInputSection(
    vm: DokuPhiloViewModel,
    enabled: Boolean,
    onJudge: (String) -> Unit,
) {
    val open = vm.testInputOpen

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (open) TEST_INPUT_OPEN else TEST_INPUT_CLOSED,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { vm.toggleTestInput() }
                .padding(vertical = 14.dp),
        )
        if (open) {
            TestButtons(enabled = enabled, onPick = onJudge)

            OutlinedTextField(
                value = vm.input,
                onValueChange = { vm.onInputChange(it) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                placeholder = {
                    Text(
                        text = INPUT_PLACEHOLDER,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onJudge(vm.input) }),
            )
            Button(
                onClick = { onJudge(vm.input) },
                enabled = enabled,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(JUDGE_BUTTON)
            }
        }
    }
}

/**
 * 子を横に並べ、幅が足りなければ次の行へ折り返す（幅360dpや大きい文字でも、切らずに折り返す）。
 * タグに使う。部品を足さず、安定版の Layout だけで書いている。
 */
@Composable
private fun WrapRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val maxWidth = constraints.maxWidth
        // 子は、自分の幅で測る（行の幅より広い子は、行の幅の中で折り返す）
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }

        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var x = 0
        var y = 0
        var rowHeight = 0
        var usedWidth = 0
        placeables.forEachIndexed { index, placeable ->
            // 行の先頭でなく、入りきらないときは、次の行へ
            if (x > 0 && x + placeable.width > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            xs[index] = x
            ys[index] = y
            x += placeable.width
            if (x > usedWidth) usedWidth = x
            x += gap
            if (placeable.height > rowHeight) rowHeight = placeable.height
        }
        val width = usedWidth.coerceIn(constraints.minWidth, maxWidth)
        val height = (y + rowHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(xs[index], ys[index])
            }
        }
    }
}

/** テストボタン4つを2列×2段に並べる。幅を等分するので、幅360dpでもはみ出さない（長いラベルは折り返す） */
@Composable
private fun TestButtons(enabled: Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TEST_CASES.chunked(2).forEach { rowCases ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowCases.forEach { testCase ->
                    OutlinedButton(
                        onClick = { onPick(testCase.text) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(text = testCase.label, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** 前の判定が残っているときの、判定結果のかたまりの薄さ */
private const val STALE_ALPHA = 0.45f

/**
 * 判定結果のかたまり（発話、タグ、カード3枚）。
 * stale は、この判定結果がいちばん新しい入力のものではないとき（音声で煩悩なしで、カードを変えなかったとき）。
 * そのときは、かたまりを薄くし、発話の行の頭に「前の判定: 」を付ける（薄さだけにしない）。カードのタップは使えるまま。
 */
@Composable
private fun ResultSection(
    judgement: Judgement,
    source: JudgementSource?,
    stale: Boolean,
    dictionary: Dictionary,
    onOpenStudy: (SageCard) -> Unit,
) {
    val bonno = judgement.bonno
    val situation = judgement.situation
    val dark = isSystemInDarkTheme()

    Column(
        modifier = if (stale) Modifier.alpha(STALE_ALPHA) else Modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 入力文は毎回ここで1回だけ「」を付ける。長い文は2行まで
        Text(
            text = (if (stale) PREVIOUS_JUDGEMENT else "") + "「${judgement.raw}」",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // タグは短くして1行に並べる（ADR-028 決定11）: 煩悩、シチュエーション、出どころ
        WrapRow {
            // 煩悩: 辞典の label。煩悩の色の塗り。煩悩なしは枠だけ
            if (bonno != null) {
                val colors = bonnoColors(bonno, dark)
                Tag(text = bonnoLabel(bonno, dictionary), container = colors.container, content = colors.onContainer)
            } else {
                Tag(text = TAG_NO_BONNO)
            }
            // シチュエーション: 辞典の label。判定できなかったときも、タグでそう示す（説明の行は出さない）。枠だけ
            Tag(text = situationLabel(situation, dictionary))
            // 出どころは、カードを出すとき（煩悩あり）だけ。色を増やさずに分ける: Jev は塗り、キーワードは枠だけ（決定15）
            if (bonno != null && source != null) {
                when (source) {
                    JudgementSource.Keyword -> Tag(text = TAG_SOURCE_KEYWORD)
                    is JudgementSource.Jev -> Tag(
                        text = jevSourceTagText(source.reading),
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        if (bonno == null) {
            // 煩悩に当たらなければカードを出さない。別の煩悩のセリフで代用しない
            Text(text = NOTICE_NO_BONNO, style = MaterialTheme.typography.bodyMedium)
        } else {
            // 3賢者のカードを縦に積む。左の帯は煩悩の色（3枚とも同じ色）
            val stripe = bonnoColors(bonno, dark).container
            judgement.cards.forEach { card ->
                SageCardView(card = card, stripe = stripe, onOpenStudy = onOpenStudy)
            }
        }
    }
}

/** 煩悩のタグの文字（辞典の label。例: 自己顕示）。辞典にないキーなら、キーをそのまま出す */
private fun bonnoLabel(bonno: String, dictionary: Dictionary): String =
    dictionary.bonnos[bonno]?.label ?: bonno

/** シチュエーションのタグの文字（辞典の label。例: ビジネス）。判定できなかったときは「場面なし」 */
private fun situationLabel(situation: String?, dictionary: Dictionary): String =
    if (situation == null) TAG_NO_SITUATION else dictionary.situations[situation]?.label ?: situation

/**
 * タグ。container と content を渡すと塗り、渡さなければ枠だけ。
 * 色のほかに、文字を必ず出す。太字は、Jev の値の短い表示で、閾値に届いたものに使う。
 */
@Composable
private fun Tag(
    text: String,
    container: Color? = null,
    content: Color? = null,
    bold: Boolean = false,
) {
    val fontWeight = if (bold) FontWeight.Bold else null
    if (container != null && content != null) {
        Surface(
            color = container,
            contentColor = content,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = fontWeight,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    } else {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = fontWeight,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 画面でも外側を『』で囲む（ADR-013） */
private fun quotePunchline(line: Line): String = "『${line.punchline}』"

private fun sageHeading(sage: Sage): String = "${sage.title}：${sage.name}"

/** カードの左の帯の幅 */
private val CARD_STRIPE_WIDTH = 6.dp

/** カードの中の余白（左は、これに帯の幅を足す） */
private val CARD_PADDING = 12.dp

/**
 * 賢者のカード。セリフがあればタップで Study View を開く。なければ「該当セリフなし」でタップ不可。
 * 左に、煩悩の色の帯を付ける（ADR-028 決定14。煩悩はタグの文字でも分かる）。
 * 3枚が1画面に収まるよう、名前の行とセリフだけにしている（生没年は Study View に出す）。セリフは省略しない。
 */
@Composable
private fun SageCardView(card: SageCard, stripe: Color, onOpenStudy: (SageCard) -> Unit) {
    val line = card.line
    val shape = MaterialTheme.shapes.medium
    OutlinedCard(
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = line != null, role = Role.Button) { onOpenStudy(card) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 左の帯は、カードの中身の後ろに描く（中身は帯の幅だけ右へ寄せる）
                .drawBehind {
                    drawRect(color = stripe, size = Size(CARD_STRIPE_WIDTH.toPx(), size.height))
                }
                .padding(
                    start = CARD_PADDING + CARD_STRIPE_WIDTH,
                    top = CARD_PADDING,
                    end = CARD_PADDING,
                    bottom = CARD_PADDING,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 名前の行: 左に「肩書き：名前」、右端に「解説 ▸」（セリフのあるカードだけ）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sageHeading(card.sage),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (line != null) {
                    Text(
                        text = EXPLAIN_HINT,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (line == null) {
                Text(
                    text = NO_LINE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(text = quotePunchline(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Study View。Dialog や別の Activity にせず、同じ Scaffold の中に出す */
@Composable
private fun StudyView(
    sage: Sage,
    line: Line,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = sageHeading(sage), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = sage.lifespan,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StudySection(heading = HEADING_PUNCHLINE, body = quotePunchline(line))
        StudySection(heading = HEADING_CORE, body = line.study.core)
        StudySection(heading = HEADING_REASONING, body = line.study.reasoning)
        StudySection(heading = HEADING_PRAXIS, body = line.study.praxis)
        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(CLOSE_BUTTON)
        }
    }
}

@Composable
private fun StudySection(heading: String, body: String) {
    Text(
        text = heading,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(text = body, style = MaterialTheme.typography.bodyLarge)
}

/**
 * 設定の画面（S2a）。Study View と同じく、Dialog や別の Activity にせず、同じ Scaffold の中に出す。
 *
 * 入力中のキーは、この画面の中の remember にだけ置く（ViewModel にも、保存されるしくみにも入れない）。
 * 画面を閉じる・回転すると消える。保存・削除でも消す。
 */
@Composable
private fun SettingsView(
    vm: DokuPhiloViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var keyInput by remember { mutableStateOf("") }
    var saveFailed by remember { mutableStateOf(false) }
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = SETTINGS, style = MaterialTheme.typography.headlineSmall)

        // ---- Jev のキー ----
        SettingsHeading(KEY_HEADING)
        // 状態だけを出す。キーの文字は一部でも出さない
        Text(
            text = if (vm.keyConfigured) KEY_SET else KEY_UNSET,
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = {
                keyInput = it
                saveFailed = false
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(
                    text = KEY_PLACEHOLDER,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (vm.saveApiKey(keyInput)) {
                        keyInput = ""
                        saveFailed = false
                    } else {
                        saveFailed = true
                    }
                },
                // 空（空白だけ）なら押せない
                enabled = keyInput.trim().isNotEmpty(),
            ) {
                Text(KEY_SAVE)
            }
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    vm.deleteApiKey()
                    keyInput = ""
                    saveFailed = false
                },
                // 未設定のときは押せない
                enabled = vm.keyConfigured,
            ) {
                Text(KEY_DELETE)
            }
        }
        if (saveFailed) {
            Text(
                text = KEY_SAVE_FAILED,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(text = KEY_NOTE, style = MaterialTheme.typography.bodySmall, color = faint)

        // ---- Jev の閾値 ----
        SettingsHeading(THRESHOLD_HEADING)
        Text(text = THRESHOLD_DESCRIPTION, style = MaterialTheme.typography.bodyMedium)
        for ((bonno, percent) in vm.thresholds) {
            ThresholdRow(
                label = bonnoDisplayName(bonno, vm.dictionary),
                percent = percent,
                canDown = canLowerThreshold(bonno, percent),
                canUp = canRaiseThreshold(bonno, percent),
                onDown = { vm.onThresholdDown(bonno) },
                onUp = { vm.onThresholdUp(bonno) },
            )
        }

        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(CLOSE_BUTTON)
        }
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 煩悩の表示名（辞典の label とキー。例: 自己顕示（flex））。辞典を読めなかったときはキーだけ */
private fun bonnoDisplayName(bonno: String, dictionary: Dictionary?): String {
    val label = dictionary?.bonnos?.get(bonno)?.label
    return if (label != null) "$label（$bonno）" else bonno
}

/**
 * 閾値の1行: 煩悩の表示名、今の値、「−」「＋」。端では押せない。
 * 表示名が残りの幅を取り、長ければ折り返す（幅360dpでもはみ出さない）。
 */
@Composable
private fun ThresholdRow(
    label: String,
    percent: Int,
    canDown: Boolean,
    canUp: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(text = formatThreshold(percent), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = onDown,
            enabled = canDown,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(THRESHOLD_DOWN)
        }
        OutlinedButton(
            onClick = onUp,
            enabled = canUp,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(THRESHOLD_UP)
        }
    }
}
