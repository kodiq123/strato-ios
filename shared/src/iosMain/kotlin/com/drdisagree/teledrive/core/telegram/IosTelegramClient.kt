package com.drdisagree.teledrive.core.telegram

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.files.IosFiles
import com.drdisagree.teledrive.domain.model.Country
import com.drdisagree.teledrive.domain.model.LinkMetadata
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import platform.Foundation.NSLock

/**
 * TelegramClient over TDLib's JSON interface, a port of the Android TdLibTelegramClient. Request
 * and field names follow td_api.tl of the bundled TDLibFramework (1.8.69), which differs from the
 * Android 1.8.56 binding in places (addProxy/testProxy take a proxy object, inputMessageDocument
 * wraps an inputDocument).
 *
 * @param tdlibDirectory per-install folder holding TDLib's db/ and files/ (Application Support).
 * @param networkType TDLib NetworkType name for the current route, e.g. "networkTypeWiFi".
 */
class IosTelegramClient(
    private val transport: TdJsonTransport,
    private val databaseKeyProvider: TdlibDatabaseKeyProvider,
    private val pacer: TelegramPacer,
    private val tdlibDirectory: String,
    private val deviceModel: String,
    private val systemVersion: String,
    private val applicationVersion: String,
    private val systemLanguageCode: String,
    private val networkType: () -> String = { "networkTypeOther" }
) : TelegramClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clientMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Current TDLib client id; doubles as the generation, so a stale client's updates are dropped. */
    @Volatile
    private var clientId: Int? = null

    @Volatile
    private var credentials: TelegramCredentials? = null

    @Volatile
    private var parametersReady = false

    @Volatile
    private var databaseDropped = false

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Uninitialized)
    override val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _connectionState = MutableStateFlow(TelegramConnectionState.CONNECTING)
    override val connectionState: StateFlow<TelegramConnectionState> =
        _connectionState.asStateFlow()

    private val updates = MutableSharedFlow<JsonObject>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val ownWrites = OwnWrites()

    private val pendingLock = NSLock()
    private val pending = HashMap<String, CompletableDeferred<JsonObject>>()
    private var nextRequestId = 0L

    @Volatile
    private var receiving = false

    // region lifecycle

    override suspend fun start(credentials: TelegramCredentials) {
        clientMutex.withLock {
            this.credentials = credentials
            val failed = _authState.value is TelegramAuthState.Failed
            if (clientId == null || failed || _authState.value == TelegramAuthState.Closed) {
                if (failed) {
                    clientId?.let { stale -> runCatching { sendRaw(stale, TdJson.obj("close")) } }
                    clientId = null
                }
                _authState.value = TelegramAuthState.Initializing
                parametersReady = false
                clientId = createClient()
            }
        }
    }

    private fun createClient(): Int {
        runCatching {
            transport.execute(
                TdJson.obj("setLogVerbosityLevel", "new_verbosity_level" to 1).toString()
            )
        }
        startReceiving()
        val id = transport.createClientId()
        // A JSON client only starts once it receives its first request.
        sendRaw(id, TdJson.obj("getOption", "name" to "version"))
        return id
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun startReceiving() {
        if (receiving) return
        receiving = true
        val receiveThread = newSingleThreadContext("tdlib-receive")
        scope.launch(receiveThread) {
            while (isActive) {
                val raw = runCatching { transport.receive(RECEIVE_TIMEOUT_SECONDS) }
                    .onFailure { SafeLog.e(TAG, "TDLib receive failed", it) }
                    .getOrNull() ?: continue
                val message = runCatching { json.parseToJsonElement(raw).jsonObject }
                    .getOrNull() ?: continue
                dispatch(message)
            }
        }
    }

    private fun dispatch(message: JsonObject) {
        val extra = message.str("@extra")
        if (extra != null) {
            val waiter = locked { pending.remove(extra) }
            waiter?.complete(message)
            return
        }
        val origin = message.int("@client_id")
        handleUpdate(origin, message)
    }

    private fun handleUpdate(origin: Int?, update: JsonObject) {
        if (update.type == "updateMessageSendSucceeded") {
            update.obj("message")?.let { message ->
                ownWrites.wroteCaption(
                    message.long("id") ?: 0L,
                    message.obj("content")?.captionText().orEmpty()
                )
            }
        }
        if (origin == null || origin == clientId) updates.tryEmit(update)
        when (update.type) {
            "updateAuthorizationState" ->
                update.obj("authorization_state")?.let { handleAuthorizationState(origin, it) }

            "updateConnectionState" -> if (origin == clientId) {
                _connectionState.value = when (update.obj("state")?.type) {
                    "connectionStateWaitingForNetwork" -> TelegramConnectionState.WAITING_FOR_NETWORK
                    "connectionStateConnecting",
                    "connectionStateConnectingToProxy" -> TelegramConnectionState.CONNECTING

                    "connectionStateUpdating" -> TelegramConnectionState.UPDATING
                    else -> TelegramConnectionState.READY
                }
            }
        }
    }

    private fun handleAuthorizationState(origin: Int?, state: JsonObject) {
        SafeLog.d(TAG, "Auth state ${state.type} client=$origin/$clientId")
        when (state.type) {
            "authorizationStateWaitTdlibParameters" ->
                scope.launch { sendTdlibParameters() }

            "authorizationStateWaitPhoneNumber" ->
                _authState.value = TelegramAuthState.WaitingForPhoneNumber

            "authorizationStateWaitCode" -> {
                val info = state.obj("code_info")
                val codeType = info?.obj("type")
                _authState.value = TelegramAuthState.WaitingForCode(
                    phoneNumber = info?.str("phone_number").orEmpty(),
                    channel = codeType.toChannel(),
                    codeLength = codeType?.int("length")?.takeIf { it > 0 },
                    resendTimeoutSeconds = info?.int("timeout") ?: 0
                )
            }

            "authorizationStateWaitOtherDeviceConfirmation" -> {
                SafeLog.d(TAG, "QR login token refreshed")
                _authState.value = TelegramAuthState.WaitingForQrScan(state.str("link").orEmpty())
            }

            "authorizationStateWaitEmailAddress" ->
                _authState.value = TelegramAuthState.WaitingForEmailAddress

            "authorizationStateWaitEmailCode" -> {
                val info = state.obj("code_info")
                _authState.value = TelegramAuthState.WaitingForEmailCode(
                    emailPattern = info?.str("email_address_pattern").orEmpty(),
                    codeLength = info?.int("length")?.takeIf { it > 0 }
                )
            }

            "authorizationStateWaitPassword" ->
                _authState.value = TelegramAuthState.WaitingForPassword(
                    state.str("password_hint")?.takeIf { it.isNotEmpty() }
                )

            "authorizationStateWaitRegistration" ->
                _authState.value = TelegramAuthState.RegistrationRequired

            // Newer TDLib can require Premium to sign in from some regions; that is out of scope
            // for this app, as account creation is.
            "authorizationStateWaitPremiumPurchase" ->
                _authState.value = TelegramAuthState.Failed(
                    "Telegram requires a Premium subscription to sign in with this number. " +
                            "Sign in with an official Telegram app first."
                )

            "authorizationStateReady" -> _authState.value = TelegramAuthState.Ready
            "authorizationStateLoggingOut" -> _authState.value = TelegramAuthState.LoggingOut
            "authorizationStateClosing" -> _authState.value = TelegramAuthState.Initializing
            "authorizationStateClosed" -> {
                if (origin == clientId) {
                    clientId = null
                    parametersReady = false
                    _authState.value = TelegramAuthState.Closed
                }
            }

            else -> SafeLog.w(TAG, "Unhandled authorization state: ${state.type}", null)
        }
    }

    private suspend fun sendTdlibParameters() {
        val creds = credentials ?: return
        if (clientId == null) {
            _authState.value = TelegramAuthState.Failed("Telegram client did not start")
            return
        }
        IosFiles.createDirectories(tdlibDirectory)
        val parameters = TdJson.obj(
            "setTdlibParameters",
            "use_test_dc" to false,
            "database_directory" to "$tdlibDirectory/db",
            "files_directory" to "$tdlibDirectory/files",
            "database_encryption_key" to databaseKeyProvider.databaseKey(),
            "use_file_database" to true,
            "use_chat_info_database" to true,
            "use_message_database" to true,
            "use_secret_chats" to false,
            "api_id" to creds.apiId,
            "api_hash" to creds.apiHash,
            "system_language_code" to systemLanguageCode,
            "device_model" to deviceModel,
            "system_version" to systemVersion,
            "application_version" to applicationVersion
        )
        try {
            send(parameters)
            parametersReady = true
        } catch (e: TelegramException) {
            SafeLog.e(TAG, "SetTdlibParameters failed: ${e.code}", null)
            if (e.code == WRONG_DATABASE_KEY_CODE && !databaseDropped) {
                databaseDropped = true
                SafeLog.w(TAG, "Dropping a session database this device cannot open", null)
                IosFiles.deleteRecursively(tdlibDirectory)
                scope.launch { sendTdlibParameters() }
            } else {
                _authState.value = TelegramAuthState.Failed(e.message)
            }
        }
    }

    // endregion

    // region request plumbing

    private fun sendRaw(target: Int, request: JsonObject) {
        transport.send(target, request.toString())
    }

    /**
     * TDLib answers a queued request only when it reconnects, which can be never, so every request
     * has a deadline.
     */
    private suspend fun send(request: JsonObject): JsonObject {
        val target = clientId ?: throw TelegramException(500, "Telegram client not started")
        return sendVia(target, request)
    }

    private suspend fun sendVia(target: Int, request: JsonObject): JsonObject {
        val waiter = CompletableDeferred<JsonObject>()
        val extra = locked {
            val id = "r${++nextRequestId}"
            pending[id] = waiter
            id
        }
        val name = request.type.orEmpty()
        try {
            sendRaw(target, JsonObject(request + ("@extra" to JsonPrimitive(extra))))
            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS.milliseconds) { waiter.await() }
                ?: throw TelegramException(REQUEST_TIMEOUT_CODE, "Telegram did not answer $name")
                    .also { SafeLog.w(TAG, "Request timed out: $name", null) }
            if (result.type == "error") {
                throw TelegramException.from(result.int("code") ?: 500, result.str("message").orEmpty())
            }
            return result
        } finally {
            locked { pending.remove(extra) }
        }
    }

    /** Fire and forget, for cleanup paths that must not suspend. */
    private fun sendQuietly(request: JsonObject) {
        val target = clientId ?: return
        runCatching { sendRaw(target, request) }
    }

    private inline fun <T> locked(block: () -> T): T {
        pendingLock.lock()
        try {
            return block()
        } finally {
            pendingLock.unlock()
        }
    }

    // endregion

    // region authentication

    override suspend fun countries(): List<Country> =
        send(TdJson.obj("getCountries")).objects("countries")
            .filterNot { it.bool("is_hidden") }
            .flatMap { info ->
                info.strings("calling_codes").map { code ->
                    Country(
                        isoCode = info.str("country_code").orEmpty(),
                        name = info.str("name").orEmpty(),
                        callingCode = code
                    )
                }
            }
            .sortedBy { it.name }

    override suspend fun detectedCountryCode(): String? =
        send(TdJson.obj("getCountryCode")).str("text")?.takeIf { it.isNotBlank() }

    override suspend fun submitPhoneNumber(phoneNumber: String) {
        send(
            TdJson.obj(
                "setAuthenticationPhoneNumber",
                "phone_number" to phoneNumber,
                "settings" to TdJson.obj("phoneNumberAuthenticationSettings")
            )
        )
    }

    override suspend fun requestQrCodeAuthentication() {
        send(TdJson.obj("requestQrCodeAuthentication", "other_user_ids" to LongArray(0)))
    }

    override suspend fun restartAuthentication() {
        val current = credentials ?: return
        runCatching { send(TdJson.obj("close")) }
        withTimeoutOrNull(CLOSE_WAIT_LIMIT_MS.milliseconds) {
            _authState.first { it == TelegramAuthState.Closed }
        }
        clientMutex.withLock {
            clientId = null
            parametersReady = false
        }
        IosFiles.deleteRecursively(tdlibDirectory)
        start(current)
    }

    override suspend fun submitEmailAddress(email: String) {
        send(TdJson.obj("setAuthenticationEmailAddress", "email_address" to email))
    }

    override suspend fun submitEmailCode(code: String) {
        send(
            TdJson.obj(
                "checkAuthenticationEmailCode",
                "code" to TdJson.obj("emailAddressAuthenticationCode", "code" to code)
            )
        )
    }

    override suspend fun submitCode(code: String) {
        send(TdJson.obj("checkAuthenticationCode", "code" to code))
    }

    override suspend fun submitPassword(password: String) {
        send(TdJson.obj("checkAuthenticationPassword", "password" to password))
    }

    override suspend fun resendCode() {
        send(TdJson.obj("resendAuthenticationCode"))
    }

    /**
     * Signing out is local: a client that never started or a silent server must not leave the
     * account stuck.
     */
    override suspend fun logout() {
        runCatching { send(TdJson.obj("logOut")) }
            .onFailure { SafeLog.w(TAG, "Remote sign out failed, dropping session locally", null) }
        runCatching { send(TdJson.obj("close")) }
        withTimeoutOrNull(CLOSE_WAIT_LIMIT_MS.milliseconds) {
            _authState.first { it == TelegramAuthState.Closed }
        }
        clientMutex.withLock {
            clientId = null
            credentials = null
            _authState.value = TelegramAuthState.Closed
        }
        IosFiles.deleteRecursively(tdlibDirectory)
    }

    override suspend fun getCurrentUser(): TelegramUser {
        val user = send(TdJson.obj("getMe"))
        val usernames = user.obj("usernames")
        return TelegramUser(
            id = user.long("id") ?: 0L,
            firstName = user.str("first_name").orEmpty(),
            lastName = user.str("last_name").orEmpty(),
            username = usernames?.str("editable_username")?.takeIf { it.isNotEmpty() }
                ?: usernames?.strings("active_usernames")?.firstOrNull(),
            phoneNumber = user.str("phone_number").orEmpty(),
            isPremium = user.bool("is_premium")
        )
    }

    override suspend fun getLimits(): TelegramLimits =
        TelegramLimits.forPremium(getCurrentUser().isPremium)

    // endregion

    // region proxy and network

    /** Replaces every TDLib proxy, so one removed here cannot keep being used. */
    override suspend fun applyProxy(proxy: TelegramProxy?) {
        if (clientId == null) return
        awaitAuthorizedOrParameters()
        runCatching {
            send(TdJson.obj("getProxies")).objects("proxies").forEach { added ->
                added.int("id")?.let { send(TdJson.obj("removeProxy", "proxy_id" to it)) }
            }
        }
        if (proxy == null) {
            runCatching { send(TdJson.obj("disableProxy")) }
            return
        }
        send(
            TdJson.obj(
                "addProxy",
                "proxy" to proxy.toTdProxy(),
                "enable" to true,
                "comment" to ""
            )
        )
    }

    override suspend fun testProxy(proxy: TelegramProxy) {
        val request = TdJson.obj(
            "testProxy",
            "proxy" to proxy.toTdProxy(),
            "dc_id" to PROXY_TEST_DC,
            "timeout" to PROXY_TEST_TIMEOUT_SECONDS
        )
        val active = clientId?.takeIf { parametersReady } ?: throw TelegramException(
            PROXY_NEEDS_CLIENT_CODE,
            "Telegram is not initialized yet"
        )
        awaitAuthorizedOrParameters()
        sendVia(active, request)
    }

    /**
     * TDLib keeps its existing sockets; re-declaring the network type forces them through the new
     * route. The iOS host calls this when NWPathMonitor reports a change.
     */
    override suspend fun reconnect() {
        if (clientId == null || !parametersReady) return
        runCatching { send(TdJson.obj("setNetworkType", "type" to TdJson.obj(networkType()))) }
            .onFailure { SafeLog.w(TAG, "Could not force a reconnect", it) }
    }

    private fun TelegramProxy.toTdProxy(): JsonObject = TdJson.obj(
        "proxy",
        "server" to host,
        "port" to port,
        "type" to when (type) {
            TelegramProxyType.SOCKS5 -> TdJson.obj(
                "proxyTypeSocks5",
                "username" to username.orEmpty(),
                "password" to password.orEmpty()
            )

            TelegramProxyType.MTPROTO -> TdJson.obj(
                "proxyTypeMtproto",
                "secret" to mtprotoSecret(secret.orEmpty())
            )

            TelegramProxyType.HTTP -> TdJson.obj(
                "proxyTypeHttp",
                "username" to username.orEmpty(),
                "password" to password.orEmpty(),
                "http_only" to false
            )
        }
    )

    /**
     * Shared links carry base64url secrets while TDLib expects hex, so anything not hex is
     * re-encoded.
     */
    private fun mtprotoSecret(raw: String): String {
        val secret = raw.trim()
        val isHex = secret.isNotEmpty() &&
                secret.length % 2 == 0 &&
                secret.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (isHex) return secret.lowercase()
        return runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(secret)
                .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }.getOrDefault(secret)
    }

    /**
     * Proxy calls are accepted before sign in, since without a route there is nothing to sign in
     * through.
     */
    private suspend fun awaitAuthorizedOrParameters() {
        if (_authState.value == TelegramAuthState.Ready) return
        withTimeoutOrNull(PROXY_READY_WAIT_MS.milliseconds) {
            authState.first { it != TelegramAuthState.Initializing }
        }
    }

    // endregion

    // region storage channels

    private val storageChatMutex = Mutex()

    override suspend fun ensureStorageChat(knownChatId: Long?): Long =
        storageChatMutex.withLock {
            awaitAuthorized()
            knownChatId?.let { candidate ->
                if (isStorageChat(candidate)) {
                    runCatching {
                        val chat = getChat(candidate)
                        SafeLog.d(TAG, "Using drive '${chat.str("title")}' #$candidate")
                    }
                    return@withLock candidate
                }
            }
            repeat(DISCOVERY_ATTEMPTS) { attempt ->
                findStorageChat()?.let { return@withLock it }
                if (attempt < DISCOVERY_ATTEMPTS - 1) delay(DISCOVERY_RETRY_MS.milliseconds)
            }
            val chat = send(newChannelRequest(STORAGE_CHAT_TITLE))
            SafeLog.d(TAG, "Created a new storage channel")
            chat.long("id") ?: throw TelegramException(500, "Created channel has no id")
        }

    override suspend fun createStorageChannel(label: String): StorageChannel {
        awaitAuthorized()
        val title = driveTitle(label)
        val chat = send(newChannelRequest(title))
        SafeLog.d(TAG, "Created an additional storage channel")
        return StorageChannel(
            chatId = chat.long("id") ?: throw TelegramException(500, "Created channel has no id"),
            title = title,
            documentCount = 0
        )
    }

    private fun newChannelRequest(title: String): JsonObject = TdJson.obj(
        "createNewSupergroupChat",
        "title" to title,
        "is_forum" to false,
        "is_channel" to true,
        "description" to STORAGE_CHAT_MARKER,
        "message_auto_delete_time" to 0,
        "for_import" to false
    )

    override suspend fun linkPreview(url: String, withImage: Boolean): LinkMetadata? = runCatching {
        awaitAuthorized()
        val preview = send(TdJson.obj("getLinkPreview", "text" to TdJson.formattedText(url)))
        LinkMetadata(
            url = url,
            siteName = preview.str("site_name")?.takeIf { it.isNotEmpty() },
            title = preview.str("title")?.takeIf { it.isNotEmpty() },
            description = preview.obj("description")?.str("text")?.takeIf { it.isNotEmpty() },
            imagePath = if (withImage) previewImagePath(preview) else null
        )
    }.getOrNull()

    private suspend fun previewImagePath(preview: JsonObject): String? {
        val type = preview.obj("type")
        val photo = when (type?.type) {
            "linkPreviewTypeArticle", "linkPreviewTypePhoto" -> type.obj("photo")
            else -> null
        } ?: return null
        val file = photo.objects("sizes").lastOrNull()?.obj("photo") ?: return null
        val local = file.obj("local")
        if (local?.bool("is_downloading_completed") == true) return local.str("path")
        val downloaded = send(downloadRequest(file.int("id") ?: return null, PREVIEW_PRIORITY, true))
        return downloaded.obj("local")?.str("path")?.takeIf { it.isNotEmpty() }
    }

    override suspend fun renameStorageChannel(chatId: Long, label: String): String {
        awaitAuthorized()
        val title = driveTitle(label)
        send(TdJson.obj("setChatTitle", "chat_id" to chatId, "title" to title))
        return title
    }

    override suspend fun fetchChannelPhoto(chatId: Long): String? {
        awaitAuthorized()
        val photo = getChat(chatId).obj("photo")?.obj("small") ?: return null
        photo.obj("local")?.str("path")?.takeIf { it.isNotEmpty() && IosFiles.exists(it) }
            ?.let { return it }
        val downloaded = send(downloadRequest(photo.int("id") ?: return null, PHOTO_PRIORITY, true))
        return downloaded.obj("local")?.str("path")
            ?.takeIf { it.isNotEmpty() && IosFiles.exists(it) }
    }

    override suspend fun chatExists(chatId: Long): Boolean? = try {
        awaitAuthorized()
        val chat = getChat(chatId)
        val type = chat.obj("type")?.takeIf { it.type == "chatTypeSupergroup" }
        if (type == null) {
            true
        } else {
            val supergroupId = type.long("supergroup_id") ?: 0L
            val status = send(TdJson.obj("getSupergroup", "supergroup_id" to supergroupId))
                .obj("status")?.type
            if (status == "chatMemberStatusLeft" || status == "chatMemberStatusBanned") {
                false
            } else {
                send(TdJson.obj("getSupergroupFullInfo", "supergroup_id" to supergroupId))
                true
            }
        }
    } catch (e: TelegramException) {
        if (e.code == 400) false else null
    }

    override suspend fun deleteStorageChannel(chatId: Long) {
        awaitAuthorized()
        send(TdJson.obj("deleteChat", "chat_id" to chatId))
        SafeLog.d(TAG, "Deleted a storage channel")
    }

    private suspend fun getChat(chatId: Long): JsonObject =
        send(TdJson.obj("getChat", "chat_id" to chatId))

    private fun driveTitle(label: String): String {
        val trimmed = label.trim()
            .removePrefix(STORAGE_CHAT_TITLE)
            .trim()
            .take(MAX_LABEL_LENGTH)
        return if (trimmed.isEmpty()) STORAGE_CHAT_TITLE else "$STORAGE_CHAT_TITLE $trimmed"
    }

    private fun isDriveTitle(title: String): Boolean =
        title == STORAGE_CHAT_TITLE || title.startsWith("$STORAGE_CHAT_TITLE ")

    private suspend fun awaitAuthorized() {
        if (_authState.value == TelegramAuthState.Ready) return
        withTimeoutOrNull(AUTH_WAIT_MS.milliseconds) {
            authState.first { it == TelegramAuthState.Ready }
        }
    }

    /** The channel type of [chat] when it is a channel (not a group), else null. */
    private fun channelTypeOf(chat: JsonObject): JsonObject? =
        chat.obj("type")?.takeIf { it.type == "chatTypeSupergroup" && it.bool("is_channel") }

    /**
     * Also adopts an own private channel with the drive title whose description never took, and
     * writes the marker back.
     */
    override suspend fun listStorageChannels(knownChatIds: List<Long>): List<StorageChannel> {
        awaitAuthorized()

        val known = validateCandidates(knownChatIds.toSet())
        val knownIds = known.map { it.chatId }.toSet()

        val candidates = LinkedHashSet<Long>()
        runCatching { searchChatsOnServer(STORAGE_CHAT_TITLE) }.getOrNull()
            ?.forEach { candidates.add(it) }
        for (chatList in CHAT_LISTS) {
            loadEveryChat(chatList)
            runCatching { getChats(chatList) }.getOrNull()?.forEach { candidates.add(it) }
        }
        val discovered = validateCandidates(candidates.filterNot { it in knownIds }.toSet())
        return (known + discovered)
            .sortedByDescending { it.documentCount }
            .also { found ->
                SafeLog.d(TAG, "Discovered ${found.size} drives, ${known.size} already known")
            }
    }

    private suspend fun validateCandidates(candidates: Set<Long>): List<StorageChannel> =
        candidates.mapNotNull { chatId ->
            try {
                val chat = getChat(chatId)
                val type = channelTypeOf(chat) ?: return@mapNotNull null
                val title = chat.str("title").orEmpty()
                if (!isDriveTitle(title)) return@mapNotNull null
                if (!isOwnPrivateChannel(type.long("supergroup_id") ?: 0L)) return@mapNotNull null
                StorageChannel(
                    chatId = chatId,
                    title = title,
                    photoPath = chat.obj("photo")?.obj("small")?.obj("local")?.str("path")
                        ?.takeIf { it.isNotEmpty() },
                    documentCount = documentCount(chatId)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    /** User files only: the folder state and key backup documents are not counted. */
    private suspend fun documentCount(chatId: Long): Int = runCatching {
        val total = searchCount(chatId, "")
        val internal = INTERNAL_DOCUMENT_NAMES.sumOf { name -> searchCount(chatId, name) }
        (total - internal).coerceAtLeast(0)
    }.getOrDefault(0)

    private suspend fun searchCount(chatId: Long, query: String): Int = send(
        TdJson.obj(
            "searchChatMessages",
            "chat_id" to chatId,
            "query" to query,
            "from_message_id" to 0L,
            "offset" to 0,
            "limit" to 1,
            "filter" to TdJson.obj("searchMessagesFilterDocument")
        )
    ).int("total_count") ?: 0

    private suspend fun isStorageChat(chatId: Long): Boolean = try {
        val chat = getChat(chatId)
        SafeLog.d(TAG, "Validating stored storage chat")
        val type = channelTypeOf(chat)
        if (type == null) {
            false
        } else {
            val supergroupId = type.long("supergroup_id") ?: 0L
            val description = fullInfoDescription(supergroupId)
            when {
                description.contains(STORAGE_CHAT_MARKER) -> true
                !isDriveTitle(chat.str("title").orEmpty()) -> false
                !isOwnPrivateChannel(supergroupId) -> false
                else -> {
                    writeMarker(chatId, description)
                    true
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SafeLog.d(TAG, "Stored storage chat rejected: ${e.message}")
        false
    }

    private suspend fun fullInfoDescription(supergroupId: Long): String =
        send(TdJson.obj("getSupergroupFullInfo", "supergroup_id" to supergroupId))
            .str("description").orEmpty()

    private suspend fun isOwnPrivateChannel(supergroupId: Long): Boolean = runCatching {
        val supergroup = send(TdJson.obj("getSupergroup", "supergroup_id" to supergroupId))
        supergroup.obj("status")?.type == "chatMemberStatusCreator" &&
                supergroup.obj("usernames")?.strings("active_usernames").isNullOrEmpty()
    }.getOrDefault(false)

    private suspend fun writeMarker(chatId: Long, description: String) {
        if (description.contains(STORAGE_CHAT_MARKER)) return
        val updated = if (description.isBlank()) {
            STORAGE_CHAT_MARKER
        } else {
            description + MARKER_SEPARATOR + STORAGE_CHAT_MARKER
        }
        runCatching {
            send(TdJson.obj("setChatDescription", "chat_id" to chatId, "description" to updated))
        }
            .onSuccess { SafeLog.d(TAG, "Restored storage marker on existing channel") }
            .onFailure { SafeLog.w(TAG, "Could not restore storage marker", it) }
    }

    /**
     * Server search finds the channel before the local chat list has synced, as happens right after
     * sign in.
     */
    private suspend fun findStorageChat(): Long? {
        val candidates = LinkedHashSet<Long>()

        runCatching { searchChatsOnServer(STORAGE_CHAT_TITLE) }
            .onFailure { SafeLog.d(TAG, "Server chat search failed: ${it.message}") }
            .getOrNull()?.forEach { candidates.add(it) }
        runCatching {
            send(TdJson.obj("searchChats", "query" to STORAGE_CHAT_TITLE, "limit" to CHAT_SEARCH_LIMIT))
                .longs("chat_ids")
        }
            .onFailure { SafeLog.d(TAG, "Local chat search failed: ${it.message}") }
            .getOrNull()?.forEach { candidates.add(it) }

        matchStorageChat(candidates)?.let { return it }

        for (chatList in CHAT_LISTS) {
            loadEveryChat(chatList)
            runCatching { getChats(chatList) }
                .onFailure { SafeLog.d(TAG, "Chat list read failed: ${it.message}") }
                .getOrNull()?.forEach { candidates.add(it) }
        }
        SafeLog.d(TAG, "Storage chat scan checked ${candidates.size} chats")
        return matchStorageChat(candidates) ?: adoptStorageChat(candidates)
    }

    private suspend fun searchChatsOnServer(query: String): List<Long> =
        send(TdJson.obj("searchChatsOnServer", "query" to query, "limit" to CHAT_SEARCH_LIMIT))
            .longs("chat_ids")

    private suspend fun getChats(chatList: String): List<Long> =
        send(TdJson.obj("getChats", "chat_list" to TdJson.obj(chatList), "limit" to CHAT_LIST_LIMIT))
            .longs("chat_ids")

    /**
     * Filtering by title first keeps full info requests to a handful, instead of hundreds that
     * would trip flood limits.
     */
    private suspend fun matchStorageChat(chatIds: Collection<Long>): Long? {
        val titled = chatIds.mapNotNull { chatId ->
            runCatching {
                val chat = getChat(chatId)
                val type = channelTypeOf(chat)
                if (type == null || chat.str("title") != STORAGE_CHAT_TITLE) {
                    null
                } else {
                    chatId to (type.long("supergroup_id") ?: 0L)
                }
            }.getOrNull()
        }
        if (titled.isEmpty()) return null
        SafeLog.d(TAG, "Found ${titled.size} channels named like the drive")

        val marked = titled.filter { (_, supergroupId) -> hasStorageMarker(supergroupId) }
        val usable = marked.ifEmpty {
            titled.filter { (_, supergroupId) -> isOwnPrivateChannel(supergroupId) }
        }
        val chosen = usable.minByOrNull { it.second } ?: return null
        if (marked.isEmpty()) {
            val description = runCatching { fullInfoDescription(chosen.second) }.getOrNull()
            writeMarker(chosen.first, description.orEmpty())
        }
        return chosen.first
    }

    private suspend fun hasStorageMarker(supergroupId: Long): Boolean = runCatching {
        fullInfoDescription(supergroupId).contains(STORAGE_CHAT_MARKER)
    }.onFailure { SafeLog.d(TAG, "Channel description unavailable: ${it.message}") }
        .getOrDefault(false)

    /**
     * TDLib loads pages in the background and answers 404 once done, so each page needs a moment to
     * land.
     */
    private suspend fun loadEveryChat(chatList: String) {
        var page = 0
        while (page++ < MAX_CHAT_LIST_PAGES) {
            val loaded = runCatching {
                send(
                    TdJson.obj("loadChats", "chat_list" to TdJson.obj(chatList), "limit" to CHAT_LIST_PAGE)
                )
            }
            if (loaded.isFailure) {
                SafeLog.d(TAG, "Chat list page $page ended: ${loaded.exceptionOrNull()?.message}")
                return
            }
            delay(CHAT_LIST_SETTLE_MS.milliseconds)
        }
    }

    /** Only an own private channel with the drive title is adopted. */
    private suspend fun adoptStorageChat(chatIds: Collection<Long>): Long? {
        for (chatId in chatIds) {
            val adopted = runCatching {
                val chat = getChat(chatId)
                if (chat.str("title") != STORAGE_CHAT_TITLE) return@runCatching null
                val supergroupId = channelTypeOf(chat)?.long("supergroup_id")
                    ?: return@runCatching null
                if (!isOwnPrivateChannel(supergroupId)) return@runCatching null
                writeMarker(chatId, fullInfoDescription(supergroupId))
                SafeLog.d(TAG, "Adopted existing storage channel")
                chatId
            }.getOrNull()
            if (adopted != null) return adopted
        }
        return null
    }

    // endregion

    // region documents

    private fun documentContent(
        file: JsonObject,
        caption: String,
        thumbnail: JsonObject?,
        disableTypeDetection: Boolean
    ): JsonObject = TdJson.obj(
        "inputMessageDocument",
        "document" to TdJson.obj(
            "inputDocument",
            "document" to file,
            "thumbnail" to thumbnail,
            "disable_content_type_detection" to disableTypeDetection
        ),
        "caption" to TdJson.formattedText(caption)
    )

    private fun sendMessageRequest(chatId: Long, content: JsonObject): JsonObject =
        TdJson.obj("sendMessage", "chat_id" to chatId, "input_message_content" to content)

    override suspend fun editDocument(
        chatId: Long,
        messageId: Long,
        localPath: String,
        caption: String
    ): RemoteDocument {
        awaitAuthorized()
        val edited = paced {
            send(
                TdJson.obj(
                    "editMessageMedia",
                    "chat_id" to chatId,
                    "message_id" to messageId,
                    "input_message_content" to documentContent(
                        TdJson.obj("inputFileLocal", "path" to localPath),
                        caption,
                        thumbnail = null,
                        disableTypeDetection = true
                    )
                )
            )
        }
        return edited.toRemoteDocument()
            ?: throw TelegramException(500, "Edited message is not a document")
    }

    override fun uploadDocument(
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
        caption: String,
        thumbnailPath: String?
    ): Flow<TelegramUploadEvent> = callbackFlow<TelegramUploadEvent> {
        val thumbnail = thumbnailPath?.let {
            TdJson.obj(
                "inputThumbnail",
                "thumbnail" to TdJson.obj("inputFileLocal", "path" to it),
                "width" to THUMBNAIL_EDGE,
                "height" to THUMBNAIL_EDGE
            )
        }
        val content = documentContent(
            TdJson.obj("inputFileLocal", "path" to localPath),
            caption,
            thumbnail,
            disableTypeDetection = true
        )
        var finished = false
        var sentMessageId = 0L
        val subscribed = CompletableDeferred<Unit>()
        val pendingMessageId = CompletableDeferred<Long>()
        val pendingFile = CompletableDeferred<Int?>()

        val job = launch {
            updates.onSubscription { subscribed.complete(Unit) }.collect { update ->
                val pendingId = pendingMessageId.await()
                val pendingFileId = pendingFile.await()
                when (update.type) {
                    "updateFile" -> {
                        val file = update.obj("file") ?: return@collect
                        if (pendingFileId != null && file.int("id") == pendingFileId) {
                            val total = file.long("size")?.takeIf { it > 0 }
                                ?: file.long("expected_size") ?: 0L
                            trySend(
                                TelegramUploadEvent.Progress(
                                    file.obj("remote")?.long("uploaded_size") ?: 0L,
                                    total
                                )
                            )
                        }
                    }

                    "updateMessageSendSucceeded" -> {
                        if (update.long("old_message_id") == pendingId) {
                            val message = update.obj("message")
                            sentMessageId = message?.long("id") ?: sentMessageId
                            val document = message?.toRemoteDocument()
                            finished = true
                            if (document != null) {
                                trySend(TelegramUploadEvent.Completed(document))
                                close()
                            } else {
                                close(TelegramException(500, "Sent message is not a document"))
                            }
                        }
                    }

                    "updateMessageSendFailed" -> {
                        if (update.long("old_message_id") == pendingId) {
                            finished = true
                            val error = update.obj("error")
                            close(
                                TelegramException.from(
                                    error?.int("code") ?: 500,
                                    error?.str("message").orEmpty()
                                )
                            )
                        }
                    }
                }
            }
        }

        subscribed.await()
        val pendingMessage = paced { send(sendMessageRequest(chatId, content)) }
        sentMessageId = pendingMessage.long("id") ?: 0L
        pendingFile.complete(
            pendingMessage.obj("content")?.obj("document")?.obj("document")?.int("id")
        )
        pendingMessageId.complete(sentMessageId)
        trySend(TelegramUploadEvent.Started(sentMessageId))

        awaitClose {
            job.cancel()
            if (!finished) {
                sendQuietly(
                    TdJson.obj(
                        "deleteMessages",
                        "chat_id" to chatId,
                        "message_ids" to longArrayOf(sentMessageId),
                        "revoke" to true
                    )
                )
            }
        }
    }.buffer(capacity = 64)

    override suspend fun copyDocument(
        chatId: Long,
        remoteFileId: String,
        fileName: String,
        mimeType: String,
        caption: String
    ): RemoteDocument {
        val content = documentContent(
            TdJson.obj("inputFileRemote", "id" to remoteFileId),
            caption,
            thumbnail = null,
            disableTypeDetection = false
        )
        val subscribed = CompletableDeferred<Unit>()
        val pendingMessageId = CompletableDeferred<Long>()

        val sent: JsonObject = coroutineScope {
            val outcome = async {
                withTimeoutOrNull(COPY_TIMEOUT_MS.milliseconds) {
                    updates.onSubscription { subscribed.complete(Unit) }.first { update ->
                        val pendingId = pendingMessageId.await()
                        (update.type == "updateMessageSendSucceeded" ||
                                update.type == "updateMessageSendFailed") &&
                                update.long("old_message_id") == pendingId
                    }
                }
            }
            subscribed.await()
            val pendingMessage = paced { send(sendMessageRequest(chatId, content)) }
            pendingMessageId.complete(pendingMessage.long("id") ?: 0L)
            outcome.await() ?: pendingMessage
        }
        return when (sent.type) {
            "updateMessageSendSucceeded" ->
                sent.obj("message")?.toRemoteDocument()
                    ?: throw TelegramException(500, "Copied message is not a document")

            "updateMessageSendFailed" -> {
                val error = sent.obj("error")
                throw TelegramException.from(error?.int("code") ?: 500, error?.str("message").orEmpty())
            }

            "message" -> sent.toRemoteDocument()
                ?: throw TelegramException(500, "Copy did not complete")

            else -> throw TelegramException(500, "Copy did not complete")
        }
    }

    private fun downloadRequest(
        fileId: Int,
        priority: Int,
        synchronous: Boolean,
        offset: Long = 0,
        limit: Long = 0
    ): JsonObject = TdJson.obj(
        "downloadFile",
        "file_id" to fileId,
        "priority" to priority,
        "offset" to offset,
        "limit" to limit,
        "synchronous" to synchronous
    )

    override fun downloadDocument(remoteFileId: String): Flow<TelegramDownloadEvent> =
        callbackFlow<TelegramDownloadEvent> {
            val subscribed = CompletableDeferred<Unit>()
            val downloadedFileId = CompletableDeferred<Int>()

            val job = launch {
                updates.onSubscription { subscribed.complete(Unit) }
                    .filter { it.type == "updateFile" }
                    .mapNotNull { it.obj("file") }
                    .filter { it.int("id") == downloadedFileId.await() }
                    .collect { file ->
                        val readyPath = file.readyPath()
                        if (readyPath != null) {
                            trySend(TelegramDownloadEvent.Completed(readyPath, file.long("size") ?: 0L))
                            close()
                        } else {
                            trySend(
                                TelegramDownloadEvent.Progress(
                                    file.obj("local")?.long("downloaded_size") ?: 0L,
                                    file.long("size")?.takeIf { it > 0 }
                                        ?: file.long("expected_size") ?: 0L
                                )
                            )
                        }
                    }
            }

            subscribed.await()
            val file = remoteFile(remoteFileId)
            val fileId = file.int("id") ?: throw TelegramException(500, "Remote file has no id")
            downloadedFileId.complete(fileId)

            file.readyPath()?.let { path ->
                trySend(TelegramDownloadEvent.Completed(path, file.long("size") ?: 0L))
                close()
            }

            if (!isClosedForSend) {
                if (file.obj("local")?.bool("is_downloading_completed") == true) {
                    send(TdJson.obj("deleteFile", "file_id" to fileId))
                }
                val started = send(downloadRequest(fileId, DOWNLOAD_PRIORITY, false))
                started.readyPath()?.let { path ->
                    trySend(TelegramDownloadEvent.Completed(path, started.long("size") ?: 0L))
                    close()
                }
            }

            val resumer = launch {
                while (true) {
                    delay(DOWNLOAD_RESUME_INTERVAL_MS)
                    val current = runCatching { send(TdJson.obj("getFile", "file_id" to fileId)) }
                        .getOrNull() ?: continue
                    val local = current.obj("local")
                    if (local?.bool("is_downloading_completed") == true) break
                    if (local?.bool("is_downloading_active") != true) {
                        runCatching { send(downloadRequest(fileId, DOWNLOAD_PRIORITY, false)) }
                    }
                }
            }

            awaitClose {
                resumer.cancel()
                job.cancel()
                sendQuietly(
                    TdJson.obj("cancelDownloadFile", "file_id" to fileId, "only_if_pending" to true)
                )
            }
        }.buffer(capacity = 64)

    override suspend fun fetchThumbnail(chatId: Long, messageId: Long): ByteArray? {
        val message = runCatching { getMessage(chatId, messageId) }.getOrNull() ?: return null
        val content = message.obj("content")?.takeIf { it.type == "messageDocument" } ?: return null
        val document = content.obj("document") ?: return null

        document.obj("thumbnail")?.obj("file")?.int("id")?.let { thumbnailId ->
            val downloaded = runCatching {
                send(downloadRequest(thumbnailId, THUMBNAIL_PRIORITY, true))
            }.getOrNull()
            val path = downloaded?.obj("local")?.str("path")?.takeIf { it.isNotEmpty() }
            if (path != null) IosFiles.readBytes(path)?.let { return it }
        }
        return document.obj("minithumbnail")?.bytes("data")
    }

    private suspend fun getMessage(chatId: Long, messageId: Long): JsonObject =
        send(TdJson.obj("getMessage", "chat_id" to chatId, "message_id" to messageId))

    private suspend fun remoteFile(remoteFileId: String): JsonObject = try {
        send(remoteFileRequest(remoteFileId, "fileTypeDocument"))
    } catch (documentFailure: TelegramException) {
        try {
            send(remoteFileRequest(remoteFileId, "fileTypePhoto"))
        } catch (_: TelegramException) {
            throw documentFailure
        }
    }

    private fun remoteFileRequest(remoteFileId: String, fileType: String) = TdJson.obj(
        "getRemoteFile",
        "remote_file_id" to remoteFileId,
        "file_type" to TdJson.obj(fileType)
    )

    override suspend fun resolveFile(remoteFileId: String): TelegramFileInfo =
        remoteFile(remoteFileId).toInfo()

    override suspend fun getFileInfo(fileId: Int): TelegramFileInfo =
        send(TdJson.obj("getFile", "file_id" to fileId)).toInfo()

    override suspend fun requestFileRange(fileId: Int, offset: Long, limit: Long) {
        send(downloadRequest(fileId, STREAM_PRIORITY, false, offset, limit))
    }

    override suspend fun readFilePart(fileId: Int, offset: Long, count: Long): ByteArray =
        send(TdJson.obj("readFilePart", "file_id" to fileId, "offset" to offset, "count" to count))
            .bytes("data") ?: ByteArray(0)

    override suspend fun cancelFileDownload(fileId: Int) {
        send(TdJson.obj("cancelDownloadFile", "file_id" to fileId, "only_if_pending" to false))
    }

    override suspend fun clearDownloadedFiles() {
        if (clientId == null) {
            IosFiles.deleteRecursively("$tdlibDirectory/files")
            return
        }
        send(
            TdJson.obj(
                "optimizeStorage",
                "size" to 0L,
                "ttl" to 0,
                "count" to 0,
                "immunity_delay" to 0,
                "return_deleted_file_statistics" to false,
                "chat_limit" to 0
            )
        )
    }

    override suspend fun deleteLocalCopy(remoteFileId: String) {
        val fileId = remoteFile(remoteFileId).int("id") ?: return
        send(TdJson.obj("deleteFile", "file_id" to fileId))
    }

    override fun fileUpdates(fileId: Int): Flow<TelegramFileInfo> =
        updates.filter { it.type == "updateFile" }
            .mapNotNull { it.obj("file") }
            .filter { it.int("id") == fileId }
            .map { it.toInfo() }

    /**
     * Search rather than getChatHistory: history answers from TDLib's cache, which on a fresh
     * session is nearly empty.
     */
    override suspend fun fetchDocuments(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): RemoteDocumentPage {
        if (fromMessageId == 0L) {
            runCatching { send(TdJson.obj("openChat", "chat_id" to chatId)) }
        }

        var messages = searchMessages(chatId, fromMessageId, limit)
        var attempts = 0
        while (fromMessageId == 0L && messages.isEmpty() && attempts++ < HISTORY_RETRIES) {
            delay(HISTORY_RETRY_MS.milliseconds)
            messages = searchMessages(chatId, fromMessageId, limit)
        }

        val documents = messages.mapNotNull { it.toRemoteDocument() }
        val oldest = messages.lastOrNull()?.long("id") ?: 0L
        SafeLog.d(
            TAG,
            "Page from=$fromMessageId messages=${messages.size} " +
                    "mapped=${documents.size} next=$oldest"
        )
        return RemoteDocumentPage(
            documents = documents,
            nextFromMessageId = if (messages.isEmpty()) 0L else oldest
        )
    }

    private suspend fun searchMessages(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): List<JsonObject> = send(
        TdJson.obj(
            "searchChatMessages",
            "chat_id" to chatId,
            "query" to "",
            "from_message_id" to fromMessageId,
            "offset" to 0,
            "limit" to limit
        )
    ).objects("messages")

    override suspend fun getDocument(chatId: Long, messageId: Long): RemoteDocument? =
        runCatching { getMessage(chatId, messageId).toRemoteDocument() }.getOrNull()

    override suspend fun editCaption(chatId: Long, messageId: Long, caption: String) {
        ownWrites.wroteCaption(messageId, caption)
        paced {
            send(
                TdJson.obj(
                    "editMessageCaption",
                    "chat_id" to chatId,
                    "message_id" to messageId,
                    "caption" to TdJson.formattedText(caption),
                    "show_caption_above_media" to false
                )
            )
        }
    }

    override suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        ownWrites.deleted(messageIds)
        messageIds.chunked(DELETE_BATCH).forEach { batch ->
            paced {
                send(
                    TdJson.obj(
                        "deleteMessages",
                        "chat_id" to chatId,
                        "message_ids" to batch.toLongArray(),
                        "revoke" to true
                    )
                )
            }
        }
    }

    override fun messageChanges(chatId: Long): Flow<MessageChange> = updates.mapNotNull { update ->
        when (update.type) {
            "updateNewMessage" -> update.obj("message")
                ?.takeIf { it.long("chat_id") == chatId && it.obj("sending_state") == null }
                ?.let { message ->
                    val id = message.long("id") ?: return@let null
                    if (ownWrites.isOwnContent(id, message.obj("content")?.captionText())) {
                        null
                    } else {
                        MessageChange.Updated(id)
                    }
                }

            "updateMessageContent" -> {
                val messageId = update.long("message_id")
                if (update.long("chat_id") == chatId && messageId != null &&
                    !ownWrites.isOwnContent(messageId, update.obj("new_content")?.captionText())
                ) {
                    MessageChange.Updated(messageId)
                } else {
                    null
                }
            }

            "updateDeleteMessages" ->
                if (update.long("chat_id") == chatId && update.bool("is_permanent") &&
                    !update.bool("from_cache")
                ) {
                    ownWrites.deletedElsewhere(update.longs("message_ids"))
                        .takeIf { it.isNotEmpty() }
                        ?.let { MessageChange.Deleted(it) }
                } else {
                    null
                }

            else -> null
        }
    }

    override suspend fun openChat(chatId: Long) {
        send(TdJson.obj("openChat", "chat_id" to chatId))
    }

    override suspend fun closeChat(chatId: Long) {
        send(TdJson.obj("closeChat", "chat_id" to chatId))
    }

    // endregion

    // region mapping

    private fun JsonObject.captionText(): String? = when (type) {
        "messageDocument", "messageAudio", "messageVideo", "messagePhoto", "messageAnimation" ->
            obj("caption")?.str("text")

        else -> null
    }

    /**
     * A flood wait applies to the whole account, so every message-creating call waits behind one
     * pacer.
     */
    private suspend fun <T> paced(block: suspend () -> T): T =
        pacer.paced { withRateLimitRetry(block) }

    private suspend fun <T> withRateLimitRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: TelegramException) {
                if (!e.isRateLimit || attempt++ >= RATE_LIMIT_RETRIES) throw e
                delay(
                    (((e.retryAfterSeconds ?: RATE_LIMIT_FALLBACK_SECONDS) + 1) * 1000L).milliseconds
                )
            }
        }
    }

    private fun JsonObject.toInfo(): TelegramFileInfo {
        val local = obj("local")
        return TelegramFileInfo(
            fileId = int("id") ?: 0,
            sizeBytes = long("size")?.takeIf { it > 0 } ?: long("expected_size") ?: 0L,
            localPath = local?.str("path")?.takeIf { it.isNotEmpty() },
            isDownloadingCompleted = local?.bool("is_downloading_completed") == true,
            isDownloadingActive = local?.bool("is_downloading_active") == true,
            downloadOffset = local?.long("download_offset") ?: 0L,
            downloadedPrefixSize = local?.long("downloaded_prefix_size") ?: 0L
        )
    }

    /**
     * TDLib keeps reporting a completed copy after its cache file is removed, so the bytes must
     * still be on disk.
     */
    private fun JsonObject.readyPath(): String? {
        val local = obj("local") ?: return null
        if (!local.bool("is_downloading_completed")) return null
        val path = local.str("path")
        if (path.isNullOrEmpty()) return null
        return path.takeIf { IosFiles.exists(it) }
    }

    private fun JsonObject.toRemoteDocument(): RemoteDocument? {
        val body = obj("content") ?: return null
        val caption = body.obj("caption")?.str("text").orEmpty()
        return when (body.type) {
            "messageDocument" -> body.obj("document")?.let { document ->
                remoteDocument(
                    file = document.obj("document"),
                    fileName = document.str("file_name").orEmpty(),
                    mimeType = document.str("mime_type").orEmpty(),
                    caption = caption,
                    miniThumbnail = document.obj("minithumbnail")?.bytes("data")
                )
            }

            "messageAudio" -> body.obj("audio")?.let { audio ->
                remoteDocument(
                    file = audio.obj("audio"),
                    fileName = audio.str("file_name").orEmpty(),
                    mimeType = audio.str("mime_type").orEmpty(),
                    caption = caption,
                    miniThumbnail = audio.obj("album_cover_minithumbnail")?.bytes("data")
                )
            }

            "messageVideo" -> body.obj("video")?.let { video ->
                remoteDocument(
                    file = video.obj("video"),
                    fileName = video.str("file_name").orEmpty(),
                    mimeType = video.str("mime_type").orEmpty(),
                    caption = caption,
                    miniThumbnail = video.obj("minithumbnail")?.bytes("data")
                )
            }

            "messagePhoto" -> body.obj("photo")?.let { photo ->
                photo.objects("sizes")
                    .mapNotNull { it.obj("photo") }
                    .maxByOrNull { it.long("size") ?: 0L }
                    ?.let { largest ->
                        remoteDocument(
                            file = largest,
                            fileName = "photo_${long("id")}.jpg",
                            mimeType = "image/jpeg",
                            caption = caption,
                            miniThumbnail = photo.obj("minithumbnail")?.bytes("data")
                        )
                    }
            }

            else -> null
        }
    }

    private fun JsonObject.remoteDocument(
        file: JsonObject?,
        fileName: String,
        mimeType: String,
        caption: String,
        miniThumbnail: ByteArray?
    ): RemoteDocument? {
        val remote = file?.obj("remote") ?: return null
        val remoteId = remote.str("id")
        if (remoteId.isNullOrEmpty()) return null
        return RemoteDocument(
            chatId = long("chat_id") ?: 0L,
            messageId = long("id") ?: 0L,
            remoteFileId = remoteId,
            uniqueFileId = remote.str("unique_id").orEmpty(),
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = file.long("size") ?: 0L,
            caption = caption,
            dateSeconds = int("date") ?: 0,
            miniThumbnail = miniThumbnail
        )
    }

    private fun JsonObject?.toChannel(): CodeDeliveryChannel = when (this?.type) {
        "authenticationCodeTypeTelegramMessage" -> CodeDeliveryChannel.TELEGRAM_APP
        "authenticationCodeTypeSms" -> CodeDeliveryChannel.SMS
        "authenticationCodeTypeSmsWord" -> CodeDeliveryChannel.SMS_WORD
        "authenticationCodeTypeSmsPhrase" -> CodeDeliveryChannel.SMS_PHRASE
        "authenticationCodeTypeCall" -> CodeDeliveryChannel.CALL
        "authenticationCodeTypeFlashCall" -> CodeDeliveryChannel.FLASH_CALL
        "authenticationCodeTypeMissedCall" -> CodeDeliveryChannel.MISSED_CALL
        "authenticationCodeTypeFragment" -> CodeDeliveryChannel.FRAGMENT
        "authenticationCodeTypeFirebaseAndroid",
        "authenticationCodeTypeFirebaseIos" -> CodeDeliveryChannel.FIREBASE

        else -> CodeDeliveryChannel.OTHER
    }

    // endregion

    companion object {
        private const val TAG = "IosTelegramClient"
        private const val RECEIVE_TIMEOUT_SECONDS = 1.0
        private const val WRONG_DATABASE_KEY_CODE = 401
        private const val REQUEST_TIMEOUT_MS = 45_000L
        private const val PROXY_TEST_DC = 2
        private const val PROXY_NEEDS_CLIENT_CODE = 401
        private const val PROXY_TEST_TIMEOUT_SECONDS = 20.0
        private const val PROXY_READY_WAIT_MS = 10_000L
        private const val REQUEST_TIMEOUT_CODE = 408
        private const val COPY_TIMEOUT_MS = 30_000L
        private const val DOWNLOAD_PRIORITY = 16
        private const val DOWNLOAD_RESUME_INTERVAL_MS = 15_000L
        private const val THUMBNAIL_PRIORITY = 24
        private const val THUMBNAIL_EDGE = 320
        private const val DELETE_BATCH = 100
        private const val RATE_LIMIT_RETRIES = 5
        private const val RATE_LIMIT_FALLBACK_SECONDS = 5
        private const val STREAM_PRIORITY = 32
        private const val MARKER_SEPARATOR = "\n"
        private const val DISCOVERY_ATTEMPTS = 8
        private const val DISCOVERY_RETRY_MS = 1_500L
        private const val AUTH_WAIT_MS = 30_000L
        private const val CHAT_SEARCH_LIMIT = 50
        private const val CHAT_LIST_SETTLE_MS = 150L
        private const val CHAT_LIST_PAGE = 100
        private const val CHAT_LIST_LIMIT = 1_000
        private const val MAX_CHAT_LIST_PAGES = 50
        private val CHAT_LISTS = listOf("chatListMain", "chatListArchive")
        private val INTERNAL_DOCUMENT_NAMES = listOf(
            "teledrive.folders.json",
            "teledrive.keybackup"
        )
        private const val HISTORY_RETRIES = 3
        private const val HISTORY_RETRY_MS = 400L
        private const val PHOTO_PRIORITY = 16
        private const val MAX_LABEL_LENGTH = 48
        private const val STORAGE_CHAT_TITLE = "TeleDrive"
        private const val CLOSE_WAIT_LIMIT_MS = 5_000L
        private const val PREVIEW_PRIORITY = 1
        const val STORAGE_CHAT_MARKER = "#teledrive-storage"
    }
}
