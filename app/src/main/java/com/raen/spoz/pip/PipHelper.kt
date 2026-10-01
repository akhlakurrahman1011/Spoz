package com.raen.spoz.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational

object PipHelper {
    fun buildPipParams(
        context: Context,
        isPlaying: Boolean,
        videoWidth: Int = 16,
        videoHeight: Int = 9,
        sourceRect: Rect? = null
    ): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        val builder = PictureInPictureParams.Builder()

        // 1. Aspect Ratio (Clamped to Android system limits: 0.4184f to 2.39f)
        val validAspectRatio = if (videoWidth > 0 && videoHeight > 0) {
            val ratio = videoWidth.toFloat() / videoHeight.toFloat()
            if (ratio in 0.418414f..2.39f) {
                Rational(videoWidth, videoHeight)
            } else {
                Rational(16, 9)
            }
        } else {
            Rational(16, 9)
        }
        builder.setAspectRatio(validAspectRatio)

        // 2. Source Rect Hint for fluid transition animation
        sourceRect?.let { builder.setSourceRectHint(it) }

        // 3. Android 12+ (API 31+) Auto-Enter PiP & Seamless Resize
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(true)
        }

        // 4. RemoteActions for Play/Pause in PiP overlay
        val intent = Intent(ACTION_PIP_CONTROL).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CONTROL_TYPE, if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val title = if (isPlaying) "Pause" else "Play"

        val action = RemoteAction(
            Icon.createWithResource(context, iconRes),
            title,
            title,
            pendingIntent
        )
        builder.setActions(listOf(action))

        return builder.build()
    }

    fun updatePipParams(
        activity: Activity,
        isPlaying: Boolean,
        videoWidth: Int = 16,
        videoHeight: Int = 9,
        sourceRect: Rect? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = buildPipParams(activity, isPlaying, videoWidth, videoHeight, sourceRect)
            if (params != null) {
                try {
                    activity.setPictureInPictureParams(params)
                } catch (e: Exception) {
                    android.util.Log.w("PipHelper", "Error setting PiP params: ${e.message}")
                }
            }
        }
    }

    fun enterPip(
        activity: Activity,
        isPlaying: Boolean,
        videoWidth: Int = 16,
        videoHeight: Int = 9,
        sourceRect: Rect? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = buildPipParams(activity, isPlaying, videoWidth, videoHeight, sourceRect)
            if (params != null) {
                try {
                    activity.enterPictureInPictureMode(params)
                } catch (e: Exception) {
                    android.util.Log.w("PipHelper", "Error entering PiP mode: ${e.message}")
                }
            }
        }
    }
}
