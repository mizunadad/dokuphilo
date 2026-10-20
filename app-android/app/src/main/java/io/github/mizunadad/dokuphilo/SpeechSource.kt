package io.github.mizunadad.dokuphilo

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * SpeechRecognizer を Flow でラップする
 *
 * v1 prototype からの設計変更:
 *  - RecognizerIntent.ACTION_RECOGNIZE_SPEECH は「システムダイアログを出す単発認識」であり、
 *    常時HUDには使えない。SpeechRecognizer + RecognitionListener + 自動再起動に置き換えた。
 *  - Composable のローカル state ではなく Flow を返すので、Activity 再生成でリスナーが漏れない。
 *
 * オンデバイス認識について:
 *  EXTRA_PREFER_OFFLINE は「優先」であって保証ではない（未ダウンロードならオンラインへ
 *  フォールバックする）。Android 13 (API 33) 以降は createOnDeviceSpeechRecognizer() を使い、
 *  利用できない場合はオンラインへ落とさず onUnavailable を通知する。
 */
sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data object ReadyForSpeech : SpeechEvent
    data object EndOfSpeech : SpeechEvent
    data class Error(val code: Int, val fatal: Boolean) : SpeechEvent
    data class Unavailable(val reason: String) : SpeechEvent
}

class SpeechSource(private val context: Context, private val requireOnDevice: Boolean = true) {

    /** 無音で切れた後の再起動バックオフ。即時再帰は高速ループの原因になる */
    private val restartDelayMs = 400L

    fun events(languageTag: String): Flow<SpeechEvent> = callbackFlow {
        val onDeviceSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        if (requireOnDevice && !onDeviceSupported) {
            // プライバシー要件を満たせないので、オンラインへフォールバックせず停止する
            trySend(SpeechEvent.Unavailable("on-device recognition is not available on this device"))
            close(); return@callbackFlow
        }
        if (!requireOnDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            // Android 11+ では AndroidManifest の <queries> 宣言が無いと必ずここに落ちる
            trySend(SpeechEvent.Unavailable("no recognition service (check <queries> in AndroidManifest)"))
            close(); return@callbackFlow
        }

        val recognizer = if (onDeviceSupported) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (!onDeviceSupported) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        var stopped = false

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { trySend(SpeechEvent.ReadyForSpeech) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { trySend(SpeechEvent.EndOfSpeech) }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: android.os.Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { trySend(SpeechEvent.Final(it)) }
                restart()
            }

            override fun onError(error: Int) {
                // 無音タイムアウトは異常ではないので黙って再開する
                val transient = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                trySend(SpeechEvent.Error(error, fatal = !transient))
                if (transient) restart() else close()
            }

            private fun restart() {
                if (stopped) return
                // バックオフを入れてから再開する
                android.os.Handler(context.mainLooper).postDelayed({
                    if (!stopped) runCatching { recognizer.startListening(intent) }
                }, restartDelayMs)
            }
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)

        awaitClose {
            stopped = true
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.destroy() }
        }
    }
}
