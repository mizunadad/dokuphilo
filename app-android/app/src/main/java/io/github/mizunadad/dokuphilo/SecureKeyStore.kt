package io.github.mizunadad.dokuphilo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * APIキーの暗号化保存
 *
 * Android Keystore で生成した AES 鍵を使い、SharedPreferences には暗号文だけを置く。
 * 鍵そのものはハードウェア保護領域（対応端末なら TEE / StrongBox）に入り、
 * アプリからも取り出せない。アンインストールすると鍵ごと消える。
 *
 * なぜ EncryptedSharedPreferences（androidx.security-crypto）を使わないか:
 *   部品を増やさないため（ADR-027 決定6）。Keystore + AES/GCM は Android 標準APIだけで完結し、
 *   依存を1つも増やさずに同等の保護が得られる。
 *
 * ※ APIキーを端末に置く以上、root 化端末やデバッグ可能ビルドでは完全な秘匿はできない。
 *   キーの扱いは ADR-027 決定6 に従う（画面にはキーの文字を出さない。ログ・リポジトリ・APK に入れない。
 *   バックアップと端末移行からも、このファイルを除外する）。
 */
object SecureKeyStore {

    private const val PREFS = "dokuphilo_secure"
    private const val ALIAS = "dokuphilo_api_key"
    private const val PREF_CIPHERTEXT = "api_key_ct"
    private const val PREF_IV = "api_key_iv"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 保存する。空文字を渡した場合は削除と同じ扱い */
    fun save(context: Context, apiKey: String): Boolean = try {
        if (apiKey.isBlank()) {
            clear(context); true
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_CIPHERTEXT, Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
            true
        }
    } catch (e: Exception) {
        false
    }

    /** 読み出す。未保存・復号失敗時は空文字 */
    fun load(context: Context): String = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ct = prefs.getString(PREF_CIPHERTEXT, null)
        val iv = prefs.getString(PREF_IV, null)
        if (ct == null || iv == null) {
            ""
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        // 端末のバックアップ復元などで鍵が失われると復号できない。その場合は未設定として扱う
        ""
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PREF_CIPHERTEXT).remove(PREF_IV).apply()
    }
}
