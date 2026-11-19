package com.drdisagree.teledrive.platform

import com.drdisagree.teledrive.core.crypto.AesGcm
import com.drdisagree.teledrive.core.crypto.CredentialCipher
import com.drdisagree.teledrive.core.crypto.SecureStore
import com.drdisagree.teledrive.core.crypto.secureRandomBytes
import com.drdisagree.teledrive.core.crypto.toNSData
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.FileNameUtils
import com.drdisagree.teledrive.core.files.FilePartialDownload
import com.drdisagree.teledrive.core.files.ImportSource
import com.drdisagree.teledrive.core.files.ImportedFile
import com.drdisagree.teledrive.core.files.IosFiles.toByteArray
import com.drdisagree.teledrive.core.files.LocalCleanup
import com.drdisagree.teledrive.core.files.LocalCopyDeleter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.files.PendingShare
import com.drdisagree.teledrive.core.files.expandDirectory
import com.drdisagree.teledrive.core.io.File
import com.drdisagree.teledrive.core.io.OutputStream
import com.drdisagree.teledrive.core.io.absolutePath
import com.drdisagree.teledrive.core.io.deleteRecursively
import com.drdisagree.teledrive.core.io.isFile
import com.drdisagree.teledrive.core.io.name
import com.drdisagree.teledrive.core.io.outputStream
import com.drdisagree.teledrive.core.io.parentFile
import com.drdisagree.teledrive.core.io.usableSpace
import com.drdisagree.teledrive.core.media.MediaInfo
import com.drdisagree.teledrive.core.media.MediaMetadataExtractor
import com.drdisagree.teledrive.core.media.MediaPart
import com.drdisagree.teledrive.core.media.ThumbnailMemoryCache
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.network.NetworkMonitor
import com.drdisagree.teledrive.core.network.NetworkStatus
import com.drdisagree.teledrive.core.power.PowerMonitor
import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.transfer.BackupResumeScheduler
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.transfer.TransferDrainResult
import com.drdisagree.teledrive.core.transfer.TransferErrorMessages
import com.drdisagree.teledrive.core.transfer.TransferQueueDrainer
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.repository.LocalDataWiper
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

private fun directory(kind: NSSearchPathDirectory): File {
    val path = NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull()
        as? String ?: error("No directory for $kind")
    return File(path).apply { mkdirs() }
}

/**
 * filesDir is Application Support itself, so TDLib's existing "tdlib" folder (stage 2) stays
 * where the engine expects it. Cache files go to Caches, which iOS may purge.
 */
class IosStoragePaths : AppStoragePaths {
    override val filesDir: File get() = directory(NSApplicationSupportDirectory)
    override val cacheDir: File get() = directory(NSCachesDirectory)
    override val externalCacheDir: File? = null
    override val externalStorageRoot: File? = null

    /** Visible in the Files app ("On My iPhone › Strato"). */
    val documentsDir: File get() = directory(NSDocumentDirectory)
}

/** AES-256-GCM under a random key that only the Keychain holds (the Android Keystore's role). */
class KeychainCredentialCipher(private val store: SecureStore) : CredentialCipher {

    private val key: ByteArray by lazy {
        store.read(KEY)?.toByteArray()?.takeIf { it.size == KEY_BYTES } ?: secureRandomBytes(KEY_BYTES)
            .also { check(store.write(KEY, it.toNSData())) { "Could not store the credential key" } }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val nonce = secureRandomBytes(NONCE_BYTES)
        return nonce + AesGcm.seal(key, nonce, null, plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > NONCE_BYTES) { "Ciphertext too short" }
        val nonce = ciphertext.copyOfRange(0, NONCE_BYTES)
        return AesGcm.open(key, nonce, null, ciphertext, NONCE_BYTES, ciphertext.size - NONCE_BYTES)
    }

    private companion object {
        const val KEY = "credential_cipher_key"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
    }
}

/** Downloads land in Documents (shown in the Files app), mirroring the folder path. */
class IosDownloadWriter(
    private val storagePaths: IosStoragePaths,
    private val settingsRepository: SettingsRepository
) : DownloadWriter {

    override fun write(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        body: (OutputStream) -> Unit
    ): String? = runCatching {
        val target = directoryFor(folderPath)
        target.mkdirs()
        val unique = FileNameUtils.uniqueName(FileNameUtils.sanitize(fileName)) { candidate ->
            File(target, candidate).exists()
        }
        val destination = File(target, unique)
        destination.outputStream().use(body)
        destination.absolutePath
    }.getOrNull()

    override fun openPartial(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        token: String?
    ): PartialDownload? = runCatching {
        FilePartialDownload.open(directoryFor(folderPath), fileName, token)
    }.getOrNull()

    override fun freeBytes(folderPath: String?): Long? = runCatching {
        val target = directoryFor(folderPath)
        target.mkdirs()
        target.usableSpace.takeIf { it > 0 }
    }.getOrNull()

    private fun directoryFor(folderPath: String?): File {
        val configured = runBlocking { settingsRepository.preferences.first() }.downloadDirectory
        val root = configured?.let(::File)?.takeIf { it.isDirectory() } ?: storagePaths.documentsDir
        return folderPath
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?.fold(root) { parent, segment -> File(parent, FileNameUtils.sanitize(segment)) }
            ?: root
    }
}

/** Picked files arrive as readable paths (the picker copies them into the sandbox). */
class IosFileImporter(private val storagePaths: AppStoragePaths) : FileImporter {

    override fun import(reference: String): ImportedFile? {
        val file = File(reference.removePrefix("file://"))
        if (!file.isFile) return null
        return ImportedFile(
            path = file.absolutePath,
            name = FileNameUtils.sanitize(file.name),
            sizeBytes = file.length()
        )
    }

    override fun discard(imported: ImportedFile) {
        if (isStaged(imported.path)) File(imported.path).delete()
    }

    override fun expand(reference: String): List<ImportSource> =
        expandDirectory(reference.removePrefix("file://")) ?: listOf(ImportSource(reference, ""))

    override fun isStaged(path: String): Boolean =
        File(path).parentFile == File(storagePaths.filesDir, IMPORT_DIR)

    override fun sweepOrphans(referencedPaths: Set<String>) {
        val staged = File(storagePaths.filesDir, IMPORT_DIR).listFiles() ?: return
        staged.filter { it.isFile && it.absolutePath !in referencedPaths }.forEach { it.delete() }
    }

    companion object {
        const val IMPORT_DIR = "imports"
    }
}

class IosPendingShare : PendingShare {
    private val _uris = MutableStateFlow<List<String>>(emptyList())
    override val uris: StateFlow<List<String>> = _uris.asStateFlow()
    private val _text = MutableStateFlow<String?>(null)
    override val text: StateFlow<String?> = _text.asStateFlow()

    override fun offer(incoming: List<String>) {
        if (incoming.isEmpty()) return
        _uris.update { incoming }
    }

    override fun clear() = _uris.update { emptyList() }

    override fun offerText(incoming: String?) {
        if (incoming.isNullOrBlank()) return
        _text.update { incoming }
    }

    override fun clearText() = _text.update { null }
}

class IosLocalCopyDeleter : LocalCopyDeleter {
    override fun delete(paths: List<String>): LocalCleanup {
        var deleted = 0
        for (path in paths) {
            val file = File(path)
            if (!file.exists() || file.delete()) deleted++
        }
        return LocalCleanup(deletedCount = deleted)
    }

    override fun isGone(path: String): Boolean = !File(path).exists()
}

/** Dimensions are optional metadata; reading them on iOS comes with the media work (stage 5). */
class IosMediaMetadataExtractor : MediaMetadataExtractor {
    override fun extract(file: File, mimeType: String): MediaInfo = MediaInfo(null, null, null)
}

/** No thumbnails until the gallery UI exists (stage 4); every caller handles null. */
class NoopThumbnailStore : ThumbnailStore {
    override suspend fun thumbnailBytes(fileId: String): ByteArray? = null
    override suspend fun uploadThumbnailFile(fileId: String): File? = null
    override suspend fun storeFromDecrypted(fileId: String, plain: File) = Unit
    override suspend fun storeFromStream(fileId: String, parts: List<MediaPart>, encrypted: Boolean) = Unit
}

class NoopThumbnailMemoryCache : ThumbnailMemoryCache {
    override fun remove(fileId: String) = Unit
    override fun clear() = Unit
}

/** Reachability via NWPathMonitor comes with background transfers (stage 5). */
class IosNetworkMonitor : NetworkMonitor {
    private val state = MutableStateFlow(NetworkStatus.UNMETERED)
    override val status: Flow<NetworkStatus> = state
    override fun currentStatus(): NetworkStatus = state.value
}

class IosPowerMonitor : PowerMonitor {
    override val charging: Flow<Boolean> = flowOf(true)
    override fun isCharging(): Boolean = true
}

/** Drains the queue while the app runs, like the desktop scheduler; BGTaskScheduler is stage 5. */
@OptIn(ExperimentalAtomicApi::class)
class IosTransferScheduler(
    private val drainer: TransferQueueDrainer,
    dispatchers: DispatcherProvider
) : TransferScheduler {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val draining = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)

    override fun kick(allowMetered: Boolean) {
        requested.store(true)
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (requested.exchange(false)) {
                    while (drainer.drain(isStopped = { false }, onTerminalFailure = { }) ==
                        TransferDrainResult.INTERRUPTED
                    ) {
                        delay(RETRY_DELAY_SECONDS.seconds)
                    }
                }
            } finally {
                draining.store(false)
            }
            if (requested.load()) kick(allowMetered)
        }
    }

    override fun rekick(allowMetered: Boolean) = kick(allowMetered)

    private companion object {
        const val RETRY_DELAY_SECONDS = 15
    }
}

@OptIn(ExperimentalAtomicApi::class)
class IosPublishScheduler(
    private val drainer: PublishOutboxDrainer,
    dispatchers: DispatcherProvider
) : PublishScheduler {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val draining = AtomicBoolean(false)

    override fun kick() {
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                var attempts = 0
                while (!drainer.drain(isStopped = { false }) && attempts++ < MAX_ATTEMPTS) {
                    delay(RETRY_DELAY_SECONDS.seconds)
                }
            } finally {
                draining.store(false)
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_SECONDS = 30
        const val MAX_ATTEMPTS = 10
    }
}

class IosBackupResumeScheduler : BackupResumeScheduler {
    override fun resumeWhen(chargingOnly: Boolean, wifiOnly: Boolean) = Unit
}

/** Maintenance runs only while the app is open until background tasks land (stage 5). */
class IosMaintenanceScheduler : MaintenanceScheduler {
    override fun scheduleUpdateCheck(enabled: Boolean) = Unit

    override fun scheduleAll(
        backupEnabled: Boolean,
        backupIntervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        instantBackup: Boolean,
        updateChecks: Boolean
    ) = Unit
}

class IosLocalDataWiper(
    private val database: TeleDriveDatabase,
    private val storagePaths: AppStoragePaths,
    private val secureStore: SecureStore
) : LocalDataWiper {
    override suspend fun wipe() {
        runCatching { database.close() }
        val files = storagePaths.filesDir
        for (name in WIPED_DIRS) runCatching { File(files, name).deleteRecursively() }
        files.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(DATABASE_PREFIX)) runCatching { file.delete() }
        }
        runCatching { storagePaths.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        WIPED_SECRETS.forEach(secureStore::remove)
    }

    private companion object {
        val WIPED_DIRS = listOf("tdlib", "keys", "datastore", "notes", IosFileImporter.IMPORT_DIR)
        val WIPED_SECRETS = listOf("tdlib_db_key", "credential_cipher_key", "telegram_credentials")
        const val DATABASE_PREFIX = "teledrive.db"
    }
}

class IosTransferErrorMessages : TransferErrorMessages {
    override val keyMissing = "Chave de criptografia ausente. Restaure-a nas configurações."
    override val noFileReference = "A transferência não tem referência de arquivo"
    override val fileRecordMissing = "Registro do arquivo ausente"
    override val noLocalCopy = "Nenhuma cópia local para enviar"
    override val localFileGone = "O arquivo local não existe mais"
    override val uploadEnded = "O envio terminou inesperadamente"
    override val uploadStalled = "O envio parou de responder"
    override val downloadEnded = "O download terminou inesperadamente"
    override val downloadStalled = "O download parou de responder"
    override val downloadedMissing = "Arquivo baixado não encontrado"
    override val noRemoteCopy = "O arquivo não tem cópia no Telegram"
    override val decryptionFailed = "Falha ao descriptografar"
    override val saveFailed = "Não foi possível salvar o download"
    override val verifyFailed = "O arquivo baixado não corresponde ao original. Baixe de novo."

    override fun destinationFull(neededBytes: Long): String =
        "Sem espaço livre onde os downloads são salvos. Necessário: ${bytes(neededBytes)}."

    override fun cacheFull(neededBytes: Long): String =
        "Sem espaço livre para os arquivos temporários. Necessário: ${bytes(neededBytes)}."

    private fun bytes(value: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        val rounded = (size * 10).toLong() / 10.0
        return if (unit == 0) "$value B" else "$rounded ${units[unit]}"
    }
}
