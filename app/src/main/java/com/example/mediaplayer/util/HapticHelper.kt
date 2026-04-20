package com.example.mediaplayer.util
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object HapticHelper {
    fun tap(ctx: Context) = vib(ctx, 18)
    fun success(ctx: Context) = vib(ctx, 60)
    fun warning(ctx: Context) = vib(ctx, 120)
    private fun vib(ctx: Context, ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(VibratorManager::class.java))
                    ?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") v?.vibrate(ms)
                }
            }
        } catch (_: Exception) {}
    }
}
