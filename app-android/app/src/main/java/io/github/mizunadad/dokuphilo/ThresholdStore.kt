package io.github.mizunadad.dokuphilo

import android.content.Context

/**
 * Jev の閾値を端末に残す（ADR-027 決定3、ADR-028 決定4）。
 *
 * ふつうの SharedPreferences に、煩悩ごとの整数（百分率）で置く。秘密ではないので暗号化しない。
 * ここに置くのは閾値だけ。入力文・発話・キーは置かない。
 * 保存するキーの名前（jev_threshold_ + 煩悩）は変えないこと（端末に保存済みの値を、そのまま使うため）。
 */
object ThresholdStore {

    private const val PREFS = "dokuphilo_settings"
    private const val KEY_PREFIX = "jev_threshold_"

    /** 読む。未保存・範囲の外・刻みに合わない値・読めない値は、その煩悩の既定に直す */
    fun load(context: Context, bonno: String): Int = try {
        normalizeThreshold(
            bonno,
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PREFIX + bonno, defaultThreshold(bonno)),
        )
    } catch (e: Exception) {
        defaultThreshold(bonno)
    }

    fun save(context: Context, bonno: String, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PREFIX + bonno, normalizeThreshold(bonno, percent))
            .apply()
    }
}
