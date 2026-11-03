# Arquitetura do Zyvox MR

Este documento explica **por que** cada tecnologia foi escolhida, e registra honestamente o
que a arquitetura não faz. Projeto independente: nenhuma identidade visual, código ou
funcionalidade de qualquer outro produto foi reutilizada.

---

## As escolhas, e o que decidiu cada uma

| Requisito | Tecnologia | Por que esta e não outra |
|---|---|---|
| Passthrough da câmera traseira | **CameraX** `Preview` → `PreviewView` | Desenha direto numa Surface, sem cópia para CPU. É o caminho de menor latência disponível fora de ARCore. |
| Renderização 3D | **SceneView 4.65.0** (Filament 1.72.x por baixo) | PBR real, e traz `ViewNode` — que desenha uma View Android comum numa textura 3D. É o que torna possível um WebView no espaço sem escrever um renderer. |
| Hand tracking | **MediaPipe Hand Landmarker 0.10.26** | Ver a seção abaixo — foi a única opção viável, não uma preferência. |
| Janelas WebView | **`WebView` real** dentro de `ViewNode` | WebView de verdade: JS, cookies, scroll, vídeo. Não é uma captura de tela nem uma simulação. |
| Pose de cabeça | **`GAME_ROTATION_VECTOR`** (3DoF) | Sem ARCore não há VIO. Ver "O que esta arquitetura não faz". |

### Por que MediaPipe e não ARCore para as mãos

Isto foi verificado, não suposto:

- **ARCore clássico para celular não tem API de hand tracking.** Não existe
  `Frame.getHands()` nem equivalente. A percepção dele é plano, âncora, profundidade,
  imagem e rosto.
- **ARCore for Jetpack XR tem hand tracking — e só roda em Android XR.** É uma biblioteca
  separada (`androidx.xr.arcore`), para headsets como o Galaxy XR. As APIs dela **não estão
  disponíveis em aparelhos**.
- Logo, em um celular Android, MediaPipe Hand Landmarker (21 pontos por mão) é o padrão
  de fato. Não há alternativa de primeira linha.

### Por que CameraX e não ARCore para o passthrough

ARCore daria passthrough *e* pose 6DoF (janelas paradas no mundo enquanto você anda), que é
tecnicamente superior. Ele foi descartado por uma decisão explícita de escopo:

- ARCore exige **Google Play Services for AR** instalado e o aparelho na lista de ~400
  modelos suportados. Fora dela, não há experiência nenhuma.
- CameraX funciona em **qualquer Android 8.0+** com câmera.

A troca é concreta e está documentada: **cobertura de aparelhos em vez de 6DoF**. Ver abaixo.

---

## O que esta arquitetura não faz

Registrar isto importa mais do que listar o que funciona.

1. **Não há 6DoF.** Sem ARCore não existe odometria visual-inercial, então não há posição no
   mundo — só orientação. Se você **girar** o aparelho, a cena gira junto e as janelas ficam
   onde estavam no espaço: isso funciona. Se você **andar** para o lado, as janelas acompanham
   o aparelho em vez de ficarem paradas na sala. É 3DoF, não 6DoF, e é a diferença mais
   visível em relação a um Quest 3.

2. **Não há profundidade real nas mãos.** O MediaPipe devolve 21 pontos normalizados em
   coordenada de imagem; o `z` dele é profundidade *relativa* ao punho, sem escala métrica.
   Então o cursor vive num **plano de interação fixo** a
   `MrTokens.INTERACTION_DEPTH_M` (0,45 m) da câmera. Você aponta e aperta; não empurra um
   objeto para longe esticando o braço.

3. **O "vidro" do dock não tem blur de verdade.** Desfoque do fundo exigiria um shader próprio
   no Filament amostrando a textura do passthrough. O que está implementado é transparência +
   borda luminosa + canto arredondado, que é o que dá a leitura de vidro em escala de
   telefone.

4. **Hand tracking exige condições de luz.** É visão computacional numa câmera RGB: com pouca
   luz ou contra a luz, a detecção degrada e o cursor some. Não há como contornar isso por
   software.

---

## Fluxo de dados

```
Câmera traseira (CameraX)
├── Preview ──────────────────────────► PreviewView          [passthrough, camada 1]
└── ImageAnalysis (YUV 640×480)
        │  thread "zyvox-camera-analysis"
        ▼
    HandTrackerPipeline
        │  1. copia os 3 planos YUV e DESCARTA o quadro se já houver
        │     inferência em voo (nunca enfileira — enfileirar é o que
        │     transforma latência baixa em latência crescente)
        │  thread "zyvox-hand"
        │  2. YUV → RGB upright em meia resolução (sem JPEG, sem Bitmap)
        │  3. detectAsync (RunningMode.LIVE_STREAM)
        │  thread do MediaPipe
        │  4. landmarks → TrackedHand: pinch com histerese + suavização
        ▼
    MrViewModel.latestHandFrame   (@Volatile, NÃO é estado do Compose)
        │
        ▼  SceneView.onFrame  (~60 Hz, main thread)
    ├── HeadPoseTracker (GAME_ROTATION_VECTOR) ──► cameraNode.quaternion
    ├── ScreenMapper.imageToView ──► view.screenToWorld ──► cursor em metros
    ├── SpatialInputRouter ──► dock? janela? WebView?
    │       └── SyntheticTouch (MotionEvent) ──► webView.dispatchTouchEvent
    └── CursorPanelHost / WindowPanelHost / DockPanelHost (ViewNode)
                                                                  [cena 3D, camada 2]
    MrHud (Compose)                                              [HUD 2D, camada 3]
```

### As três camadas de tela

1. `PreviewView` — o mundo real.
2. `SceneView` com `SurfaceType.TextureSurface` + `isOpaque = false` — a cena 3D.
   **Tem de ser `TextureSurface`**: uma `SurfaceView` desenha numa camada separada *abaixo*
   da janela e não compõe com alpha, então o fundo sairia preto em vez de transparente.
3. HUD 2D em Compose — status e permissão. É 2D de propósito: precisa ser legível
   independentemente de para onde o aparelho aponta.

---

## Decisões que valem explicação

### `FrameRatePolicy.Continuous` em vez do padrão `OnDemand`

O padrão do SceneView **estaciona** o loop de render quando nada muda. A documentação da
biblioteca é explícita sobre o caso em que isso falha em silêncio: *"an external Stream
(camera, video) pushing content"* e *"a texture updated off-thread"*.

É exatamente o que um `WebView` num `ViewNode` faz — a página se redesenha sozinha, fora da
biblioteca. Com `OnDemand`, a cena congelaria no último frame, sem crash e sem log, e o
usuário leria isso como app travado. `Continuous(maxFps = 60)` custa bateria; é a troca certa
para uma experiência imersiva.

### Um `WindowManager` por painel

O `ViewNode.WindowManager` mede o conteúdo com `AT_MOST(display)` e se dimensiona pelo
**maior** filho. Se dock e janelas dividissem um manager:

- o dock (1120×190 px) seria re-medido para 1280×800 e apareceria como faixa larga com um
  vazio enorme embaixo dos botões;
- uma janela que cresce arrastaria e deslocaria **todas** as outras.

Manager separado custa uma janela de sistema a mais por painel — barato — e resolve os dois
problemas de vez.

### Conteúdo em pixel fixo, tamanho no mundo variável

O conteúdo de uma janela é sempre 1280×800 px. Redimensionar muda `pxPerUnits` (quantos
metros cada pixel ocupa), não a contagem de pixels. Consequências:

- o texto continua **nítido** em qualquer tamanho, em vez de ser esticado;
- existe **um único** `pxPerUnits` válido, derivado da largura — se largura e altura
  variassem independentemente, cada eixo teria o seu e o quad deformaria a página;
- por isso `resizeBy` é **proporcional**, como um player de vídeo.

### A lateralidade da mão é ignorada de propósito

O `Handedness` do MediaPipe devolve `Left`/`Right` assumindo imagem **espelhada** — convenção
de câmera frontal. Na câmera traseira, que é a deste app, o rótulo sai invertido, e pode
trocar entre quadros quando a mão gira.

Se o slot do cursor seguisse o rótulo, o cursor pularia de uma mão para a outra no meio de um
arrasto — e o objeto seria solto. Então o slot é atribuído por **posição na tela** com
histerese (`HandSlotAssigner`), e o rótulo é mantido só para exibição.

### O cursor segue a palma, não a ponta do indicador

A ponta do indicador é o landmark mais ruidoso — é o que mais se move. Usá-la faz o cursor
tremer justamente quando o usuário está parado mirando em algo pequeno. A palma (média de
punho + os quatro MCP) é estável, e o pinch continua sendo medido entre as pontas do polegar
e do indicador, que é onde o gesto acontece.

### O modo do gesto é travado no início

`WindowController` decide entre mover / redimensionar / tocar **uma vez**, no `DOWN`, e não
reavalia até o gesto terminar. Sem isso, arrastar uma janela e passar o cursor sobre o corpo
dela no meio do movimento mudaria o modo para "tocar" e a página começaria a rolar — que é o
pior defeito possível nesta interface.

O dock é testado **antes** das janelas porque está mais perto do usuário
(`DOCK_DEPTH_M` 0,40 m vs `DEFAULT_DEPTH` 0,55 m): num plano de interação único, o objeto
mais próximo é o que ele vê na frente.

### O dock não usa `SyntheticTouch`

As células do dock têm `setOnClickListener`. Se injetássemos um stream sintético de
`MotionEvent` **e** invocássemos a ação diretamente, cada botão dispararia **duas vezes**.
Escolhemos o caminho direto (determinístico) e o feedback de "pressionado" vem do destaque
visual. O custo é não haver ripple — aceitável para cinco botões.

O WebView, ao contrário, precisa do `MotionEvent` sintético: ali a ação é a página, e não há
como invocá-la diretamente.

### Por que `@Volatile` e não `mutableStateOf` no cursor

`latestHandFrame` e a posição do cursor mudam a ~30 Hz. Se fossem estado do Compose, cada
quadro de hand tracking recomporia a árvore inteira — o Compose passaria a custar mais que o
Filament e a latência do cursor **pioraria** com a otimização.

Regra que ficou no código: **se é lido em `onFrame`, não pode ser estado do Compose.**

---

## Layout do código

```
app/src/main/java/dev/zyvox/mr/
├── MainActivity.kt              Activity única, imersiva, singleTask
├── MrViewModel.kt               estado da sessão (janela = observável; quadro = @Volatile)
├── ZyvoxMrApplication.kt        aquece o provider do WebView
│
├── camera/CameraController.kt   Preview + ImageAnalysis, KEEP_ONLY_LATEST
│
├── hand/                        ── Kotlin puro onde possível, testável em JVM ──
│   ├── HandModel.kt             HandJoint (21 índices), HandPoint, TrackedHand, HandFrame
│   ├── GestureLogic.kt          pinchRatio, PinchDetector (histerese), LandmarkSmoother
│   ├── PipelineGates.kt         InferenceSlot (ticket), ModelTimestamps (rebase)
│   ├── FrameConvert.kt          YUV_420_888 → RGB upright em meia resolução
│   ├── HandSlotAssigner.kt      slot estável 0/1 por posição, não por lateralidade
│   └── HandTrackerPipeline.kt   orquestra as três threads
│
├── spatial/
│   ├── ScreenMapper.kt          imagem upright → pixel de view (compensa o aspect-fill)
│   ├── HeadPoseTracker.kt       GAME_ROTATION_VECTOR → quaternion da câmera
│   ├── SyntheticTouch.kt        pinch → MotionEvent DOWN/MOVE/UP/CANCEL
│   └── SpatialInputRouter.kt    decide dock vs janela vs WebView, a cada frame
│
├── window/
│   ├── WindowStack.kt           multitarefa: abrir/fechar/foco/mover/redimensionar
│   ├── WindowController.kt      máquina de estados do gesto (IDLE/DRAG/RESIZE/TOUCH)
│   ├── WindowMetrics.kt         metros ↔ pixels ↔ fração do quad
│   └── WindowPanelView.kt       barra de título + WebView real
│
├── dock/DockPanelView.kt        faixa compacta de vidro com cinco ações
│
└── ui/
    ├── MrScreen.kt              integra as três camadas
    ├── MrHud.kt                 status 2D
    ├── CameraPermissionGate.kt  permissão + saída para negativa permanente
    ├── CursorPanelHost.kt       cursor 3D pequeno
    ├── WindowPanelHost.kt       ciclo de vida dos ViewNode de janela
    ├── DockPanelHost.kt         ciclo de vida do ViewNode do dock
    └── MrTokens.kt              paleta e constantes espaciais
```

---

## Verificações que valem fazer no aparelho

Este projeto foi escrito sem acesso a um aparelho ou a um SDK Android, então o que está
coberto por teste unitário é a lógica pura (gesto, mapeamento, multitarefa) — **não** a
integração com câmera, Filament e WebView. Ao testar, estas são as coisas mais prováveis de
precisar de ajuste, em ordem:

1. **Alinhamento cursor ↔ dedo.** Se o cursor está deslocado, o suspeito é o
   `ScreenMapper` ou a orientação upright. A HUD mostra o pixel do cursor; compare com o dedo.
2. **Sentido da rotação da cena.** Se girar o aparelho move a cena na direção errada, o
   suspeito é a conversão `[w,x,y,z]` → `Quaternion(x,y,z,w)` no `HeadPoseTracker`.
3. **Toque caindo no pixel errado dentro da página.** Suspeito: `WindowMetrics` ou o desconto
   da barra de título no `WindowController`.
4. **Webview em branco.** Suspeito: ordem de inicialização do `WindowManager` (tem de ser
   `resume(ownerView)` **antes** de `addChildNode`).
5. **Latência.** `adb logcat -s ZyvoxHandPerf` — se `dropped` for alto e constante, o aparelho
   não acompanha; reduza a resolução de análise em `CameraController`.
