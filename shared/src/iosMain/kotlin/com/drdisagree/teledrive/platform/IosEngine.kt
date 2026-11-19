package com.drdisagree.teledrive.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.drdisagree.teledrive.core.crypto.CredentialCipher
import com.drdisagree.teledrive.core.crypto.FileWrappedKeyRepository
import com.drdisagree.teledrive.core.crypto.KeyBackupCodec
import com.drdisagree.teledrive.core.crypto.KeychainCredentialStore
import com.drdisagree.teledrive.core.crypto.KeychainTdlibDatabaseKeyProvider
import com.drdisagree.teledrive.core.crypto.PassphraseKdf
import com.drdisagree.teledrive.core.crypto.SecureFileDeleter
import com.drdisagree.teledrive.core.crypto.SecureStore
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.LocalCopyDeleter
import com.drdisagree.teledrive.core.files.PendingShare
import com.drdisagree.teledrive.core.files.sharedFilesModule
import com.drdisagree.teledrive.core.io.File
import com.drdisagree.teledrive.core.io.absolutePath
import com.drdisagree.teledrive.core.media.MediaMetadataExtractor
import com.drdisagree.teledrive.core.media.ThumbnailMemoryCache
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.network.NetworkMonitor
import com.drdisagree.teledrive.core.power.PowerMonitor
import com.drdisagree.teledrive.core.proxy.ProxyProbe
import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.security.AppLockManager
import com.drdisagree.teledrive.core.telegram.IosTelegramClient
import com.drdisagree.teledrive.core.telegram.IosTelegramFactory
import com.drdisagree.teledrive.core.telegram.TdJsonTransport
import com.drdisagree.teledrive.core.telegram.TdlibDatabaseKeyProvider
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramPacer
import com.drdisagree.teledrive.core.transfer.BackupResumeScheduler
import com.drdisagree.teledrive.core.transfer.BackupSessionTracker
import com.drdisagree.teledrive.core.transfer.CountingBackupSessionTracker
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.transfer.TransferErrorMessages
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.core.transfer.transferEngineModule
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.database.daosModule
import com.drdisagree.teledrive.data.repository.LocalDataWiper
import com.drdisagree.teledrive.data.repository.repositoryModule
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.usecase.useCaseModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.Path.Companion.toPath
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * The shared engine (sync, transfers, encryption, Room, DataStore) wired for iOS. The Swift host
 * hands over its TDLib bridge and Keychain store; everything else is Kotlin.
 */
object IosEngine {

    private var koin: Koin? = null

    fun start(transport: TdJsonTransport, secureStore: SecureStore): Koin {
        koin?.let { return it }
        val started = startKoin { modules(iosModule(transport, secureStore)) }.koin
        koin = started
        return started
    }

    /**
     * Stage 2 kept the API id/hash in the Keychain only; the engine reads them through
     * SettingsRepository, so carry them over once.
     */
    suspend fun migrateStage2Credentials(koin: Koin) {
        val settings = koin.get<SettingsRepository>()
        if (settings.getTelegramCredentials() != null) return
        val legacy = KeychainCredentialStore(koin.get()).load() ?: return
        settings.setTelegramCredentials(legacy)
    }

    private fun iosModule(transport: TdJsonTransport, secureStore: SecureStore): Module = module {
        includes(
            daosModule,
            repositoryModule,
            useCaseModule,
            sharedFilesModule,
            transferEngineModule
        )
        single<SecureStore> { secureStore }
        single<TdJsonTransport> { transport }
        single { IosStoragePaths() } bind AppStoragePaths::class
        single<CredentialCipher> { KeychainCredentialCipher(get()) }
        single<WrappedKeyRepository> { FileWrappedKeyRepository(get(), get()) }
        single<TdlibDatabaseKeyProvider> { KeychainTdlibDatabaseKeyProvider(get()) }
        singleOf(::DefaultDispatcherProvider) { bind<DispatcherProvider>() }
        singleOf(::StreamCrypto)
        singleOf(::SecureFileDeleter)
        singleOf(::PassphraseKdf)
        singleOf(::KeyBackupCodec)
        singleOf(::TelegramPacer)
        single { ProxyProbe() }
        singleOf(::AppLockManager)
        singleOf(::PublishOutboxDrainer)
        single<NetworkMonitor> { IosNetworkMonitor() }
        single<PowerMonitor> { IosPowerMonitor() }
        single<TransferErrorMessages> { IosTransferErrorMessages() }
        single<DownloadWriter> { IosDownloadWriter(get(), get()) }
        single<FileImporter> { IosFileImporter(get()) }
        single<PendingShare> { IosPendingShare() }
        single<LocalCopyDeleter> { IosLocalCopyDeleter() }
        single<MediaMetadataExtractor> { IosMediaMetadataExtractor() }
        single<ThumbnailStore> { NoopThumbnailStore() }
        single<ThumbnailMemoryCache> { NoopThumbnailMemoryCache() }
        singleOf(::CountingBackupSessionTracker) { bind<BackupSessionTracker>() }
        single<TransferScheduler> { IosTransferScheduler(get(), get()) }
        single<BackupResumeScheduler> { IosBackupResumeScheduler() }
        single<MaintenanceScheduler> { IosMaintenanceScheduler() }
        single<PublishScheduler> { IosPublishScheduler(get(), get()) }
        single<LocalDataWiper> { IosLocalDataWiper(get(), get(), get()) }
        single { IosTelegramFactory.create(get(), get()) }
        single<TelegramClient> { get<IosTelegramClient>() }
        single<DataStore<Preferences>> {
            val directory = File(get<AppStoragePaths>().filesDir, "datastore").apply { mkdirs() }
            PreferenceDataStoreFactory.createWithPath {
                File(directory, "settings.preferences_pb").absolutePath.toPath()
            }
        }
        single<TeleDriveDatabase> {
            Room.databaseBuilder<TeleDriveDatabase>(
                name = File(get<AppStoragePaths>().filesDir, "teledrive.db").absolutePath
            )
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        }
    }
}
