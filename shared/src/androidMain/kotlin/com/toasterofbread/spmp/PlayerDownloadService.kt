package com.toasterofbread.spmp

import SpMp
import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.graphics.drawable.IconCompat
import com.toasterofbread.composekit.platform.PlatformFile
import com.toasterofbread.spmp.model.lyrics.LyricsFileConverter
import com.toasterofbread.spmp.model.mediaitem.library.MediaItemLibrary
import com.toasterofbread.spmp.model.mediaitem.loader.SongLyricsLoader
import com.toasterofbread.spmp.model.mediaitem.song.Song
import com.toasterofbread.spmp.model.mediaitem.song.SongAudioQuality
import com.toasterofbread.spmp.model.mediaitem.song.SongRef
import com.toasterofbread.spmp.model.mediaitem.song.getSongFormatByQuality
import com.toasterofbread.spmp.model.settings.Settings
import com.toasterofbread.spmp.model.settings.category.StreamingSettings
import com.toasterofbread.spmp.platform.AppContext
import com.toasterofbread.spmp.platform.PlatformBinder
import com.toasterofbread.spmp.platform.PlatformServiceImpl
import com.toasterofbread.spmp.platform.download.LocalSongMetadataProcessor
import com.toasterofbread.spmp.platform.download.PlayerDownloadManager
import com.toasterofbread.spmp.platform.download.PlayerDownloadManager.DownloadStatus
import com.toasterofbread.spmp.platform.download.getLocalLyricsFile
import com.toasterofbread.spmp.platform.download.getLocalSongFile
import com.toasterofbread.spmp.platform.getUiLanguage
import com.toasterofbread.spmp.resources.getString
import com.toasterofbread.spmp.resources.initResources
import com.toasterofbread.spmp.youtubeapi.YoutubeVideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val FILE_DOWNLOADING_SUFFIX = ".part"
private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_CHANNEL_ID = "download_channel"
private const val DOWNLOAD_MAX_RETRY_COUNT = 3

// TODO | Split

class PlayerDownloadService: PlatformServiceImpl() {
    private inner class Download(
        context: AppContext,
        val song: Song,
        val quality: SongAudioQuality,
        var silent: Boolean,
        val instance: Int,
    ) {
        var song_file: PlatformFile? = runBlocking { song.getLocalSongFile(context, allow_partial = true) } // This is fine :)
        var lyrics_file: PlatformFile? = song.getLocalLyricsFile(context, allow_partial = true)

        var status: DownloadStatus.Status =
            if (song_file?.let { isFileDownloadInProgressForSong(it, song) } == false) DownloadStatus.Status.ALREADY_FINISHED
            else DownloadStatus.Status.IDLE
            set(value) {
                if (field != value) {
                    field = value
                    broadcastStatus()
                }
            }

        val finished: Boolean get() = status == DownloadStatus.Status.ALREADY_FINISHED || status == DownloadStatus.Status.FINISHED
        val downloading: Boolean get() = status == DownloadStatus.Status.DOWNLOADING || status == DownloadStatus.Status.PAUSED

        var cancelled: Boolean = false
            private set

        var downloaded: Long = 0
        var total_size: Long = -1

        val progress: Float get() = if (total_size < 0f) 0f else downloaded.toFloat() / total_size
        val percent_progress: Int get() = (progress * 100).toInt()

        fun getStatusObject(): DownloadStatus =
            DownloadStatus(
                song,
                status,
                quality,
                progress,
                instance.toString(),
                song_file
            )

        fun cancel() {
            cancelled = true
        }

        fun generatePath(extension: String, in_progress: Boolean): String {
            return getDownloadPath(song, extension, in_progress, context)
        }

        fun broadcastResult(result: Result<PlatformFile?>?, instance: Int) {
            sendMessageOut(PlayerDownloadManager.PlayerDownloadMessage(
                IntentAction.START_DOWNLOAD,
                mapOf(
                    "status" to getStatusObject(),
                    "result" to (result ?: Result.success(null))
                ),
                instance
            ))
        }

        fun broadcastStatus(started: Boolean = false) {
            sendMessageOut(PlayerDownloadManager.PlayerDownloadMessage(
                IntentAction.STATUS_CHANGED,
                mapOf("status" to getStatusObject(), "started" to started)
            ))
        }

        override fun toString(): String =
            "Download(id=${song.id}, quality=$quality, silent=$silent, instance=$instance, file=$song_file)"
    }

    private var download_inc: Int = 0
    private fun getOrCreateDownload(context: AppContext, song: Song): Download {
        synchronized(downloads) {
            for (download in downloads) {
                if (download.song.id == song.id) {
                    return download
                }
            }
            return Download(context, song, Settings.getEnum(StreamingSettings.Key.DOWNLOAD_AUDIO_QUALITY), true, download_inc++)
        }
    }

    fun getAllDownloadsStatus(): List<DownloadStatus> =
        downloads.map { it.getStatusObject() }

    fun getDownloadStatus(song: Song): DownloadStatus? =
        downloads.firstOrNull { it.song.id == song.id }?.getStatusObject()

    private fun getTotalDownloadProgress(): Float {
        if (downloads.isEmpty()) {
            return 1f
        }

        val finished = completed_downloads + failed_downloads

        var total_progress: Float = finished.toFloat()
        for (download in downloads) {
            total_progress += download.progress
        }
        return total_progress / (downloads.size + finished)
    }

    enum class IntentAction {
        STOP, START_DOWNLOAD, CANCEL_DOWNLOAD, CANCEL_ALL, PAUSE_RESUME, STATUS_CHANGED
    }
    
    data class FilenameData(
        val id_or_title: String,
        val extension: String,
        val downloading: Boolean
    )

    companion object {
        fun getDownloadPath(song: Song, extension: String, in_progress: Boolean, context: AppContext): String {
            if (in_progress) {
                return "${song.id}.$extension$FILE_DOWNLOADING_SUFFIX"
            }
            else {
                return "${song.getActiveTitle(context.database)}.$extension"
            }
        }

        fun isFileDownloadInProgressForSong(file: PlatformFile, song: Song): Boolean =
            file.name.endsWith(FILE_DOWNLOADING_SUFFIX) && file.name.startsWith("${song.id}.")

        fun getSongIdOfInProgressDownload(file: PlatformFile): String? =
            if (file.name.endsWith(FILE_DOWNLOADING_SUFFIX)) file.name.split('.', limit = 2).first() else null
    }

    private var notification_builder: NotificationCompat.Builder? = null
    private lateinit var notification_manager: NotificationManagerCompat

    private val song_download_dir: PlatformFile get() = PlayerDownloadManager.getSongDownloadDir(context)

    private val downloads: MutableList<Download> = mutableListOf()
    private val executor = Executors.newFixedThreadPool(3)
    private var stopping = false

    private var start_time: Long = 0
    private var completed_downloads: Int = 0
    private var failed_downloads: Int = 0
    private var cancelled: Boolean = false
    private var notification_update_time: Long = -1

    private var paused: Boolean = false
        set(value) {
            field = value
            pause_resume_action.title = if (value) getString("action_download_resume") else getString("action_download_pause")
        }

    private lateinit var notification_delete_intent: PendingIntent
    private lateinit var pause_resume_action: NotificationCompat.Action

    override fun onCreate() {
        super.onCreate()

        initResources(context.getUiLanguage(), context)

        synchronized(downloads) {
            downloads.clear()
        }

        notification_manager = NotificationManagerCompat.from(this)
        notification_delete_intent = PendingIntent.getService(
            this,
            IntentAction.STOP.ordinal,
            Intent(this, PlayerDownloadService::class.java).putExtra("action", IntentAction.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )
        pause_resume_action = NotificationCompat.Action.Builder(
            IconCompat.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString("action_download_pause") ?: "Pause",
            PendingIntent.getService(
                this,
                IntentAction.PAUSE_RESUME.ordinal,
                Intent(this, PlayerDownloadService::class.java).putExtra("action", IntentAction.PAUSE_RESUME),
                PendingIntent.FLAG_IMMUTABLE
            )
        ).build()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        try {
            downloads.clear()
        }
        catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onMessage(data: Any?) {
        require(data is PlayerDownloadManager.PlayerDownloadMessage)
        onActionIntentReceived(data)
    }

    private fun onActionIntentReceived(message: PlayerDownloadManager.PlayerDownloadMessage) {
        when (message.action) {
            IntentAction.STOP -> {
                SpMp.Log.info("Download service stopping...")
                synchronized(executor) {
                    stopping = true
                }
                stopSelf()
            }
            IntentAction.START_DOWNLOAD -> startDownload(message)
            IntentAction.CANCEL_DOWNLOAD -> cancelDownload(message)
            IntentAction.CANCEL_ALL -> cancelAllDownloads(message)
            IntentAction.PAUSE_RESUME -> {
                paused = !paused
                onDownloadProgress()
            }
            IntentAction.STATUS_CHANGED -> throw IllegalStateException("STATUS_CHANGED is for output only")
        }
    }

    private fun startDownload(message: PlayerDownloadManager.PlayerDownloadMessage) {
        require(message.instance != null)

        val download = getOrCreateDownload(context, SongRef(message.data["song_id"] as String))

        val silent = message.data["silent"] as Boolean
        if (!silent) {
            download.silent = false
        }

        synchronized(download) {
            if (download.finished) {
                download.broadcastResult(Result.success(download.song_file), message.instance)
                return
            }

            if (download.downloading) {
                if (paused) {
                    paused = false
                }
                download.broadcastResult(null, message.instance)
                return
            }

            synchronized(downloads) {
                if (downloads.isEmpty()) {
                    if (!download.silent) {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED
                        ) {
                            context.sendToast("(BUG) No notification permission")
                            return
                        }

                        notification_builder = getNotificationBuilder()
                        startForeground(NOTIFICATION_ID, notification_builder!!.build())
                    }
                    start_time = System.currentTimeMillis()
                    completed_downloads = 0
                    failed_downloads = 0
                    cancelled = false
                }

                downloads.add(download)
                download.broadcastStatus(true)
            }
        }

        onDownloadProgress()

        executor.submit {
            runBlocking {
                var result: Result<PlatformFile?>? = null
                var retry_count: Int = 0

                while (
                    retry_count++ < DOWNLOAD_MAX_RETRY_COUNT && (
                        result == null || download.status == DownloadStatus.Status.IDLE || download.status == DownloadStatus.Status.PAUSED
                    )
                ) {
                    if (paused && !download.cancelled) {
                        onDownloadProgress()
                        delay(500)
                        continue
                    }

                    result =
                        try {
                            performDownload(download)
                        }
                        catch (e: Exception) {
                            Result.failure(e)
                        }
                }

                synchronized(downloads) {
                    downloads.removeAll { it.song.id == download.song.id }

                    if (downloads.isEmpty()) {
                        cancelled = download.cancelled
                        stopForeground(false)
                    }

                    if (result?.isSuccess == true) {
                        completed_downloads += 1
                    }
                    else {
                        failed_downloads += 1
                    }
                }

                download.broadcastResult(result, message.instance)

                if (notification_update_time > 0) {
                    val delay_duration = 1000 - (System.currentTimeMillis() - notification_update_time)
                    if (delay_duration > 0) {
                        delay(delay_duration)
                    }
                }
                onDownloadProgress()
            }
        }
    }

    private fun cancelDownload(message: PlayerDownloadManager.PlayerDownloadMessage) {
        val id = message.data["id"] as String
        synchronized(downloads) {
            downloads.firstOrNull { it.song.id == id }?.cancel()
        }
    }

    private fun cancelAllDownloads(message: PlayerDownloadManager.PlayerDownloadMessage) {
        SpMp.Log.info("Download manager cancelling all downloads $downloads")
        synchronized(downloads) {
            downloads.forEach { it.cancel() }
        }
    }

    private suspend fun performDownload(download: Download): Result<PlatformFile?> = withContext(Dispatchers.IO) {
        val format: YoutubeVideoFormat = getSongFormatByQuality(download.song.id, download.quality, context).fold(
            { it },
            { return@withContext Result.failure(it) }
        )

        val connection: HttpURLConnection = URL(format.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.setRequestProperty("Range", "bytes=${download.downloaded}-")

        try {
            connection.connect()
        }
        catch (e: Throwable) {
            return@withContext Result.failure(RuntimeException(connection.url.toString(), e))
        }

        if (connection.responseCode != 200 && connection.responseCode != 206) {
            return@withContext Result.failure(ConnectException(
                "${download.song.id}: Server returned code ${connection.responseCode} ${connection.responseMessage}"
            ))
        }

        var file: PlatformFile? = download.song_file
        check(song_download_dir.mkdirs()) { song_download_dir.toString() }

        val file_extension: String = when (connection.contentType) {
            "audio/webm" -> "webm"
            "audio/mp4" -> "mp4"
            else -> return@withContext Result.failure(NotImplementedError(connection.contentType))
        }

        if (file == null) {
            file = song_download_dir.resolve(download.generatePath(file_extension, true))
        }

        check(file.name.endsWith(FILE_DOWNLOADING_SUFFIX))
        val target_filename: String = download.generatePath(file_extension, false)

        val data: ByteArray = ByteArray(4096)
        val output: OutputStream = file.outputStream(true)
        val input: InputStream = connection.inputStream

        fun close(status: DownloadStatus.Status) {
            input.close()
            output.close()
            connection.disconnect()
            download.status = status
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                download.total_size = connection.contentLengthLong + download.downloaded
            }
            else {
                download.total_size = connection.contentLength + download.downloaded
            }
            download.status = DownloadStatus.Status.DOWNLOADING

            while (true) {
                val size = input.read(data)
                if (size < 0) {
                    break
                }

                synchronized(executor) {
                    if (stopping || download.cancelled) {
                        close(DownloadStatus.Status.CANCELLED)
                        return@withContext Result.success(null)
                    }
                    if (paused) {
                        close(DownloadStatus.Status.PAUSED)
                        return@withContext Result.success(null)
                    }
                }

                download.downloaded += size
                output.write(data, 0, size)

                onDownloadProgress()
            }

            val metadata_retriever: MediaMetadataRetriever = MediaMetadataRetriever()
            metadata_retriever.setDataSource(context.ctx, Uri.parse(file.uri))

            val duration_ms: Long? = metadata_retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            download.song.Duration.setNotNull(duration_ms, context.database)

            runBlocking {
                launch {
                    LocalSongMetadataProcessor.addMetadataToLocalSong(download.song, file, target_filename, context)
                }
                launch {
                    if (download.lyrics_file == null) {
                        val lyrics_file: PlatformFile = MediaItemLibrary.getLocalLyricsFile(download.song, context)

                        SongLyricsLoader.loadBySong(download.song, context)?.onSuccess { lyrics ->
                            with (LyricsFileConverter) {
                                lyrics.saveToFile(lyrics_file, context)
                            }
                        }
                    }
                }
            }

            close(DownloadStatus.Status.FINISHED)
        }
        catch (e: Throwable) {
            e.printStackTrace()
            close(DownloadStatus.Status.CANCELLED)
        }

        val renamed: PlatformFile = file.renameTo(target_filename)
        download.song_file = renamed
        download.status = DownloadStatus.Status.FINISHED

        return@withContext Result.success(download.song_file)
    }

    private fun getNotificationText(): String {
        var text = ""
        var additional = ""

        synchronized(downloads) {
            var downloading = 0
            for (dl in downloads) {
                if (dl.status != DownloadStatus.Status.DOWNLOADING && dl.status != DownloadStatus.Status.PAUSED) {
                    continue
                }

                if (text.isNotEmpty()) {
                    text += ", ${dl.percent_progress}%"
                }
                else {
                    text += "${dl.percent_progress}%"
                }

                downloading += 1
            }

            if (downloading < downloads.size) {
                additional += "${downloads.size - downloading} queued"
            }
            if (completed_downloads > 0) {
                if (additional.isNotEmpty()) {
                    additional += ", $completed_downloads finished"
                }
                else {
                    additional += "$completed_downloads finished"
                }
            }
            if (failed_downloads > 0) {
                if (additional.isNotEmpty()) {
                    additional += ", $failed_downloads failed"
                }
                else {
                    additional += "$failed_downloads failed"
                }
            }
        }

        return if (additional.isEmpty()) text else "$text ($additional)"
    }

    private fun onDownloadProgress() {
        synchronized(downloads) {
            if (downloads.isNotEmpty() && downloads.all { it.silent }) {
                return
            }

            notification_builder?.also { builder ->
                notification_update_time = System.currentTimeMillis()
                val total_progress = getTotalDownloadProgress()

                if (!downloads.any { !it.silent }) {
                    builder.setProgress(0, 0, false)
                    builder.setOngoing(false)
                    builder.setDeleteIntent(notification_delete_intent)

                    if (cancelled) {
                        builder.setContentTitle("Download cancelled")
                        builder.setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                    }
                    else if (completed_downloads == 0) {
                        builder.setContentTitle("Download failed")
                        builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    }
                    else {
                        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
                        return
                    }

                    builder.setContentText("")
                }
                else {
                    builder.setProgress(100, (total_progress * 100).toInt(), false)

                    var title: String? = null
                    if (downloads.size == 1) {
                        val song_title = downloads.first().song.getActiveTitle(context.database)
                        if (song_title != null) {
                            title = getString("downloading_song_\$title").replace("\$title", song_title)
                        }
                    }

                    if (title == null) {
                        title = getString("downloading_\$x_songs").replace("\$x", downloads.size.toString())
                    }

                    builder.setContentTitle(if (paused) "$title (paused)" else title)
                    builder.setContentText(getNotificationText())

                    val elapsed_minutes = ((System.currentTimeMillis() - start_time) / 60000f).toInt()
                    builder.setSubText(
                        if (elapsed_minutes == 0) getString("download_just_started")
                        else getString("download_started_\$x_minutes_ago").replace("\$x", elapsed_minutes.toString())
                    )
                }

                if (ActivityCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
                    try {
                        NotificationManagerCompat.from(this).notify(
                            NOTIFICATION_ID, builder.build().apply {
                                if (downloads.isEmpty() || total_progress == 1f) {
                                    actions = arrayOf<Notification.Action>()
                                }
                            }
                        )
                    }
                    catch (_: Throwable) {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.extras?.get("action")
        if (action is IntentAction) {
            SpMp.Log.info("Download service received action $action")
            onActionIntentReceived(
                PlayerDownloadManager.PlayerDownloadMessage(
                    action,
                    emptyMap()
                )
            )
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun getNotificationBuilder(): NotificationCompat.Builder {
        val content_intent: PendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this@PlayerDownloadService, AppContext.main_activity),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, getNotificationChannel())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(content_intent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 0, false)
            .addAction(pause_resume_action)
            .addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString("action_cancel"),
                    PendingIntent.getService(
                        this,
                        IntentAction.CANCEL_ALL.ordinal,
                        Intent(this, PlayerDownloadService::class.java).putExtra("action", IntentAction.CANCEL_ALL),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    foregroundServiceBehavior = Notification.FOREGROUND_SERVICE_IMMEDIATE
                }
            }
    }

    private fun getNotificationChannel(): String {
        val channel =
            NotificationChannelCompat.Builder(
                NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_LOW
            )
            .setName(getString("download_service_name"))
            .setSound(null, null)
            .build()

        notification_manager.createNotificationChannel(channel)
        return NOTIFICATION_CHANNEL_ID
    }

    inner class ServiceBinder: PlatformBinder() {
        fun getService(): PlayerDownloadService = this@PlayerDownloadService
    }
    private val binder = ServiceBinder()
    override fun onBind() = binder
}
