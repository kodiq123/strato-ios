# Handoff — Strato (porta iOS do TeleDrive)

Estado do projeto para continuar em outra máquina. Leia tudo antes de agir.

## O que é
Porta para iOS do [TeleDrive](https://github.com/Mahmud0808/TeleDrive) (Kotlin Multiplatform + Compose,
TDLib, Apache-2.0). Gera um **IPA sem assinatura** para sideload com Apple ID gratuito. Build no
**GitHub Actions** (`.github/workflows/ios.yml`, job `ipa` em `macos-26-intel`). O nome público é
**Strato** (bundle `app.strato.ios`, framework `StratoKit`).

## Regras do usuário
- Responder em **português (pt-BR)**.
- **Parar e avisar assim que o build do IPA entrar na fila.** Não acompanhar o build nem trabalhar em
  paralelo, para economizar tokens.
- Ao concluir uma etapa, criar uma **GitHub Release** `vX.Y.0-etapaN` (pre-release, com o IPA anexado).
- Manter o `LICENSE` e o crédito ao upstream.
- **Nunca renomear os identificadores usados no canal**, que garantem a compatibilidade com o Android:
  - título do canal "TeleDrive" e o marcador `#teledrive-storage`;
  - prefixos de legenda `td1:` e `tde1:`;
  - arquivos `teledrive.folders.json` e `teledrive.keybackup`;
  - pacotes `com.drdisagree`.
- Os formatos de criptografia precisam ficar idênticos byte a byte aos do Android. `CryptoSelfTest`
  contém fixtures geradas pelo código javax.crypto ORIGINAL; nunca regenerar a partir do código novo.

## Ambiente
- Ferramentas: Git, JDK 21 e GitHub CLI. Recarregar o PATH em cada shell; `JAVA_HOME` = JDK 21.
- A compilação cruzada de klibs Apple está ativa, então o Windows compila o Kotlin de iOS. Só o link
  final e o IPA exigem o macOS do CI.
- Verificações locais:
  `./gradlew :shared:desktopTest :shared:compileKotlinIosArm64 :ui:compileKotlinIosArm64 :desktop:compileKotlin`
  (201 testes verdes). O módulo Android só compila com o Android SDK instalado.
- O job `simulator-tests` costuma ser cancelado por falta de máquina arm64. Ele não bloqueia o IPA.

## Arquitetura no iOS
- TDLib: TDLibFramework estático do Swiftgram, versão 1.8.69, chamado via ponte Swift `TdJsonBridge`
  (interface `TdJsonTransport`). O `IosTelegramClient` usa o JSON do td_api. A API dessa versão difere
  da 1.8.56 do Android.
- Criptografia: AES-GCM pelo CryptoKit (`CryptoKitAesGcm`), PBKDF2 pelo CommonCrypto e Keychain via
  `KeychainStore` (Swift).
- O motor fica em `commonMain` sobre `core/io`:
  - `File`, `InputStream`, `OutputStream` e `IOException` são `expect`; no JVM são typealias de java.io
    (o Android não muda); no iOS são implementados sobre Foundation/POSIX;
  - gzip via zlib e SHA-256 via CommonCrypto;
  - `Lock` substitui `synchronized`;
  - `IoSelfTest` confere a camada de I/O do iOS contra o java.io.
- O grafo Koin do iOS está em `shared/src/iosMain/.../platform/IosEngine.kt`, com as implementações em
  `IosPlatform.kt`.
- A tela atual é de teste: `ui/src/iosMain/.../presentation/probe/DriveProbeScreen.kt`.

## Etapas
| Etapa | Status |
|---|---|
| 1 — Base iOS + pipeline do IPA | ✅ release v0.1.0-etapa1 |
| 2 — Login no Telegram + leitura dos drives | ✅ testada no iPhone, release v0.2.0-etapa2 (a descrição ainda diz "não testado"; atualizar com o OK do usuário) |
| 3 — Motor (sync, download/descriptografia, envio, restaurar chave) | 🟡 release v0.3.0-etapa3, **falta o teste no iPhone** |
| 4 — Interface completa (portar ui/jvmCommonMain para commonMain; QR sem zxing; prévia de ZIP sem zip4j; miniaturas; textos traduzidos) | pendente |
| 5 — Recursos do iOS (AVPlayer, backup da galeria de Fotos, BGTaskScheduler, NWPathMonitor, Face ID, extensão de compartilhamento, ProxyProbe) | pendente |

## Próximo passo
O usuário testa a v0.3.0 no iPhone:
1. Autoteste de compatibilidade.
2. Escolher um drive e indexar.
3. Baixar um arquivo e conferir no app Arquivos.
4. Enviar um arquivo e conferir no Android.
5. Restaurar a chave e baixar um arquivo 🔒.

Se tudo funcionar, marcar a etapa 3 como testada e só começar a etapa 4 quando o usuário pedir. Se
algo falhar, depurar a partir do erro exato mostrado na tela.
