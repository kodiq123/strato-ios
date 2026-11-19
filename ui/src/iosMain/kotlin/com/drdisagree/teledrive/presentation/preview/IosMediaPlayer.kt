package com.drdisagree.teledrive.presentation.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.media.MediaByteSource
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_download
import com.drdisagree.teledrive.resources.player_codec_unsupported
import com.drdisagree.teledrive.resources.player_playback_failed
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.stringResource
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.resourceLoader
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create

/**
 * Plays a local file or a [MediaByteSource] in the system player. A streamed source is served
 * through a custom URL scheme to AVFoundation's resource loader, which asks for byte ranges as it
 * plays and seeks, so only what is watched gets downloaded and decrypted.
 *
 * [source] is created on first use and closed when the player leaves the screen.
 */
@Composable
internal fun IosMediaPlayer(
    key: String,
    fileName: String,
    mimeType: String,
    localPath: String?,
    source: (() -> MediaByteSource)?,
    onDownload: (() -> Unit)?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val session = remember(key) { PlayerSession(fileName, mimeType, localPath, source) }
    // The player shows and hides its own controls on a tap; the preview bars follow the same tap.
    val tapRelay = remember { TapRelay(onTap) }
    tapRelay.onTap = onTap
    DisposableEffect(session) {
        onDispose { session.release() }
    }

    var failed by remember(key) { mutableStateOf<PlaybackFailure?>(null) }
    LaunchedEffect(session) {
        // AVPlayerItem reports readiness through KVO; polling the status is simpler from Compose.
        while (isActive) {
            when (session.item.status) {
                AVPlayerItemStatusReadyToPlay -> return@LaunchedEffect
                AVPlayerItemStatusFailed -> {
                    SafeLog.w(TAG, "Playback failed: ${session.item.error?.localizedDescription}", null)
                    failed = if (session.sourceFailed) PlaybackFailure.Source else PlaybackFailure.Format
                    return@LaunchedEffect
                }
            }
            delay(STATUS_POLL_MS)
        }
    }

    when (failed) {
        null -> UIKitViewController(
            factory = {
                AVPlayerViewController().apply {
                    player = session.player
                    tapRelay.attachTo(view)
                    session.start()
                }
            },
            modifier = modifier.fillMaxSize()
        )

        else -> EmptyState(
            icon = Icons.Filled.Movie,
            title = stringResource(Res.string.player_playback_failed),
            description = if (failed == PlaybackFailure.Format) {
                stringResource(Res.string.player_codec_unsupported)
            } else {
                null
            },
            actionLabel = onDownload?.let { stringResource(Res.string.common_download) },
            onAction = onDownload,
            modifier = modifier.fillMaxSize()
        )
    }
}

private enum class PlaybackFailure { Format, Source }

@OptIn(ExperimentalForeignApi::class)
private class PlayerSession(
    fileName: String,
    mimeType: String,
    localPath: String?,
    sourceFactory: (() -> MediaByteSource)?
) {
    private val loader: StreamingResourceLoader?
    val item: AVPlayerItem
    val player: AVPlayer

    init {
        if (localPath != null || sourceFactory == null) {
            loader = null
            item = AVPlayerItem(uRL = NSURL.fileURLWithPath(localPath.orEmpty()))
        } else {
            loader = StreamingResourceLoader(sourceFactory, mimeType)
            // The extension helps AVFoundation pick a demuxer before the content type arrives.
            val extension = fileName.substringAfterLast('.', "").lowercase().ifEmpty { "mp4" }
            val asset = AVURLAsset(uRL = NSURL(string = "$STREAM_SCHEME://media/stream.$extension"), options = null)
            asset.resourceLoader.setDelegate(loader, queue = dispatch_queue_create("strato.stream", null))
            item = AVPlayerItem(asset = asset)
        }
        player = AVPlayer(playerItem = item)
    }

    val sourceFailed: Boolean get() = loader?.failed == true

    @OptIn(BetaInteropApi::class)
    fun start() {
        // Playback category: sound plays with the silent switch on, like any video app.
        runCatching {
            val audio = AVAudioSession.sharedInstance()
            audio.setCategory(AVAudioSessionCategoryPlayback, error = null)
            audio.setActive(true, error = null)
        }
        player.play()
    }

    fun release() {
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        loader?.close()
    }
}

/**
 * Answers AVFoundation's byte-range requests from a [MediaByteSource]. The sources keep one open
 * part and one decrypted frame, so reads are serialized; a request AVFoundation cancels (after a
 * seek, say) stops waiting at once.
 */
@OptIn(ExperimentalForeignApi::class)
private class StreamingResourceLoader(
    private val sourceFactory: () -> MediaByteSource,
    private val mimeType: String
) : NSObject(), AVAssetResourceLoaderDelegateProtocol {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readLock = Mutex()
    private val jobsLock = NSLock()
    private val jobs = mutableMapOf<AVAssetResourceLoadingRequest, Job>()
    private var source: MediaByteSource? = null

    @kotlin.concurrent.Volatile
    var failed: Boolean = false
        private set

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest
    ): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        val job = scope.launch {
            try {
                serve(request)
                request.finishLoading()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                SafeLog.w(TAG, "Stream read failed", error)
                failed = true
                request.finishLoadingWithError(NSError.errorWithDomain(STREAM_SCHEME, code = 1, userInfo = null))
            } finally {
                jobsLock.lock()
                jobs.remove(request)
                jobsLock.unlock()
            }
        }
        jobsLock.lock()
        jobs[request] = job
        jobsLock.unlock()
        return true
    }

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        didCancelLoadingRequest: AVAssetResourceLoadingRequest
    ) {
        jobsLock.lock()
        val job = jobs.remove(didCancelLoadingRequest)
        jobsLock.unlock()
        job?.cancel()
    }

    private suspend fun serve(request: AVAssetResourceLoadingRequest) {
        val size = readLock.withLock { openSource().size() }
        request.contentInformationRequest?.let { info ->
            info.contentType = UTType.typeWithMIMEType(mimeType)?.identifier ?: "public.mpeg-4"
            info.contentLength = size
            info.byteRangeAccessSupported = true
        }
        val data = request.dataRequest ?: return
        var offset = data.requestedOffset
        val end = if (data.requestsAllDataToEndOfResource) {
            size
        } else {
            minOf(size, data.requestedOffset + data.requestedLength)
        }
        while (offset < end) {
            val count = minOf(CHUNK_BYTES.toLong(), end - offset).toInt()
            val chunk = readLock.withLock { openSource().read(offset, count) }
            if (chunk.isEmpty()) break
            data.respondWithData(chunk.toNSData())
            offset += chunk.size
        }
    }

    private fun openSource(): MediaByteSource = source ?: sourceFactory().also { source = it }

    fun close() {
        scope.cancel()
        runCatching { source?.close() }
        source = null
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

private const val TAG = "IosMediaPlayer"
private const val STREAM_SCHEME = "strato-stream"
private const val CHUNK_BYTES = 256 * 1024
private const val STATUS_POLL_MS = 300L
