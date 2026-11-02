# Como buildar o Zyvox MR

Duas rotas produzem o mesmo `app-debug.apk`. Escolha a que for mais fácil para você.

---

## Rota 1 — GitHub Actions (não precisa instalar nada)

É o caminho que exige zero toolchain local.

1. Faça push desta branch (ou abra um PR).
2. Abra a aba **Actions** do repositório → run mais recente de **"Build debug APK"**.
3. Espere ~10 min. No fim da página do run, na seção **Artifacts**, baixe
   `zyvox-mr-debug-apk`.
4. O artefato vem **zipado**:

```bash
unzip zyvox-mr-debug-apk.zip
adb install -r app-debug.apk
```

O workflow roda testes unitários antes de montar o APK. Se os testes falharem, o job falha e
os relatórios sobem como artefato `test-reports` — é a primeira coisa a abrir nesse caso.

---

## Rota 2 — Build local

### Pré-requisitos

| Ferramenta | Versão mínima | Por quê |
|---|---|---|
| JDK | **21** | O AAR do SceneView 4.65.0 publica bytecode Java 21 com `inline` functions públicas. Com JDK 17 o build falha apontando para classes do Kotlin stdlib, o que é confuso de diagnosticar. |
| Gradle | **9.6.0** (recomendado 9.8.2) | Exigência do AGP 9.4. |
| Android SDK platform | **37** | O AAR do SceneView declara `minCompileSdk = 37`. |
| Android SDK build-tools | **37.0.0** | Casado com a platform 37. |
| Android Studio | Rabbit 1 (2026.2.1) ou mais novo | Se preferir IDE. Qualquer versão que suporte AGP 9.4 serve. |

### Passo a passo em linha de comando

```bash
# 1. Aponte para o SDK. O CI usa ANDROID_HOME; localmente tanto faz qual das duas você usa.
export ANDROID_HOME="$HOME/Android/Sdk"        # Linux
# export ANDROID_HOME="$HOME/Library/Android/sdk"   # macOS

# 2. Instale plataforma e build-tools, e aceite as licenças.
#    Sem aceitar licença o sdkmanager trava esperando input.
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-37" "build-tools;37.0.0" "platform-tools"

# 3. O repo não versiona gradle/wrapper/gradle-wrapper.jar (é binário), então ./gradlew
#    não funciona direto do clone. Duas opções:
#
#    (a) gerar o wrapper uma vez — depois disso ./gradlew funciona para sempre:
gradle wrapper --gradle-version 9.8.2
./gradlew :app:assembleDebug
#
#    (b) ou usar o gradle instalado diretamente:
gradle :app:assembleDebug
```

O APK sai em:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Instalando no aparelho

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### APK menor para o seu aparelho

O APK debug carrega as libs nativas do Filament e do MediaPipe para **todas** as ABIs — dá
~150 MB. Cortando para a ABI do seu aparelho cai para ~50 MB e o build acelera bastante:

```bash
gradle :app:assembleDebug -PabiFilters=arm64-v8a
```

Quase todo aparelho moderno é `arm64-v8a`. Para descobrir o seu:

```bash
adb shell getprop ro.product.cpu.abi
```

---

## O modelo de hand tracking

O `hand_landmarker.task` (~7 MB) **não** é versionado no repositório. Ele é baixado durante o
build pela tarefa `fetch<Variant>HandModel` (`:app:fetchDebugHandModel`,
`:app:fetchReleaseHandModel`), registrada por variant, com verificação de tamanho — e entra no
APK **sem compressão**, porque o MediaPipe o memory-maps em runtime (recomprimir quebra o
`createFromOptions`).

Não existe uma tarefa chamada `fetchHandModel`. O registro é por variant porque os assets
gerados passam por `variant.sources.assets.addGeneratedSourceDirectory`, que é a única forma que
o AGP 9 aceita — ele proíbe `Provider` no SourceSet API (`assets.srcDir(provedor)`). O ganho é
que o AGP passa a declarar o download como dependência de *todo* consumidor dos assets, em vez de
cada um ter que ser ligado à mão.

Caminho após o download:

```
app/build/generated/mediapipe-model/<variant>/hand_landmarker.task
```

Se sua rede bloqueia `storage.googleapis.com`, use `./scripts/fetch-hand-model.sh`, que baixa para
os assets **estáticos**:

```bash
./scripts/fetch-hand-model.sh
```

Sem o modelo o app **não crasha**: passthrough, dock e janelas continuam funcionando, e a HUD
mostra "hand tracking inativo" em vermelho. Foi uma decisão deliberada — é melhor entregar uma
experiência parcial e honesta do que uma tela preta.

---

## Verificações rápidas

```bash
# Só os testes unitários (não precisa de aparelho; ~30 s).
gradle :app:testDebugUnitTest

# Só baixar o modelo, para separar "a rede falhou" de "o código não compila".
# É por variant: não existe `:app:fetchHandModel`.
gradle :app:fetchDebugHandModel

# Diagnóstico de latência do hand tracking com o aparelho ligado.
adb logcat -s ZyvoxHandPerf
```

O `ZyvoxHandPerf` imprime a cada 2 s: fps de entrega de quadros da câmera, custo do hand-off,
custo da conversão YUV→RGB, latência de inferência e quantos quadros foram descartados. É o
primeiro lugar para olhar quando algo parecer atrasado.

---

## Problemas conhecidos

| Sintoma | Causa provável | Correção |
|---|---|---|
| `Dependency … requires libraries not available in compileSdk 37` | Platform 37 não instalada | Rode o `sdkmanager` do passo 2 |
| `Cannot access class … Java 21` ou erro apontando para o stdlib do Kotlin | JDK menor que 21 rodando o Gradle | `java -version`; ajuste `org.gradle.java.home` ou `JAVA_HOME` |
| `./gradlew: No such file or directory` | Wrapper jar não versionado | Rode `gradle wrapper --gradle-version 9.8.2` uma vez |
| Hand tracking inativo, HUD vermelho | Modelo ausente nos assets | `./scripts/fetch-hand-model.sh` ou `gradle :app:fetchDebugHandModel` |
| Tela preta com o dock aparecendo | `PreviewView` não recebeu frame | Confira a permissão de câmera e `adb logcat -s ZyvoxCamera` |
| Instalação falha com `INSTALL_FAILED_OLDER_SDK` | Aparelho abaixo do Android 8.0 | `minSdk` é 26; não há suporte abaixo disso |
