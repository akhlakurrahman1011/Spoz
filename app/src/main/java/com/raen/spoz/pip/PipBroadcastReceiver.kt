package com.raen.spoz.pip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

const val ACTION_PIP_CONTROL = "com.raen.spoz.PIP_CONTROL"
const val EXTRA_CONTROL_TYPE = "control_type"
const val CONTROL_TYPE_PLAY = 1
const val CONTROL_TYPE_PAUSE = 2

class PipBroadcastReceiver : BroadcastReceiver() {
    companion object {
        var onPlayPauseToggle: (() -> Unit)? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_PIP_CONTROL) {
            onPlayPauseToggle?.invoke()
        }
    }
}
