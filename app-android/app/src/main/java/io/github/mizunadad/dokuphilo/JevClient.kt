package io.github.mizunadad.dokuphilo

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Jev（Typesafe 社の API）のクライアント。送受信だけを行う（ADR-027 決定7）。
 *
 * - アプリの中で通信するのは、このファイルだけ。okhttp3 を import してよいのも、このファイルだけ
 * - 送り先は JEV_ENDPOINT の定数だけ。引数で変えられる形にしない
 * - 要求の本文・応答の読み方・誤りの分類は JevProtocol.kt の関数を使う（ここで JSON を組み立てない）
 * - 送る文・返事・キーをログに出さない。応答の本文と例外のメッセージは、結果に持たせない
 *   （例外のメッセージには、送り先やヘッダーの中身が入ることがある）
 * - インターセプタ、ログ、キャッシュ、再試行を足さない。リダイレクトは追わない（3xx は「その他」になる）
 *
 * 送受信の形とタイムアウトは EGOGUARD の JevClient.kt と同じ（connect 3秒、read 3秒、全体 5秒）。
 */
class JevClient {

    // 最初に送るときに作る（キーが未設定なら、作りもしない）
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val mediaType by lazy { "application/json; charset=utf-8".toMediaType() }

    /**
     * Jev に1回送り、結果を返す。例外で落とさない（取り消しの CancellationException だけは、そのまま外に出す）。
     *
     * @param sanitized 必ず Sanitizer.sanitize() を通した文を渡すこと（ADR-027 決定5）
     * @param apiKey    利用者が入力したキー。空なら送らない
     */
    suspend fun evaluate(sanitized: String, apiKey: String): JevCallResult = withContext(Dispatchers.IO) {
        // キーが未設定なら一切通信しない（呼び出し側でも確かめている。ここは念のため）
        if (apiKey.isBlank()) return@withContext JevCallResult.Failure(JevFailure.BadKey)

        val request = try {
            Request.Builder()
                .url(JEV_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(buildJevRequestBody(sanitized).toRequestBody(mediaType))
                .build()
        } catch (e: IllegalArgumentException) {
            // キーに、ヘッダーへ入れられない文字（改行や日本語など）がある。送らずに、キーの誤りとして返す
            return@withContext JevCallResult.Failure(JevFailure.BadKey)
        }

        try {
            awaitResponse(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    when (val parsed = parseJevResponse(body)) {
                        is JevParseResult.Values -> JevCallResult.Values(parsed.values)
                        JevParseResult.Unexpected -> JevCallResult.Unexpected
                    }
                } else {
                    JevCallResult.Failure(classifyJevFailure(response.code, body))
                }
            }
        } catch (e: CancellationException) {
            // 取り消しは握りつぶさない
            throw e
        } catch (e: IOException) {
            // つながらない、タイムアウト、途中で切れた、など
            JevCallResult.NetworkError
        } catch (e: Exception) {
            // 応答を受け取れなかったので、通信できなかった扱いにする（落とさない）
            JevCallResult.NetworkError
        }
    }

    /** OkHttp の呼び出しを待つ。コルーチンが取り消されたら、通信も取り消す */
    private suspend fun awaitResponse(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
}
