package com.mapbox.vision.sync.syncmanager

import android.content.Context
import com.mapbox.android.telemetry.AttachmentListener
import com.mapbox.android.telemetry.AttachmentMetadata
import com.mapbox.android.telemetry.MapboxTelemetry
import com.mapbox.vision.sync.telemetry.AttachmentManager
import com.mapbox.vision.sync.telemetry.TotalBytesCounter
import com.mapbox.vision.utils.UuidHolder
import com.mapbox.vision.utils.file.ZipFileCompressorImpl
import com.mapbox.vision.utils.prefs.TotalBytesCounterPrefs
import com.mapbox.vision.utils.threads.WorkThreadHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType

class TelemetrySyncManager(
    private val mapboxTelemetry: MapboxTelemetry,
    context: Context
) : SyncManager, AttachmentListener {

    companion object {
        private const val MAX_TELEMETRY_SIZE = 300 * 1024 * 1024L // 300 MB

        private val MEDIA_TYPE_ZIP: MediaType = MediaType.parse("application/zip")!!
        private val MEDIA_TYPE_MP4: MediaType = MediaType.parse("video/mp4")!!
        private const val FORMAT_MP4 = "mp4"
        private const val FORMAT_ZIP = "zip"
        private const val TYPE_VIDEO = "video"
        private const val TYPE_ZIP = "zip"
    }

    private val zipQueue = ConcurrentLinkedQueue<AttachmentProperties>()
    private val imageZipQueue = ConcurrentLinkedQueue<AttachmentProperties>()
    private val videoQueue = ConcurrentLinkedQueue<AttachmentProperties>()
    private val threadHandler = WorkThreadHandler()
    private val fileCompressor = ZipFileCompressorImpl()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ssZ", Locale.US)
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.US)
    private val bytesTracker = TotalBytesCounter.Impl(totalBytesCounterPrefs = TotalBytesCounterPrefs.Impl("telemetry"))
    private val uuidUtil = UuidHolder.Impl(context)
    @Suppress("DEPRECATION")
    private val language = context.resources.configuration.locale.language
    @Suppress("DEPRECATION")
    private val country = context.resources.configuration.locale.country

    private val uploadInProgress = AtomicBoolean(false)

    init {
        mapboxTelemetry.addAttachmentListener(this)
    }

    override fun start() {
        if (threadHandler.isStarted()) {
            return
        }

        zipQueue.clear()
        imageZipQueue.clear()
        videoQueue.clear()
        threadHandler.start()
        uploadInProgress.set(false)
    }

    override fun stop() {
        if (!threadHandler.isStarted()) {
            return
        }

        threadHandler.stop()
    }

    override fun syncSessionDir(path: String) {
        if (!threadHandler.isStarted()) {
            return
        }

        val dirFile = File(path)

        // path = ../Telemetry/Recordings_Country/timestamp/
        // quota need to be checked for Telemetry dir
        removeTelemetryOverQuota(dirFile.parentFile.parentFile)

        zipDataFiles("telemetry", path) { file ->
            file.name.endsWith("bin") || file.name.endsWith("json")
        }?.let { zippedTelemetry ->
            zipQueue.add(
                zippedTelemetry.toAttachmentProperties(
                    FORMAT_ZIP,
                    TYPE_ZIP,
                    MEDIA_TYPE_ZIP
                )
            )
        }

        zipDataFiles("images", path) { file ->
            file.name.endsWith("png")
        }?.let { zippedTelemetry ->
            imageZipQueue.add(
                zippedTelemetry.toAttachmentProperties(
                    FORMAT_ZIP,
                    TYPE_ZIP,
                    MEDIA_TYPE_ZIP
                )
            )
        }

        dirFile.listFiles { file ->
            file.name.endsWith("mp4")
        }?.forEach { videoFile ->
            videoQueue.add(
                videoFile.toAttachmentProperties(
                    FORMAT_MP4,
                    TYPE_VIDEO,
                    MEDIA_TYPE_MP4
                ).also {
                    val parentTimestamp = videoFile.parentFile.name.toLong()
                    val interval = videoFile.name.substringBeforeLast(".").split("_")
                    val startMillis = (interval[0].toFloat() * 1000).toLong()
                    val endMillis = (interval[1].toFloat() * 1000).toLong()
                    it.metadata.startTime = isoDateFormat.format(Date(parentTimestamp + startMillis))
                    it.metadata.endTime = isoDateFormat.format(Date(parentTimestamp + endMillis))
                }
            )
        }

        processQueues()
    }

    private fun removeTelemetryOverQuota(rootTelemetryDirectory: File) {
        val totalTelemetrySize = rootTelemetryDirectory.directorySizeRecursive()
        if (totalTelemetrySize > MAX_TELEMETRY_SIZE) {
            var bytesToRemove = totalTelemetrySize - MAX_TELEMETRY_SIZE

            val telemetryDirs = mutableListOf<File>()

            rootTelemetryDirectory.listFiles()?.forEach { countryDir ->
                countryDir.listFiles()?.let { timestampDir ->
                    telemetryDirs.addAll(timestampDir)
                }
            }

            telemetryDirs.sortBy { it.name }

            for (telemetryDir in telemetryDirs) {
                bytesToRemove -= telemetryDir.directorySizeRecursive()
                telemetryDir.deleteRecursively()
                zipQueue.removeAll {
                    it.absolutePath.contains(telemetryDir.path)
                }
                imageZipQueue.removeAll {
                    it.absolutePath.contains(telemetryDir.path)
                }
                videoQueue.removeAll {
                    it.absolutePath.contains(telemetryDir.path)
                }
                if (bytesToRemove <= 0) {
                    break
                }
            }
        }

        threadHandler.removeAllTasks()
    }

    private fun File.directorySizeRecursive(): Long = if (!isDirectory) {
        0
    } else {
        listFiles()
            .map {
                (if (it.isFile) it.length() else it.directorySizeRecursive())
            }
            .sum()
    }

    private fun File.toAttachmentProperties(
        format: String,
        type: String,
        mediaType: MediaType
    ): AttachmentProperties {
        val fileId = dateFormat.format(Date(parentFile.name.toLong()))

        return AttachmentProperties(
            absolutePath,
            AttachmentMetadata(
                name, "$fileId/$name", format, type,
                "${fileId}_${language}_${country}_${uuidUtil.uniqueId}_Android"
            ),
            mediaType
        )
    }

    private fun zipDataFiles(fileName: String, dirPath: String, filter: (File) -> Boolean): File? {
        val dirFile = File(dirPath)
        if (!dirFile.exists() || !dirFile.isDirectory) {
            return null
        }

        val filesToZip = dirFile.listFiles(filter)
        if (filesToZip.isEmpty()) {
            return null
        }

        val output = File(dirPath, "$fileName.$FORMAT_ZIP")
        if (!output.exists()) {
            output.createNewFile()
        }

        fileCompressor.compress(files = filesToZip, outFilePath = output.absolutePath)
        filesToZip.forEach {
            it.delete()
        }

        return output
    }

    private fun processQueues() {
        if (uploadInProgress.get()) return
        uploadInProgress.set(true)

        val attachment = zipQueue.peek() ?: imageZipQueue.peek() ?: videoQueue.peek()
        when {
            attachment != null -> {
                sendAttachment(attachment)
            }
            else -> uploadInProgress.set(false)
        }
    }

    private fun sendAttachment(attachmentProperties: AttachmentProperties) {
        val file = File(attachmentProperties.absolutePath)
        if (bytesTracker.trackSentBytes(file.length())) {
            sendFile(file, attachmentProperties)
        } else {
            threadHandler.postDelayed(
                {
                    sendAttachment(attachmentProperties)
                },
                bytesTracker.millisToNextSession()
            )
        }
    }

    private fun sendFile(
        file: File,
        attachmentProperties: AttachmentProperties
    ) {
        AttachmentManager(mapboxTelemetry).apply {
            addFileAttachment(
                filePath = file.absolutePath,
                mediaType = attachmentProperties.mediaType,
                attachmentMetadata = attachmentProperties.metadata
            )
            pushEvent()
        }
    }

    override fun onAttachmentResponse(message: String?, code: Int, fileIds: MutableList<String>?) {
        fileIds?.forEach { zipFileId ->
            if (zipQueue.removeByFileId(zipFileId)) return@forEach
            if (imageZipQueue.removeByFileId(zipFileId)) return@forEach
            videoQueue.removeByFileId(zipFileId)
        }
        uploadInProgress.set(false)
        processQueues()
    }

    private fun ConcurrentLinkedQueue<AttachmentProperties>.removeByFileId(fileId: String): Boolean =
        this.firstOrNull { it.metadata.fileId == fileId }
            ?.also { attachment ->
                File(attachment.absolutePath).delete()
                remove(attachment)
            } != null

    override fun onAttachmentFailure(message: String?, fileIds: MutableList<String>?) {
        uploadInProgress.set(false)
        processQueues()
    }
}

private data class AttachmentProperties(
    val absolutePath: String,
    val metadata: AttachmentMetadata,
    val mediaType: MediaType
)
