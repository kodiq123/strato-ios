package com.drdisagree.teledrive.platform

import com.drdisagree.teledrive.core.common.nowMillis
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.crypto.toNSData
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.IosFiles.toByteArray
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.files.Urls
import com.drdisagree.teledrive.core.io.File
import com.drdisagree.teledrive.core.io.absolutePath
import com.drdisagree.teledrive.core.io.name
import com.drdisagree.teledrive.core.io.readBytes
import com.drdisagree.teledrive.core.io.readText
import com.drdisagree.teledrive.core.io.writeBytes
import com.drdisagree.teledrive.core.media.ApkIconExtractor
import com.drdisagree.teledrive.core.media.MediaPart
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.ThumbnailDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.ThumbnailEntity
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * The desktop store's logic (cache, sealing, Telegram fallbacks) with UIKit doing the image work:
 * decode, scale to fit [MAX_DIMENSION] and encode JPEG.
 */
class IosThumbnailStore(
    private val storagePaths: AppStoragePaths,
    private val fileDao: FileDao,
    private val thumbnailDao: ThumbnailDao,
    private val telegramClient: TelegramClient,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val settingsRepository: SettingsRepository
) : ThumbnailStore {

    private val generationSemaphore = Semaphore(3)

    override suspend fun thumbnailBytes(fileId: String): ByteArray? {
        if (!settingsRepository.preferences.first().linkPreviews && isNote(fileId)) {
            thumbnailDao.delete(fileId)
            return null
        }
        thumbnailDao.byFileId(fileId)?.let { cached ->
            val file = File(cached.path)
            if (file.exists()) {
                thumbnailDao.touch(fileId, nowMillis())
                val raw = file.readBytes()
                return if (cached.encrypted) {
                    runCatching { streamCrypto.decryptBytes(thumbnailKey(), raw) }.getOrNull()
                } else raw
            }
            thumbnailDao.delete(fileId)
        }
        return generationSemaphore.withPermit { generate(fileId) }
    }

    override suspend fun uploadThumbnailFile(fileId: String): File? {
        val bytes = thumbnailBytes(fileId) ?: return null
        val dir = File(storagePaths.cacheDir, "upload-thumbs").apply { mkdirs() }
        val target = File(dir, "$fileId.jpg")
        return runCatching {
            if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                target.writeBytes(bytes)
            }
            target
        }.getOrNull()
    }

    private suspend fun isNote(fileId: String): Boolean =
        fileDao.byId(fileId)?.let { MimeTypes.isText(it.mimeType) } == true

    private suspend fun generate(fileId: String): ByteArray? {
        val entity = fileDao.byId(fileId) ?: return null
        val local = entity.localPath?.let { path ->
            val file = File(path)
            when {
                !file.exists() -> null
                MimeTypes.isText(entity.mimeType) -> linkThumbnail(file)
                MimeTypes.isImage(entity.mimeType) -> decodeImageThumbnail(path)
                MimeTypes.isApk(entity.mimeType, file.name) -> decodeApkIcon(file)
                else -> null
            }
        }

        val jpegBytes = if (local != null) {
            toJpeg(local)
        } else {
            val iconFileId = entity.iconFileId
            if (iconFileId != null) {
                fetchIconBytes(entity, iconFileId) ?: fetchRemoteThumbnail(entity)
            } else {
                fetchRemoteThumbnail(entity)
            }
        } ?: return null
        return save(fileId, jpegBytes)
    }

    override suspend fun storeFromDecrypted(fileId: String, plain: File) {
        if (thumbnailDao.byFileId(fileId)?.let { File(it.path).exists() } == true) return
        val entity = fileDao.byId(fileId) ?: return
        if (!MimeTypes.isImage(entity.mimeType)) return
        generationSemaphore.withPermit {
            val image = decodeImageThumbnail(plain.absolutePath) ?: return@withPermit
            val jpeg = toJpeg(image) ?: return@withPermit
            save(fileId, jpeg)
        }
    }

    // Frames from a stream need AVFoundation, which arrives with the player in stage 5.
    override suspend fun storeFromStream(fileId: String, parts: List<MediaPart>, encrypted: Boolean) = Unit

    private suspend fun save(fileId: String, jpegBytes: ByteArray): ByteArray {
        val encrypt = settingsRepository.preferences.first().encryptThumbnails
        val stored = if (encrypt) streamCrypto.encryptBytes(thumbnailKey(), jpegBytes) else jpegBytes

        val dir = File(storagePaths.cacheDir, "thumbnails").apply { mkdirs() }
        val target = File(dir, "$fileId.thumb")
        runCatching { target.writeBytes(stored) }.onFailure { return jpegBytes }

        val now = nowMillis()
        thumbnailDao.upsert(
            ThumbnailEntity(
                fileId = fileId,
                path = target.absolutePath,
                encrypted = encrypt,
                width = MAX_DIMENSION,
                height = MAX_DIMENSION,
                sizeBytes = stored.size.toLong(),
                generatedAt = now,
                lastAccessAt = now
            )
        )
        return jpegBytes
    }

    private suspend fun linkThumbnail(file: File): UIImage? {
        if (file.length() > LINK_NOTE_LIMIT) return null
        if (!settingsRepository.preferences.first().linkPreviews) return null
        val url = Urls.sole(file.readText()) ?: return null
        val imagePath = telegramClient
            .linkPreview(Urls.normalize(url), withImage = true)
            ?.imagePath
            ?: return null
        return imagePath.takeIf { File(it).exists() }?.let(::decodeImageThumbnail)
    }

    private fun decodeApkIcon(file: File): UIImage? {
        val bytes = ApkIconExtractor.extractIconBytes(file) ?: return null
        return UIImage(data = bytes.toNSData())
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun decodeImageThumbnail(path: String): UIImage? {
        val original = UIImage.imageWithContentsOfFile(path) ?: return null
        val (width, height) = original.size.useContents { width to height }
        if (width <= 0.0 || height <= 0.0) return null
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) return original
        val scale = minOf(MAX_DIMENSION / width, MAX_DIMENSION / height)
        val target = CGSizeMake(
            (width * scale).toLong().coerceAtLeast(1).toDouble(),
            (height * scale).toLong().coerceAtLeast(1).toDouble()
        )
        return original.imageByPreparingThumbnailOfSize(target)
    }

    /** JPEG has no alpha, so UIKit flattens transparency; quality matches ImageIO's default. */
    private fun toJpeg(image: UIImage): ByteArray? =
        UIImageJPEGRepresentation(image, JPEG_QUALITY)?.toByteArray()

    private suspend fun fetchIconBytes(entity: FileEntity, iconFileId: String): ByteArray? {
        var readyPath: String? = null
        val rawRemoteFileId = iconFileId.substringBefore(":")
        runCatching {
            telegramClient.downloadDocument(rawRemoteFileId).collect { event ->
                if (event is TelegramDownloadEvent.Completed) readyPath = event.localPath
            }
        }
        val path = readyPath ?: return null
        val rawBytes = runCatching { File(path).readBytes() }.getOrNull() ?: return null
        return if (entity.isEncrypted) {
            val key = wrappedKeyRepository.get(CryptoKeys.CONTENT) ?: return null
            runCatching { streamCrypto.decryptBytes(key, rawBytes) }.getOrNull()
        } else {
            rawBytes
        }
    }

    private suspend fun fetchRemoteThumbnail(entity: FileEntity): ByteArray? {
        val chatId = entity.chatId ?: return null
        val messageId = entity.messageId ?: return null
        if (MimeTypes.isText(entity.mimeType) &&
            !settingsRepository.preferences.first().linkPreviews
        ) return null
        return runCatching { telegramClient.fetchThumbnail(chatId, messageId) }.getOrNull()
    }

    private fun thumbnailKey(): ByteArray = wrappedKeyRepository.getOrCreate(CryptoKeys.THUMBNAIL)

    private companion object {
        const val LINK_NOTE_LIMIT = 8L * 1024
        const val MAX_DIMENSION = 512
        const val JPEG_QUALITY = 0.75
    }
}
