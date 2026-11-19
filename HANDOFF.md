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
  `./gradlew :shared:desktopTest :ui:desktopTest :shared:compileKotlinIosArm64 :ui:compileKotlinIosArm64 :desktop:compileKotlin`
  (219 testes verdes: 202 no shared e 17 na ui). O módulo Android só compila com o Android SDK instalado.
- Se o caminho do projeto tiver acento (ex.: `C:\Users\João`), o plugin Android recusa o build: passe
  `"-Pandroid.overridePathCheck=true"` (com aspas no PowerShell, que senão quebra o argumento no ponto).
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
- A interface inteira (`ui/src/commonMain`) roda no iOS desde a etapa 4. O que muda por plataforma:
  - `ui/src/jvmCommonMain` guarda só o que é da JVM: zxing (`QrCode.jvm.kt`), zip4j (`ZipListing.jvm.kt`)
    e as datas/números via `java.text` (`PlatformFormat.jvm.kt`), idênticos ao TeleDrive original;
  - no iOS: QR pelo CoreImage, listagem de ZIP pelo `ZipCentralDirectory` (comum, testado contra o
    zip4j), datas pelo `NSDateFormatter`, miniaturas pelo UIKit (`IosThumbnailStore`), prévia em
    `IosPreviewScreen` (vídeo, áudio e PDF abrem no QuickLook);
  - `ui/src/iosMain/.../presentation/MainViewController.kt` é a entrada: sobe o Koin (`iosUiModule`),
    as tarefas de início do desktop e o Coil; `platform/IosPlatformProviders.kt` fornece seletores,
    compartilhar, abrir URL etc.;
  - idioma escolhido no app: os textos do Compose seguem `Locale.current`, que no iOS vem de
    `AppleLanguages`; o app grava essa chave, então a troca vale a partir da próxima abertura.
- `UpdateChecker` (só JVM consulta as releases do TeleDrive original) e `ApkIconExtractor` viraram
  `expect`; no iOS não há checagem de atualização nem ícone de APK.

## Etapas
| Etapa | Status |
|---|---|
| 1 — Base iOS + pipeline do IPA | ✅ release v0.1.0-etapa1 |
| 2 — Login no Telegram + leitura dos drives | ✅ testada no iPhone, release v0.2.0-etapa2 |
| 3 — Motor (sync, download/descriptografia, envio, restaurar chave) | ✅ testada no iPhone, release v0.3.0-etapa3 |
| 4 — Interface completa (portar ui/jvmCommonMain para commonMain; QR sem zxing; prévia de ZIP sem zip4j; miniaturas; textos traduzidos) | 🟡 IPA v0.4.0 no CI, **falta o teste no iPhone** e a release v0.4.0-etapa4 |
| 5 — Recursos do iOS (AVPlayer, backup da galeria de Fotos, BGTaskScheduler, NWPathMonitor, Face ID, extensão de compartilhamento, ProxyProbe) | pendente |

## Próximo passo
Quando o build do commit da etapa 4 terminar: criar a release `v0.4.0-etapa4` (pre-release, IPA anexado,
status "não testado"). Depois o usuário testa no iPhone:
1. O app abre direto na interface completa, com o login já feito (sessão da etapa 3).
2. Navegar pelos drives e pastas; miniaturas de fotos aparecem.
3. Abrir prévia de imagem, texto, ZIP (lista de arquivos) e PDF/vídeo (botão Abrir → QuickLook).
4. Baixar, enviar (botão + / app Arquivos), compartilhar, renomear, lixeira.
5. Configurações: trocar o idioma e reabrir o app; datas e textos no idioma escolhido.
6. Sair da conta e entrar de novo pelo QR (o QR agora é desenhado pelo CoreImage).

Se algo falhar, depurar a partir do erro exato mostrado na tela. A etapa 5 só começa quando o usuário
pedir.