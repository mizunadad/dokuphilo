package io.github.mizunadad.dokuphilo

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** マイクについて画面に出すメッセージの種類。文言は MainActivity.kt に置く */
sealed interface MicMessage {
    /** 端末内の音声認識が使えない端末（オンラインには切り替えない） */
    data object Unavailable : MicMessage

    /** マイクの権限がない */
    data object Denied : MicMessage

    /** 音声認識が止まった。code は SpeechRecognizer のエラー番号 */
    data class Error(val code: Int) : MicMessage
}

/** Jev の状態。画面に1つ出す（ADR-027 決定8）。文言は MainActivity.kt に置く */
sealed interface JevStatus {
    /** キー未設定。キーワードだけで判定する。一切通信しない */
    data object NoKey : JevStatus

    /** 使える。キーワードで判定できなかった文だけ送る */
    data object Ready : JevStatus

    /** 問い合わせ中 */
    data object Asking : JevStatus

    /** 通信できなかった（タイムアウトを含む）。次の入力でまた試す */
    data object NetworkError : JevStatus

    /** 想定外の応答。次の入力でまた試す */
    data object Unexpected : JevStatus

    /** その他のエラー（コードつき）。次の入力でまた試す */
    data class HttpError(val code: Int) : JevStatus

    /** キーの誤り。キーを入れ直すかアプリを再起動するまで送らない */
    data object BadKey : JevStatus

    /** 残高切れ。キーを入れ直すかアプリを再起動するまで送らない */
    data object NoCredit : JevStatus
}

/**
 * 画面の状態を持つ（回転しても消えない）。
 *
 * 入力文・発話（途中経過と確定結果）・判定結果・Jev に送った文（マスク後）・Jev の値と状態は、
 * このメモリ上の状態にだけ置く。保存しない（ADR-026 決定8）。
 * 保存されるしくみ（画面状態の保存、設定、ファイル）には入れず、ログにも出さない。
 * アプリが終了すれば消える。
 *
 * 端末に残すのは、Jev のキー（SecureKeyStore.kt。暗号文だけ）と Jev の閾値（ThresholdStore.kt）の2つだけ（ADR-027）。
 * キーの文字列は、ここでは状態として持たない。持つのは「設定済みかどうか」だけ。
 * 送るときは、その送信の間だけローカル変数に読む。
 *
 * 判定の流れ（ADR-027 決定1）: 元の文でキーワード判定をする。煩悩ありならそのまま表示し、Jev に送らない。
 * 煩悩なしで、キーが設定済みで、送信が止まっていないときだけ、マスクした文を Jev に送る。
 * Jev がどう失敗しても、キーワード判定とマイクは止めない。
 */
class DokuPhiloViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** 音声認識の言語。固定（ADR-024 決定7） */
        const val LANGUAGE_TAG = "ja-JP"
    }

    /** 起動時に1回だけ読む。読めなければ null（画面にその旨を出し、判定しない） */
    val dictionary: Dictionary? = loadDictionary(application)

    /** テキスト欄の中身 */
    var input by mutableStateOf("")
        private set

    /** 直近の判定結果。まだ判定していなければ null */
    var judgement by mutableStateOf<Judgement?>(null)
        private set

    /** 表示中の判定結果の出どころ（キーワードか、Jev とその値か）。煩悩なしの結果では null */
    var judgementSource by mutableStateOf<JudgementSource?>(null)
        private set

    // ---- 表示のための状態（ADR-028 決定8・13）。どちらもメモリ上だけ。端末には残さない ----

    /**
     * 判定結果を置き換えた回数。画面は、これが変わったときに、メインの画面を先頭へスクロールする。
     * 判定結果を変えないとき（音声で煩悩なし、Jev が閾値未満）は進めない。
     */
    var judgementVersion by mutableStateOf(0)
        private set

    /** 「テストと手入力」を開いているか。既定は閉じる（アプリを終了すると、閉じた状態に戻る） */
    var testInputOpen by mutableStateOf(false)
        private set

    /**
     * 画面の判定結果が、いちばん新しい入力のものではない（前の判定が残っている）か。
     * 音声の入力がキーワードで煩悩なしで、表示中の判定結果を変えなかったときに真になる。
     * 判定結果を置き換えると偽に戻る。判定結果がまだないときは偽のまま。
     */
    var judgementStale by mutableStateOf(false)
        private set

    /**
     * 直近の入力の応答時間（ミリ秒）。文字起こしの確定（または手入力の「判定」）から、判定が決まるまで。
     * まだないときと、Jev に問い合わせている間は null。持つのは直近の1つだけ（履歴は持たない）。
     */
    var responseMillis by mutableStateOf<Long?>(null)
        private set

    /** Study View で開いているカード。閉じていれば null */
    var study by mutableStateOf<SageCard?>(null)
        private set

    // ---- マイク（S1b）。どれもメモリ上だけ ----

    /** 聞き取り中か。既定は OFF */
    var listening by mutableStateOf(false)
        private set

    /** 途中経過。画面に薄く出すだけで、判定しない */
    var partial by mutableStateOf("")
        private set

    /** 直前の確定結果。まだなければ null */
    var lastHeard by mutableStateOf<String?>(null)
        private set

    /** マイクのメッセージ。なければ null */
    var micMessage by mutableStateOf<MicMessage?>(null)
        private set

    /** 端末内の音声認識だけを使う。使えない端末では SpeechEvent.Unavailable が来る（オンラインに切り替えない） */
    private val speech = SpeechSource(application.applicationContext, requireOnDevice = true)
    private var speechJob: Job? = null

    /** 音声の歯止めを直前に通った文（同じ文を続けて判定しないため） */
    private var lastAccepted: String? = null

    // ---- 設定（S2a） ----

    /** 設定の画面を開いているか（回転しても消えない） */
    var settingsOpen by mutableStateOf(false)
        private set

    /** Jev のキーが設定済みか。キーの文字列そのものは持たない（読んで、空かどうかだけを見て捨てる） */
    var keyConfigured by mutableStateOf(SecureKeyStore.load(application).isNotEmpty())
        private set

    /** Jev の閾値（百分率。0.80 なら 80）。煩悩のキー → 値。Jev で判定する煩悩（JEV_QUESTIONS）の分だけ持つ */
    var thresholds by mutableStateOf(
        JEV_QUESTIONS.keys.associateWith { bonno -> ThresholdStore.load(application, bonno) }
    )
        private set

    // ---- Jev（S2b）。どれもメモリ上だけ ----

    /** Jev の状態。起動時は、キーがあれば「使える」、なければ「キー未設定」（アプリを再起動すると、ここに戻る） */
    var jevStatus by mutableStateOf<JevStatus>(if (keyConfigured) JevStatus.Ready else JevStatus.NoKey)
        private set

    /** 今の入力について Jev に送った文（マスク後）。送っていなければ null */
    var jevSentText by mutableStateOf<String?>(null)
        private set

    /** 今の入力について Jev から読めた値（煩悩ごと。使った閾値つき）。読めていなければ空 */
    var jevReadings by mutableStateOf<List<JevReading>>(emptyList())
        private set

    /** 通信するのはこれだけ（JevClient.kt） */
    private val jevClient = JevClient()
    private var jevJob: Job? = null

    /** 判定の番号。入力を判定するたびに進める。返事が来たときに変わっていたら、その返事は捨てる（追い越し） */
    private var judgeSeq = 0L

    fun onInputChange(value: String) {
        input = value
    }

    /**
     * テキスト入力とテストボタンの判定。毎回、判定結果を置き換え、Study View を閉じる。
     * 空（空白だけ）の入力と、辞典を読めなかったときは何もしない。
     * 煩悩なしのときは、送れる状態なら Jev に送る。
     */
    fun judgeText(text: String) {
        // 応答時間の始点: 手入力・テストボタンは、ここに入った時点
        val startedAt = now()
        val loaded = dictionary ?: return
        val result = judge(text, loaded) ?: return
        val seq = beginJudgement()
        showJudgement(result, if (result.bonno != null) JudgementSource.Keyword else null)
        study = null
        // 応答時間の終点: Jev に送らないときは、ここ（キーワード判定が終わり、判定結果を置き換えた時点）。
        // 送ったときは、返事を反映する時点（applyJevResult）
        val asking = result.bonno == null && askJev(seq, result.raw, loaded, startedAt)
        if (!asking) responseMillis = elapsedSince(startedAt)
    }

    /** セリフのないカード（該当セリフなし）は開かない */
    fun openStudy(card: SageCard) {
        if (card.line != null) study = card
    }

    fun closeStudy() {
        study = null
    }

    /** 「テストと手入力」を開閉する */
    fun toggleTestInput() {
        testInputOpen = !testInputOpen
    }

    /**
     * 聞き取りを始める。権限の確認は呼び出し側（MainActivity）で済ませておく。
     * 辞典を読めなかったときは始めない。
     */
    fun startListening() {
        if (listening) return
        if (dictionary == null) return
        // 設定を開いている間は聞き取らない
        if (settingsOpen) return
        micMessage = null
        partial = ""
        lastAccepted = null
        listening = true
        val job = speech.events(LANGUAGE_TAG)
            .onEach(::handleSpeechEvent)
            .launchIn(viewModelScope)
        // 使えない端末では、launchIn から戻る前に Unavailable が届いて止まっていることがある。
        // そのときは job を持たない（持つと、止まっているのに動いている扱いになる）
        if (listening) speechJob = job else job.cancel()
    }

    /** 聞き取りを止める。途中経過は消す。直前の確定結果と判定結果は残す */
    fun stopListening() {
        speechJob?.cancel()
        speechJob = null
        listening = false
        partial = ""
    }

    /** 権限を拒否されたとき。落とさずに知らせる */
    fun onMicPermissionDenied() {
        micMessage = MicMessage.Denied
    }

    /** 設定を開く。聞き取りは止める（閉じても自動では再開しない） */
    fun openSettings() {
        stopListening()
        settingsOpen = true
    }

    fun closeSettings() {
        settingsOpen = false
    }

    /**
     * Jev のキーを保存する。前後の空白は除く。空なら何もしない（false）。
     * 保存できたら true。キーの文字列は、ここで保存に渡すだけで、状態としては持たない。
     */
    fun saveApiKey(input: String): Boolean {
        val key = input.trim()
        if (key.isEmpty()) return false
        val saved = SecureKeyStore.save(getApplication<Application>(), key)
        if (saved) {
            // 進行中の送信（前のキーでの送信）は取り消す。止まっていた送信は、次の入力から再開する
            cancelJev()
            keyConfigured = true
            jevStatus = JevStatus.Ready
        }
        return saved
    }

    /** Jev のキーを削除する。進行中の送信は取り消し、以降は一切通信しない */
    fun deleteApiKey() {
        SecureKeyStore.clear(getApplication<Application>())
        cancelJev()
        keyConfigured = false
        jevStatus = JevStatus.NoKey
    }

    /** 閾値を1刻み上げる。端では動かない。変わったら端末に残す */
    fun onThresholdUp(bonno: String) = changeThreshold(bonno) { raiseThreshold(bonno, it) }

    /** 閾値を1刻み下げる。端では動かない。変わったら端末に残す */
    fun onThresholdDown(bonno: String) = changeThreshold(bonno) { lowerThreshold(bonno, it) }

    private fun changeThreshold(bonno: String, change: (Int) -> Int) {
        val current = thresholds[bonno] ?: return
        val next = change(current)
        if (next == current) return
        ThresholdStore.save(getApplication<Application>(), bonno, next)
        thresholds = thresholds + (bonno to next)
    }

    private fun handleSpeechEvent(event: SpeechEvent) {
        if (!listening) return
        when (event) {
            // 途中経過は出すだけ。判定しない
            is SpeechEvent.Partial -> partial = event.text
            is SpeechEvent.Final -> {
                // 応答時間の始点: 音声は、確定結果を受け取った時点
                val startedAt = now()
                partial = ""
                lastHeard = event.text
                judgeVoice(event.text, startedAt)
            }
            // オンラインに切り替えずに止まる
            is SpeechEvent.Unavailable -> stopWithMessage(MicMessage.Unavailable)
            // 無音などの一時的なエラーは SpeechSource が自分で再開する。そうでないものは、黙って止まらずに知らせる
            is SpeechEvent.Error -> if (event.fatal) stopWithMessage(MicMessage.Error(event.code))
            else -> Unit
        }
    }

    /**
     * 音声の確定結果を判定する。歯止め（VoiceFilter.kt）を通ったものだけを、テキスト入力と同じ judge() に渡す。
     * 歯止めを通らなかったものは、何もしない（Jev にも送らない）。
     * - 煩悩あり: 判定結果（カード）を置き換える
     * - 煩悩なし: 今出ている判定結果を変えない（カードを消さない）。送れる状態なら Jev に送る
     * どちらの場合も Study View は閉じない。
     * startedAt は、確定結果を受け取った時刻（応答時間の始点）。
     */
    private fun judgeVoice(heard: String, startedAt: Long) {
        val loaded = dictionary ?: return
        // 歯止めを通らなかった入力は判定していないので、応答時間も変えない
        val text = acceptVoiceText(heard, lastAccepted) ?: return
        lastAccepted = text
        val result = judge(text, loaded) ?: return
        val seq = beginJudgement()
        if (result.bonno == null) {
            // 表示中のカードは変えない。残っているカードは、前の入力のもの（画面で薄くする）
            if (judgement != null) judgementStale = true
            // 送れる状態なら Jev に送る。送らないときは、ここが応答時間の終点（キーワード判定が終わった時点）
            val asking = askJev(seq, result.raw, loaded, startedAt)
            if (!asking) responseMillis = elapsedSince(startedAt)
            return
        }
        showJudgement(result, JudgementSource.Keyword)
        // 応答時間の終点: キーワードで煩悩ありのときは、判定結果を置き換えた時点
        responseMillis = elapsedSince(startedAt)
    }

    /**
     * 判定結果を置き換える。置き換えた回数を進める（画面が先頭へスクロールするきっかけ）。
     * 置き換えた判定結果は、いちばん新しい入力のもの。
     */
    private fun showJudgement(result: Judgement, source: JudgementSource?) {
        judgement = result
        judgementSource = source
        judgementVersion += 1
        judgementStale = false
    }

    /** 応答時間を測る時計。端末の時刻の変更に左右されない（起動からの経過時間） */
    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun elapsedSince(startedAt: Long): Long = (now() - startedAt).coerceAtLeast(0L)

    /**
     * 新しい入力の判定を始める。番号を進め、進行中の送信を取り消し、前の入力の「送った文と値」の表示を消す。
     * Jev に送らない判定（キーワードで煩悩あり）でも呼ぶ。
     */
    private fun beginJudgement(): Long {
        val seq = cancelJev()
        jevSentText = null
        jevReadings = emptyList()
        return seq
    }

    /** 進行中の送信を取り消し、番号を進める（遅れて届いた返事を捨てるため）。新しい番号を返す */
    private fun cancelJev(): Long {
        jevJob?.cancel()
        jevJob = null
        // 取り消した送信の「問い合わせ中」を残さない
        if (jevStatus == JevStatus.Asking) jevStatus = JevStatus.Ready
        judgeSeq += 1
        return judgeSeq
    }

    /**
     * キーワードで煩悩なしだった文を、Jev に送る。
     * キーが未設定のときと、送信が止まっているとき（キーの誤り・残高切れ）は、何もしない（通信しない）。
     * 送る文は必ず Sanitizer.sanitize を通す。判定結果の組み立て（シチュエーションの判定）には元の文を使う。
     *
     * 問い合わせを始めたら true、送らなかったら false を返す。
     * startedAt は応答時間の始点。問い合わせている間は応答時間を null にし、返事を反映するときに決める。
     * 追い越されて捨てた返事と、取り消した送信では、応答時間を更新しない。
     */
    private fun askJev(seq: Long, raw: String, loaded: Dictionary, startedAt: Long): Boolean {
        if (!keyConfigured) return false
        if (jevStatus == JevStatus.BadKey || jevStatus == JevStatus.NoCredit) return false

        val sanitized = Sanitizer.sanitize(raw)
        jevStatus = JevStatus.Asking
        responseMillis = null
        jevJob = viewModelScope.launch {
            // キーは送る直前に読む（メインスレッドでは読まない）。この送信の間だけ、このローカル変数に置く
            val key = withContext(Dispatchers.IO) { SecureKeyStore.load(getApplication<Application>()) }
            if (seq != judgeSeq) return@launch
            if (key.isEmpty()) {
                // 設定済みのはずが読めなかった（復号できない、など）。送らずに、キー未設定にする
                keyConfigured = false
                jevStatus = JevStatus.NoKey
                // 送らないと決まったので、ここを応答時間の終点にする
                responseMillis = elapsedSince(startedAt)
                return@launch
            }
            jevSentText = sanitized
            val result = jevClient.evaluate(sanitized, key)
            // 追い越された返事は捨てる（新しい入力の判定を、古い返事で上書きしない）
            if (seq != judgeSeq) return@launch
            applyJevResult(result, raw, loaded, startedAt)
        }
        return true
    }

    /**
     * Jev の結果を画面の状態に反映する。
     * - 値が読めて閾値以上: 判定結果（カード）を置き換える。Study View は閉じない
     * - 値が読めて閾値未満: カードは変えない。値は画面に出す
     * - 失敗: 状態に出す。キーの誤りと残高切れは、以降の送信を止める
     * 閾値は、返事を読んだ時点の値を使い、結果と一緒に持つ。
     * ここが、Jev に送ったときの応答時間の終点（値が読めたときも、失敗・通信できなかった・想定外のときも）。
     */
    private fun applyJevResult(result: JevCallResult, raw: String, loaded: Dictionary, startedAt: Long) {
        responseMillis = elapsedSince(startedAt)
        when (result) {
            is JevCallResult.Values -> {
                val readings = readJevValues(result.values, thresholds)
                jevReadings = readings
                jevStatus = JevStatus.Ready
                val hit = pickJevReading(readings) ?: return
                showJudgement(buildJevJudgement(raw, hit.bonno, loaded), JudgementSource.Jev(hit))
            }
            is JevCallResult.Failure -> jevStatus = when (val failure = result.failure) {
                JevFailure.BadKey -> JevStatus.BadKey
                JevFailure.NoCredit -> JevStatus.NoCredit
                is JevFailure.Other -> JevStatus.HttpError(failure.code)
            }
            JevCallResult.NetworkError -> jevStatus = JevStatus.NetworkError
            JevCallResult.Unexpected -> jevStatus = JevStatus.Unexpected
        }
    }

    private fun stopWithMessage(message: MicMessage) {
        stopListening()
        micMessage = message
    }
}

/** assets/dictionary.json を UTF-8 で読む。読めない・形が合わないときは、落とさずに null を返す */
private fun loadDictionary(application: Application): Dictionary? =
    try {
        val text = application.assets.open("dictionary.json").use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
        parseDictionary(text)
    } catch (e: Exception) {
        null
    }
