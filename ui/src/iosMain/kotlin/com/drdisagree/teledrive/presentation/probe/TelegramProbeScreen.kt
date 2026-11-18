package com.drdisagree.teledrive.presentation.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.core.crypto.KeychainCredentialStore
import com.drdisagree.teledrive.core.telegram.IosTelegramClient
import com.drdisagree.teledrive.core.telegram.StorageChannel
import com.drdisagree.teledrive.core.telegram.TelegramAuthState
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Stage-2 check screen: signs in with the real IosTelegramClient and reads the drive. Read-only on
 * purpose (listStorageChannels and fetchDocuments never create or edit anything), so it is safe to
 * point at a drive the Android app already uses. Replaced by the real UI in stage 4.
 */
@Composable
fun TelegramProbeScreen(
    client: IosTelegramClient,
    credentialStore: KeychainCredentialStore,
    tdlibVersion: String?
) {
    val auth by client.authState.collectAsState()
    val connection by client.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
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
        credentialStore.load()?.let { client.start(it) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Strato", style = MaterialTheme.typography.headlineMedium)
        Text(
            "TDLib ${tdlibVersion ?: "unavailable"} · ${auth.label()} · $connection",
            style = MaterialTheme.typography.bodySmall
        )

        when (val state = auth) {
            TelegramAuthState.Uninitialized, TelegramAuthState.Closed ->
                CredentialsForm(enabled = !busy) { credentials ->
                    run {
                        credentialStore.save(credentials)
                        client.start(credentials)
                    }
                }

            TelegramAuthState.Initializing, TelegramAuthState.LoggingOut ->
                Text("Conectando ao Telegram…")

            TelegramAuthState.WaitingForPhoneNumber -> {
                SingleField("Telefone com DDI (ex.: +5511999999999)", KeyboardType.Phone, !busy) {
                    run { client.submitPhoneNumber(it) }
                }
                OutlinedButton(onClick = { run { client.requestQrCodeAuthentication() } }) {
                    Text("Entrar confirmando no app do Telegram")
                }
            }

            is TelegramAuthState.WaitingForCode -> {
                Text("Código enviado para ${state.phoneNumber} (${state.channel}).")
                SingleField("Código", KeyboardType.Number, !busy) { run { client.submitCode(it) } }
                OutlinedButton(onClick = { run { client.resendCode() } }) { Text("Reenviar código") }
            }

            is TelegramAuthState.WaitingForQrScan -> {
                Text("Abra o link no app do Telegram (neste aparelho) e confirme o login.")
                Button(onClick = { openUrl(state.link) }) { Text("Confirmar no Telegram") }
                Text(state.link, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { run { client.restartAuthentication() } }) {
                    Text("Voltar ao login por telefone")
                }
            }

            TelegramAuthState.WaitingForEmailAddress ->
                SingleField("E-mail", KeyboardType.Email, !busy) { run { client.submitEmailAddress(it) } }

            is TelegramAuthState.WaitingForEmailCode -> {
                Text("Código enviado para ${state.emailPattern}.")
                SingleField("Código do e-mail", KeyboardType.Number, !busy) {
                    run { client.submitEmailCode(it) }
                }
            }

            is TelegramAuthState.WaitingForPassword -> {
                state.passwordHint?.let { Text("Dica: $it") }
                SingleField("Senha de duas etapas", KeyboardType.Password, !busy, secret = true) {
                    run { client.submitPassword(it) }
                }
            }

            TelegramAuthState.RegistrationRequired ->
                Text("Esta conta não existe. Crie-a primeiro no app oficial do Telegram.")

            is TelegramAuthState.Failed -> {
                Text("Falha: ${state.message}")
                credentialStore.load()?.let { saved ->
                    Button(onClick = { run { client.start(saved) } }) { Text("Tentar de novo") }
                }
                OutlinedButton(onClick = { credentialStore.clear(); run { client.logout() } }) {
                    Text("Trocar credenciais")
                }
            }

            TelegramAuthState.Ready -> DriveReader(client, busy) { run(it) }
        }

        error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DriveReader(
    client: IosTelegramClient,
    busy: Boolean,
    run: (suspend () -> Unit) -> Unit
) {
    var account by remember { mutableStateOf<String?>(null) }
    var drives by remember { mutableStateOf<List<StorageChannel>?>(null) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        runCatching { client.getCurrentUser() }.getOrNull()?.let { user ->
            account = listOfNotNull(user.firstName, user.username?.let { "@$it" }).joinToString(" ")
        }
    }

    Text("Conectado como ${account ?: "…"}")
    Button(enabled = !busy, onClick = {
        run {
            val found = client.listStorageChannels()
            drives = found
            files = found.firstOrNull()?.let { drive ->
                client.fetchDocuments(drive.chatId, 0L, FILE_PREVIEW_LIMIT).documents.map { doc ->
                    "${doc.fileName} · ${doc.sizeBytes / 1024} KB · " +
                            if (doc.caption.startsWith("tde1:")) "criptografado" else "aberto"
                }
            }.orEmpty()
        }
    }) { Text(if (busy) "Lendo…" else "Ler meus drives (somente leitura)") }

    drives?.let { found ->
        if (found.isEmpty()) {
            Text("Nenhum drive encontrado nesta conta.")
        } else {
            found.forEach { Text("Drive: ${it.title} — ${it.documentCount} arquivos") }
            if (files.isNotEmpty()) {
                Text("Mensagens mais recentes de \"${found.first().title}\":")
                files.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    OutlinedButton(enabled = !busy, onClick = { run { client.logout() } }) { Text("Sair") }
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
        visualTransformation = if (secret) PasswordVisualTransformation() else
            androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
    Button(enabled = enabled && value.isNotBlank(), onClick = { onSubmit(value.trim()) }) {
        Text("Enviar")
    }
}

private fun TelegramAuthState.label(): String = this::class.simpleName ?: "?"

private fun openUrl(link: String) {
    val url = NSURL.URLWithString(link) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}

private const val FILE_PREVIEW_LIMIT = 20
