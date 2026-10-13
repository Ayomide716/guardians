package com.ayomide.guardian.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Loud looping alarm on the alarm stream at full volume, re-raised every second
 * so the volume keys can't quiet it, plus vibration.
 */
class Siren(private val ctx: Context) {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var savedVolume = -1
    val isPlaying get() = player != null

    private val keepLoud = object : Runnable {
        override fun run() {
            try {
                audio?.let { it.setStreamVolume(AudioManager.STREAM_ALARM, it.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            } catch (e: SecurityException) {
                // Do Not Disturb prevents volume changes; keep playing anyway.
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (player != null) return
        try {
            audio?.let { savedVolume = it.getStreamVolume(AudioManager.STREAM_ALARM) }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w("Siren", "Could not play alarm sound", e)
            player = null
        }
        handler.post(keepLoud)
        ctx.getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
    }

    fun stop() {
        handler.removeCallbacks(keepLoud)
        try {
            player?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        player?.release()
        player = null
        ctx.getSystemService(Vibrator::class.java)?.cancel()
        if (savedVolume >= 0) {
            try {
                audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (e: SecurityException) {
                // Do Not Disturb blocks volume changes
            }
            savedVolume = -1
        }
    }
}
