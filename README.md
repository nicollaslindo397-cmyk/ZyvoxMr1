# Zyvox MR

> Aplicativo independente de Mixed Reality para Android, usando a câmera traseira como
> passthrough, com hand tracking de duas mãos, dock espacial e janelas WebView multitarefa.

Projeto independente. Não reutiliza identidade visual, código nem funcionalidades de nenhum
outro produto — a referência estética (dark, transparência, cantos arredondados, dock
compacto) é implementada do zero com paleta e ícones próprios.

---

## O que está implementado

| Requisito | Estado | Onde |
|---|---|---|
| Passthrough com câmera traseira | ✅ | `camera/CameraController.kt`, `ui/MrScreen.kt` |
| Permissão de câmera | ✅ com saída para negativa permanente | `ui/CameraPermissionGate.kt` |
| Hand tracking das duas mãos | ✅ 21 landmarks por mão | `hand/` |
| Gesto de pinch (polegar + indicador) | ✅ com histerese | `hand/GestureLogic.kt` |
| Cursor pequeno e discreto | ✅ 3D, ~1,6 cm no mundo | `ui/CursorPanelHost.kt` |
| Pegar, mover e soltar objetos | ✅ arrastar pela barra de título | `window/WindowController.kt` |
| Redimensionar janelas | ✅ proporcional, pela alça do canto | `window/WindowStack.kt` |
| Dock flutuante compacto | ✅ ~0,34 m, vidro escuro, 5 ações | `dock/DockPanelView.kt` |
| Ocultar/mostrar o dock | ✅ | ação `TOGGLE_DOCK` |
| Janelas WebView reais | ✅ `WebView` de verdade, não captura de tela | `window/WindowPanelView.kt` |
| Minimizar / fechar janela | ✅ | `window/WindowPanelView.kt` |
| Várias janelas simultâneas | ✅ até 4, independentes do dock | `window/WindowStack.kt` |
| Ambiente com profundidade | ✅ cena 3D + plano de interação a 0,45 m | `spatial/` |
| Baixa latência / estabilidade | ✅ 3 threads, descarte de quadro, `LIVE_STREAM` | `hand/HandTrackerPipeline.kt` |

## ⚠️ Leia antes de testar: o que esta arquitetura **não** faz

Registrado em detalhe em [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md). O resumo honesto:

- **Não há 6DoF.** A decisão foi CameraX em vez de ARCore, para funcionar em **qualquer
  Android 8.0+** sem depender do Google Play Services for AR. Consequência: **girar** o
  aparelho move a cena corretamente, mas **andar** para o lado faz as janelas acompanharem o
  aparelho em vez de ficarem paradas na sala. É 3DoF.
- **Não há profundidade métrica nas mãos.** O MediaPipe devolve `z` relativo ao punho, sem
  escala. O cursor vive num plano fixo a 0,45 m. Você aponta e aperta; não empurra objetos
  para longe esticando o braço.
- **O "vidro" não tem blur real.** É transparência + borda luminosa + canto arredondado. Blur
  de verdade exigiria shader próprio amostrando a textura do passthrough.
- **Hand tracking precisa de luz.** É visão computacional em câmera RGB; com pouca luz ou
  contra a luz o cursor some. Não há contorno por software.

## Por que estas tecnologias

Verificado, não suposto:

- **ARCore clássico para celular não tem API de hand tracking.** ARCore for Jetpack XR tem —
  e só roda em headsets Android XR, não em aparelhos. Logo, em celular, **MediaPipe Hand
  Landmarker é o padrão de fato**.
- **SceneView 4.65.0** (Filament por baixo) traz `ViewNode`, que desenha uma View Android
  comum numa textura 3D — é o que permite um WebView no espaço sem escrever renderer.
- **CameraX** desenha o preview direto numa Surface, sem cópia para CPU: menor latência
  disponível fora de ARCore.

## Buildar e instalar

**Duas rotas, mesmo APK.** Detalhes completos em [`docs/BUILD.md`](docs/BUILD.md).

### Rota 1 — GitHub Actions (não instala nada)

1. Push desta branch → aba **Actions** → run de **"Build debug APK"**.
2. Baixe o artefato `zyvox-mr-debug-apk` no fim da página do run.
3. Vem zipado:

```bash
unzip zyvox-mr-debug-apk.zip && adb install -r app-debug.apk
```

### Rota 2 — Local

Requer **JDK 21** (obrigatório: o AAR do SceneView publica bytecode Java 21), Gradle 9.6+,
platform **android-37**.

```bash
gradle wrapper --gradle-version 9.8.2   # o repo não versiona o .jar do wrapper
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK menor para o seu aparelho (~50 MB em vez de ~150 MB):

```bash
./gradlew :app:assembleDebug -PabiFilters=arm64-v8a
```

## Testes

```bash
./gradlew :app:testDebugUnitTest        # lógica pura, sem aparelho, ~30 s
adb logcat -s ZyvoxHandPerf             # fps, latência de inferência, quadros descartados
```

A lógica de maior risco é coberta por teste unitário em JVM (gesto, histerese de pinch,
mapeamento imagem→tela, multitarefa de janelas, máquina de estados do gesto, travas de
concorrência do pipeline). A integração com câmera, Filament e WebView **não** tem como ser
testada sem aparelho — ver "Verificações que valem fazer no aparelho" em
[`docs/ARQUITETURA.md`](docs/ARQUITETURA.md) para a lista do que ajustar primeiro.

## Layout

```
app/src/main/java/dev/zyvox/mr/
├── MainActivity.kt           Activity única, imersiva, singleTask
├── MrViewModel.kt            estado da sessão
├── camera/                   passthrough + análise (Preview + ImageAnalysis)
├── hand/                     MediaPipe: pipeline de 3 threads, gesto, suavização
├── spatial/                  imagem→tela, pose 3DoF, toque sintético, roteador de gesto
├── window/                   multitarefa, máquina de estados, métricas, WebView
├── dock/                     dock de vidro
└── ui/                       tela, HUD, permissão, hosts dos painéis 3D
```

## Status do desenvolvimento

O app foi escrito **sem acesso a aparelho ou SDK Android** neste ambiente, então a ordem de
desenvolvimento pedida foi seguida no desenho, mas a validação em hardware está pendente. O
caminho até o APK passa pelo CI (Rota 1), que é também o que vai revelar os primeiros erros de
integração.
