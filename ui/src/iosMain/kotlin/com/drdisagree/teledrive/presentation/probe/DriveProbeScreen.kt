package com.drdisagree.teledrive.presentation.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.FileNameUtils
import com.drdisagree.teledrive.core.io.File
import com.drdisagree.teledrive.core.io.absolutePath
import com.drdisagree.teledrive.core.io.copyTo
import com.drdisagree.teledrive.core.io.name
import com.drdisagree.teledrive.core.telegram.TelegramAuthState
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.DriveFolder
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.repository.ChannelRepository
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.KeyBackupRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import com.drdisagree.teledrive.domain.repository.TelegramAuthRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.platform.IosEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.drdisagree.teledrive.core.crypto.CryptoSelfTest
import com.drdisagree.teledrive.core.io.IoSelfTest
import org.koin.core.Koin
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Stage-3 check screen: the real shared engine on iOS (index sync, folders, downloads with
 * decryption, uploads, key restore). Replaced by the full UI in stage 4.
 */
@Composable
fun DriveProbeScreen(koin: Koin, tdlibVersion: String?) {
    val auth = remember { koin.get<TelegramAuthRepository>() }
    val authState by auth.authState.collectAsState()
    val connection by auth.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val run: (suspend () -> Unit) -> Unit = { block ->
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { koin.get<TransferRepository>().recoverOrphanedTransfers() }
        IosEngine.migrateStage2Credentials(koin)
        auth.startFromStoredCredentials().also { if (it is AppResult.Failure) error = it.error.toString() }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Strato", style = MaterialTheme.typography.headlineMedium)
        Text(
            "TDLib ${tdlibVersion ?: "?"} · ${authState::class.simpleName} · $connection",
            style = MaterialTheme.typography.bodySmall
        )
        SelfTest(koin)

        when (val state = authState) {
            TelegramAuthState.Uninitialized, TelegramAuthState.Closed ->
                CredentialsForm(!busy) { credentials -> run { auth.configure(credentials).orThrow() } }

            TelegramAuthState.Initializing, TelegramAuthState.LoggingOut -> Text("Conectando ao Telegram…")

            TelegramAuthState.WaitingForPhoneNumber -> {
                SingleField("Telefone com DDI (ex.: +5511999999999)", KeyboardType.Phone, !busy) {
                    run { auth.submitPhoneNumber(it).orThrow() }
                }
                OutlinedButton(onClick = { run { auth.requestQrCodeAuthentication().orThrow() } }) {
                    Text("Entrar confirmando no app do Telegram")
                }
            }

            is TelegramAuthState.WaitingForCode -> {
                Text("Código enviado para ${state.phoneNumber}.")
                SingleField("Código", KeyboardType.Number, !busy) { run { auth.submitCode(it).orThrow() } }
                OutlinedButton(onClick = { run { auth.resendCode().orThrow() } }) { Text("Reenviar código") }
            }

            is TelegramAuthState.WaitingForQrScan -> {
                Text("Abra o link no app do Telegram e confirme o login.")
                Button(onClick = { openUrl(state.link) }) { Text("Confirmar no Telegram") }
                OutlinedButton(onClick = { run { auth.restartAuthentication().orThrow() } }) {
                    Text("Voltar ao login por telefone")
                }
            }

            TelegramAuthState.WaitingForEmailAddress ->
                SingleField("E-mail", KeyboardType.Email, !busy) { run { auth.submitEmailAddress(it).orThrow() } }

            is TelegramAuthState.WaitingForEmailCode ->
                SingleField("Código do e-mail", KeyboardType.Number, !busy) {
                    run { auth.submitEmailCode(it).orThrow() }
                }

            is TelegramAuthState.WaitingForPassword -> {
                state.passwordHint?.let { Text("Dica: $it") }
                SingleField("Senha de duas etapas", KeyboardType.Password, !busy, secret = true) {
                    run { auth.submitPassword(it).orThrow() }
                }
            }

            TelegramAuthState.RegistrationRequired ->
                Text("Esta conta não existe. Crie-a primeiro no app oficial do Telegram.")

            is TelegramAuthState.Failed -> {
                Text("Falha: ${state.message}")
                Button(onClick = { run { auth.startFromStoredCredentials().orThrow() } }) { Text("Tentar de novo") }
                OutlinedButton(onClick = { run { auth.logout().orThrow() } }) { Text("Sair") }
            }

            TelegramAuthState.Ready -> DriveSection(koin, busy, run)
        }

        error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DriveSection(koin: Koin, busy: Boolean, run: (suspend () -> Unit) -> Unit) {
    val channels = remember { koin.get<ChannelRepository>() }
    val sync = remember { koin.get<SyncRepository>() }
    val files = remember { koin.get<FileRepository>() }
    val transfers = remember { koin.get<TransferRepository>() }
    val settings = remember { koin.get<SettingsRepository>() }
    val storagePaths = remember { koin.get<AppStoragePaths>() }

    val drives by channels.observeChannels().collectAsState(emptyList())
    val activeChatId by settings.preferences.map { it.storageChatId }.collectAsState(null)
    val syncing by sync.syncing.collectAsState(false)
    val indexed by sync.indexedSoFar.collectAsState(0)
    val active by transfers.observeSection(TransferSection.ACTIVE, 20).collectAsState(emptyList())
    val failed by transfers.observeSection(TransferSection.FAILED, 5).collectAsState(emptyList())
    val path = remember { mutableStateListOf<DriveFolder>() }
    val folderId = path.lastOrNull()?.id

    LaunchedEffect(Unit) { channels.refresh() }
    // Same order as AppViewModel: index on start, catch up, then follow edits from other devices.
    LaunchedEffect(activeChatId) {
        path.clear()
        if (activeChatId == null || channels.activeDriveMissing()) return@LaunchedEffect
        sync.syncOnStart()
        launchFollow(sync)
    }

    val folders by remember(activeChatId, folderId) {
        if (activeChatId == null) emptyFlow() else files.observeFolders(folderId, showHidden = true, showArchived = true)
    }.collectAsState(emptyList())
    val list by remember(activeChatId, folderId) {
        if (activeChatId == null) emptyFlow()
        else files.observeFiles(FileQuerySpec(chatId = activeChatId, folderId = folderId, filterByFolder = true))
    }.collectAsState(emptyList())

    Text("Drives", style = MaterialTheme.typography.titleMedium)
    drives.forEach { drive ->
        val marker = if (drive.chatId == activeChatId) "● " else ""
        TextButton(enabled = !busy, onClick = {
            run { channels.switchTo(drive.chatId).orThrow() }
        }) { Text("$marker${drive.title} — ${drive.remoteFileCount} arquivos") }
    }
    if (syncing) {
        Text("Indexando… $indexed itens")
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    if (activeChatId != null) {
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { run { sync.incrementalSync().orThrow() } }) {
                Text("Sincronizar")
            }
            Button(enabled = !busy, onClick = {
                pickFiles { picked -> run { upload(picked, folderId, files, transfers, storagePaths) } }
            }) { Text("Enviar arquivos…") }
        }
        KeyRestore(koin, busy, run)

        Text(
            "Pasta: /" + path.joinToString("/") { it.name },
            style = MaterialTheme.typography.titleSmall
        )
        if (path.isNotEmpty()) {
            TextButton(onClick = { path.removeAt(path.lastIndex) }) { Text("⬆ Voltar") }
        }
        folders.forEach { folder ->
            Text(
                "📁 ${folder.name}",
                modifier = Modifier.fillMaxWidth().clickable { path.add(folder) }.padding(vertical = 6.dp)
            )
        }
        list.forEach { file ->
            FileRow(file, active.firstOrNull { it.fileId == file.id }, busy) {
                run { transfers.enqueueDownload(file.id).orThrow() }
            }
        }
        if (folders.isEmpty() && list.isEmpty() && !syncing) Text("Pasta vazia.")
    }

    if (active.isNotEmpty() || failed.isNotEmpty()) {
        HorizontalDivider()
        Text("Transferências", style = MaterialTheme.typography.titleMedium)
        active.forEach { TransferRow(it) }
        failed.forEach { Text("✗ ${it.displayName}: ${it.errorMessage}", color = MaterialTheme.colorScheme.error) }
        if (failed.isNotEmpty()) {
            TextButton(onClick = { run { transfers.clearFinished() } }) { Text("Limpar concluídas/falhas") }
        }
    }

    HorizontalDivider()
    OutlinedButton(enabled = !busy, onClick = { run { koin.get<TelegramAuthRepository>().logout().orThrow() } }) {
        Text("Sair da conta")
    }
}

@Composable
private fun FileRow(file: DriveFile, transfer: TransferTask?, busy: Boolean, onDownload: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val lock = if (file.isEncrypted) "🔒 " else ""
        Text("$lock${file.name}")
        Text("${humanBytes(file.sizeBytes)} · ${file.mimeType}", style = MaterialTheme.typography.bodySmall)
        when {
            transfer != null -> TransferRow(transfer)
            file.localPath != null && File(file.localPath!!).exists() ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ No aparelho", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { shareFile(file.localPath!!) }) { Text("Abrir / compartilhar") }
                }
            file.remoteFileId != null -> TextButton(enabled = !busy, onClick = onDownload) { Text("Baixar") }
            else -> Text("Aguardando envio", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransferRow(task: TransferTask) {
    Column {
        Text(
            "${task.type.name.lowercase()} · ${task.displayName} · ${(task.progress * 100).toInt()}% · " +
                "${humanBytes(task.speedBytesPerSecond)}/s" + (task.stage?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall
        )
        LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun KeyRestore(koin: Koin, busy: Boolean, run: (suspend () -> Unit) -> Unit) {
    val keys = remember { koin.get<KeyBackupRepository>() }
    var open by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        open = !open
        if (open) run { hint = (keys.backupHint() as? AppResult.Success)?.value }
    }) { Text(if (open) "Fechar restauração de chave" else "Restaurar chave de criptografia…") }
    if (!open) return
    Text("Necessário só para arquivos 🔒. Use a frase-senha do backup de chave feito no Android.")
    hint?.let { Text("Dica: $it", style = MaterialTheme.typography.bodySmall) }
    SingleField("Frase-senha", KeyboardType.Password, !busy, secret = true) { passphrase ->
        run {
            status = when (val result = keys.restore(passphrase.toCharArray())) {
                is AppResult.Success -> if (result.value) "Chave restaurada ✓" else "Frase-senha incorreta"
                is AppResult.Failure -> "Falhou: ${result.error}"
            }
        }
    }
    status?.let { Text(it) }
}

/** Runs the crypto fixtures (made by the original JVM code) and the file/stream layer checks. */
@Composable
private fun SelfTest(koin: Koin) {
    var result by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        scope.launch {
            result = withContext(Dispatchers.Default) {
                CryptoSelfTest.run() + IoSelfTest.run(koin.get<AppStoragePaths>().cacheDir)
            }
        }
    }) { Text("Autoteste de compatibilidade") }
    result?.let { failures ->
        if (failures.isEmpty()) {
            Text("Autoteste: tudo OK ✓")
        } else {
            failures.forEach { Text("✗ $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private suspend fun upload(
    picked: List<String>,
    folderId: String?,
    files: FileRepository,
    transfers: TransferRepository,
    storagePaths: AppStoragePaths
) {
    val staging = File(storagePaths.filesDir, "imports").apply { mkdirs() }
    for (path in picked) {
        val source = File(path)
        val name = FileNameUtils.uniqueName(FileNameUtils.sanitize(source.name)) { File(staging, it).exists() }
        val staged = source.copyTo(File(staging, name))
        source.delete()
        val file = files.importLocalFile(staged.absolutePath, folderId, source.name).orThrow()
        transfers.enqueueUpload(file.id).orThrow()
    }
}

/** followRemoteChanges suspends for the session; it runs in the composition's scope. */
private suspend fun launchFollow(sync: SyncRepository) {
    runCatching { sync.catchUpWithRemote() }
    sync.followRemoteChanges()
}

private fun <T> AppResult<T>.orThrow(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> throw IllegalStateException(error.toString())
}

private fun humanBytes(value: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024
        unit++
    }
    return if (unit == 0) "$value B" else "${(size * 10).toLong() / 10.0} ${units[unit]}"
}

@Composable
private fun CredentialsForm(enabled: Boolean, onSubmit: (TelegramCredentials) -> Unit) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    Text("Use o api_id e o api_hash da sua conta em my.telegram.org → API development tools.")
    OutlinedTextField(
        value = apiId,
        onValueChange = { apiId = it.filter(Char::isDigit) },
        label = { Text("api_id") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = apiHash,
        onValueChange = { apiHash = it.trim() },
        label = { Text("api_hash") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        enabled = enabled && apiId.isNotEmpty() && apiHash.length >= 32,
        onClick = { onSubmit(TelegramCredentials(apiId.toInt(), apiHash)) }
    ) { Text("Continuar") }
}

@Composable
private fun SingleField(
    label: String,
    keyboard: KeyboardType,
    enabled: Boolean,
    secret: Boolean = false,
    onSubmit: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
    Button(enabled = enabled && value.isNotBlank(), onClick = { onSubmit(value.trim()) }) { Text("Enviar") }
}

private fun openUrl(link: String) {
    val url = NSURL.URLWithString(link) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}
