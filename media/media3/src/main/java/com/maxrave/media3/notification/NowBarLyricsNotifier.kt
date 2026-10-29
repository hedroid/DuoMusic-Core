package com.maxrave.media3.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.maxrave.logger.Logger
import com.maxrave.media3.R

/**
 * A second, deliberately NON-media notification that mirrors the current lyric line, so Samsung's
 * One UI 8.5 Now Bar (the lock-screen music capsule) can surface live lyrics for a third-party
 * package. Entry routes, both non-public on Samsung's side and needing on-device verification:
 *
 *  1. Android 16 promoted ongoing ("Live Updates") via [NotificationCompat.Builder.setRequestPromotedOngoing]
 *     — the standard route, but One UI 8/8.5 only promotes third-party Live Updates when the
 *     secure setting `enable_notification_nowbar_test` is enabled.
 *  2. Samsung automation extras (`android.ongoingActivityNoti.automation` / `.automationPackage`)
 *     — reported to enter the Now Bar with an independent package name on some One UI 8.5
 *     firmware. Added unconditionally; harmless elsewhere.
 *
 * Constraints carried over from the id-2026 incident (see SimpleMediaService): this notification
 * must NEVER use MediaStyle, must NOT be bound to the MediaSession, and must NEVER be posted via
 * startForeground — two MediaStyle notifications fighting over one session made SystemUI drop the
 * real media card and the OEM capsules. A plain low-importance notification rides the shade (and,
 * hopefully, the Now Bar) beside the media card instead of competing with it.
 */
class NowBarLyricsNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var channelReady = false

    /** Line-change dedupe: Progress ticks arrive every 50ms, lyric lines every few seconds. */
    private var lastPostedLine: String? = null

    fun update(
        line: String?,
        title: String,
        artist: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (line.isNullOrBlank()) {
            cancel()
            return
        }
        if (line == lastPostedLine) return
        if (notificationManager.areNotificationsEnabled().not()) return
        ensureChannel()
        val subtitle =
            listOfNotNull(
                title.takeIf { it.isNotBlank() },
                artist.takeIf { it.isNotBlank() },
            ).joinToString(" - ")
        val builder =
            NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.mono)
                .setContentTitle(line)
                .setContentText(subtitle)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShortCriticalText(line)
                .setRequestPromotedOngoing(true)
        if (durationMs > 0) {
            // ProgressStyle owns its own progress model: max = sum of segment lengths (no
            // setProgressMax — default 100). On API 36+ the compat builder's setProgress never
            // reaches the platform style, so the segment + style progress is the only real bar.
            builder.setStyle(
                NotificationCompat
                    .ProgressStyle()
                    .addProgressSegment(
                        NotificationCompat.ProgressStyle.Segment(durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    )
                    .setProgress(positionMs.coerceIn(0, durationMs).toInt())
                    .setStyledByProgress(true),
            )
        }
        val extras = builder.extras
        extras.putBoolean(SAMSUNG_AUTOMATION_EXTRA, true)
        extras.putString(SAMSUNG_AUTOMATION_PACKAGE_EXTRA, appContext.packageName)
        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
            lastPostedLine = line
        } catch (e: SecurityException) {
            Logger.w(TAG, "Now Bar lyrics notification rejected: ${e.message}")
        }
    }

    fun cancel() {
        if (lastPostedLine == null) return
        lastPostedLine = null
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (channelReady) return
        val channel =
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        notificationManager.createNotificationChannel(channel)
        channelReady = true
    }

    private companion object {
        const val TAG = "NowBarLyrics"

        /** id 2026 was the old second-media-notification that broke the Now Bar — keep away from it. */
        const val NOTIFICATION_ID = 2027
        const val CHANNEL_ID = "duomusic_nowbar_lyrics"
        const val CHANNEL_NAME = "Now Bar Lyrics"
        const val SAMSUNG_AUTOMATION_EXTRA = "android.ongoingActivityNoti.automation"
        const val SAMSUNG_AUTOMATION_PACKAGE_EXTRA = "android.ongoingActivityNoti.automationPackage"
    }
}
