package eu.kanade.tachiyomi.data.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import uy.kohesive.injekt.injectLazy
import java.util.regex.Pattern
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * DownloadNotifier is used to show notifications when downloading one or multiple chapters.
 *
 * @param context context of application
 */
internal class DownloadNotifier(
    private val context: Context,
    private val notifyLock: ReentrantLock = ReentrantLock()
) {
    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Create a fresh NotificationCompat.Builder. IMPORTANT: do NOT reuse builders across threads.
     */
    private fun createNotificationBuilder(): NotificationCompat.Builder {
        return NotificationCompat
            .Builder(context, Notifications.CHANNEL_DOWNLOADER)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
    }

    /**
     * Status of download. Used for correct notification icon.
     */
    private var isDownloading = false

    /**
     * Updated when error is thrown
     */
    var errorThrown = false

    /**
     * Shows a notification from this built object.
     *
     * @param id the id of the notification.
     */
    private fun showBuiltNotification(id: Int = Notifications.ID_DOWNLOAD_CHAPTER, built: android.app.Notification) {
        context.notificationManager.notify(id, built)
    }

    /**
     * Dismiss the downloader's notification. Downloader error notifications use a different id, so
     * those can only be dismissed by the user.
     */
    fun dismiss() {
        context.notificationManager.cancel(Notifications.ID_DOWNLOAD_CHAPTER)
    }

    fun setPlaceholder(download: Download?): NotificationCompat.Builder {
        val ctx = context.localeContext

        // Build and configure a fresh builder inside the lock to avoid concurrent construction issues.
        val builder = notifyLock.withLock {
            val b = createNotificationBuilder()
            with(b) {
                // Check if first call.
                if (!isDownloading) {
                    setSmallIcon(android.R.drawable.stat_sys_download)
                    setAutoCancel(false)
                    clearActions()
                    setOngoing(true)
                    // Open download manager when clicked
                    setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(ctx))
                    color = ContextCompat.getColor(ctx, R.color.secondaryTachiyomi)
                    isDownloading = true
                    // Pause action
                    addAction(
                        R.drawable.ic_pause_24dp,
                        ctx.getString(R.string.pause),
                        NotificationReceiver.pauseDownloadsPendingBroadcast(ctx),
                    )
                }

                if (download != null && !preferences.hideNotificationContent()) {
                    val title = download.manga.title.chop(15)
                    val quotedTitle = Pattern.quote(title)
                    val name = download.chapter.preferredChapterName(ctx, download.manga, preferences)
                    val chapter =
                        name.replaceFirst(
                            "$quotedTitle[\\s]*[-]*[\\s]*"
                                .toRegex(RegexOption.IGNORE_CASE),
                            "",
                        )
                    setContentTitle("$title - $chapter".chop(30))
                    setContentText(ctx.getString(R.string.downloading))
                } else {
                    setContentTitle(ctx.getString(R.string.downloading))
                    setContentText(null)
                }
                setProgress(0, 0, true)
                setStyle(null)
            }
            b
        }

        return builder
    }

    /**
     * Called when download progress changes.
     *
     * @param download download object containing download information.
     */
    fun onProgressChange(download: Download) {
        // Build, configure, and post the notification inside the lock to avoid concurrent builder usage.
        val built = notifyLock.withLock {
            val b = createNotificationBuilder()
            val ctx = context.localeContext

            with(b) {
                // Check if first call.
                if (!isDownloading) {
                    setSmallIcon(android.R.drawable.stat_sys_download)
                    setAutoCancel(false)
                    clearActions()
                    setOngoing(true)
                    // Open download manager when clicked
                    color = ContextCompat.getColor(ctx, R.color.secondaryTachiyomi)
                    setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(ctx))
                    isDownloading = true
                    // Pause action
                    addAction(
                        R.drawable.ic_pause_24dp,
                        ctx.getString(R.string.pause),
                        NotificationReceiver.pauseDownloadsPendingBroadcast(ctx),
                    )
                }

                val downloadingProgressText =
                    ctx
                        .getString(R.string.downloading_progress)
                        .format(download.downloadedImages, download.pages!!.size)

                if (preferences.hideNotificationContent()) {
                    setContentTitle(downloadingProgressText)
                } else {
                    val title = download.manga.title.chop(15)
                    val quotedTitle = Pattern.quote(title)
                    val name = download.chapter.preferredChapterName(ctx, download.manga, preferences)
                    val chapter =
                        name.replaceFirst(
                            "$quotedTitle[\\s]*[-]*[\\s]*".toRegex(RegexOption.IGNORE_CASE),
                            "",
                        )
                    setContentTitle("$title - $chapter".chop(30))
                    setContentText(downloadingProgressText)
                }
                setStyle(null)
                setProgress(download.pages!!.size, download.downloadedImages, false)
            }
            b.build()
        }

        showBuiltNotification(Notifications.ID_DOWNLOAD_CHAPTER, built)
    }

    /**
     * Show notification when download is paused.
     */
    fun onDownloadPaused() {
        val built = notifyLock.withLock {
            val ctx = context.localeContext
            val b = createNotificationBuilder()
            with(b) {
                setContentTitle(ctx.getString(R.string.paused))
                setContentText(ctx.getString(R.string.download_paused))
                setSmallIcon(R.drawable.ic_pause_24dp)
                setAutoCancel(false)
                setOngoing(false)
                setProgress(0, 0, false)
                color = ContextCompat.getColor(ctx, R.color.secondaryTachiyomi)
                clearActions()
                // Open download manager when clicked
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(ctx))
                // Resume action
                addAction(
                    R.drawable.ic_play_arrow_24dp,
                    ctx.getString(R.string.resume),
                    NotificationReceiver.resumeDownloadsPendingBroadcast(ctx),
                )
                // Clear action
                addAction(
                    R.drawable.ic_close_24dp,
                    ctx.getString(R.string.cancel_all),
                    NotificationReceiver.clearDownloadsPendingBroadcast(ctx),
                )
            }
            isDownloading = false
            b.build()
        }

        showBuiltNotification(Notifications.ID_DOWNLOAD_CHAPTER, built)
    }

    /**
     * Called when the downloader receives a warning.
     *
     * @param reason the text to show.
     */
    fun onWarning(reason: String) {
        val built = notifyLock.withLock {
            val ctx = context.localeContext
            val b = createNotificationBuilder()
            with(b) {
                setContentTitle(ctx.getString(R.string.downloads))
                setContentText(reason)
                color = ContextCompat.getColor(ctx, R.color.secondaryTachiyomi)
                setSmallIcon(R.drawable.ic_warning_white_24dp)
                setOngoing(false)
                setAutoCancel(true)
                clearActions()
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(ctx))
                setProgress(0, 0, false)
            }
            isDownloading = false
            b.build()
        }

        showBuiltNotification(Notifications.ID_DOWNLOAD_CHAPTER_ERROR, built)

        // Reset download information
        isDownloading = false
    }

    /**
     * Called when the downloader has too many downloads from one source.
     */
    fun massDownloadWarning() {
        val built = notifyLock.withLock {
            val ctx = context.localeContext
            val builtNotif =
                ctx
                    .notificationBuilder(Notifications.CHANNEL_DOWNLOADER) {
                        setContentTitle(ctx.getString(R.string.warning))
                        setSmallIcon(R.drawable.ic_warning_white_24dp)
                        setStyle(
                            NotificationCompat
                                .BigTextStyle()
                                .bigText(ctx.getString(R.string.download_queue_size_warning)),
                        )
                        setContentIntent(
                            NotificationHandler.openUrl(
                                ctx,
                                LibraryUpdateNotifier.HELP_WARNING_URL,
                            ),
                        )
                        setTimeoutAfter(30000)
                    }.build()
            builtNotif
        }

        context.notificationManager.notify(
            Notifications.ID_DOWNLOAD_SIZE_WARNING,
            built,
        )
    }

    /**
     * Called when the downloader receives an error. It's shown as a separate notification to avoid
     * being overwritten.
     *
     * @param error string containing error information.
     * @param chapter string containing chapter title.
     */
    fun onError(
        error: String? = null,
        chapter: String? = null,
        mangaTitle: String? = null,
        customIntent: Intent? = null,
    ) {
        // Build and post notification inside lock
        val built = notifyLock.withLock {
            val ctx = context.localeContext
            val b = createNotificationBuilder()
            with(b) {
                setContentTitle(
                    mangaTitle?.plus(": $chapter") ?: ctx.getString(R.string.download_error),
                )
                setContentText(error ?: ctx.getString(R.string.could_not_download_unexpected_error))
                setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        error ?: ctx.getString(R.string.could_not_download_unexpected_error),
                    ),
                )
                setSmallIcon(android.R.drawable.stat_sys_warning)
                setCategory(NotificationCompat.CATEGORY_ERROR)
                setOngoing(false)
                clearActions()
                setAutoCancel(true)
                if (customIntent != null) {
                    setContentIntent(
                        PendingIntent.getActivity(
                            ctx,
                            0,
                            customIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                } else {
                    setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(ctx))
                }
                color = ContextCompat.getColor(ctx, R.color.secondaryTachiyomi)
                setProgress(0, 0, false)
            }
            errorThrown = true
            isDownloading = false
            b.build()
        }

        showBuiltNotification(Notifications.ID_DOWNLOAD_CHAPTER_ERROR, built)
    }
}
