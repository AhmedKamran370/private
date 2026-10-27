package com.bundlebot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

object Alarm {
    private var mp: MediaPlayer? = null
    private val h = Handler(Looper.getMainLooper())

    @Synchronized fun ring(c: Context, msg: String) {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("alarm", "Bundle Bot alarms", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(77, Notification.Builder(c, "alarm").setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Bundle Bot needs you").setContentText(msg).setContentIntent(pi).setAutoCancel(true).build())
            if (mp == null) {
                val u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mp = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(c, u); isLooping = true; prepare(); start()
                }
                (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
                h.postDelayed({ stop(c) }, 60000)
            }
        } catch (e: Exception) { }
    }

    fun stop(c: Context) {
        try { mp?.stop(); mp?.release() } catch (e: Exception) { }
        mp = null
        try { (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (e: Exception) { }
    }
}
