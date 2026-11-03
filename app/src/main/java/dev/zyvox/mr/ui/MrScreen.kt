package dev.zyvox.mr.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.filament.View
import dev.romainguy.kotlin.math.Float3
import dev.zyvox.mr.MrViewModel
import dev.zyvox.mr.camera.CameraController
import dev.zyvox.mr.dock.DockPanelView
import dev.zyvox.mr.hand.TrackedHand
import dev.zyvox.mr.settings.DisplayMode
import dev.zyvox.mr.spatial.HeadPoseTracker
import dev.zyvox.mr.spatial.InterfaceTransform
import dev.zyvox.mr.spatial.ScreenMapper
import dev.zyvox.mr.spatial.SpatialInputRouter
import dev.zyvox.mr.window.WindowController
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView
// `screenToWorld` é extensão de `com.google.android.filament.View` que vive em
// io.github.sceneview.utils.ViewKt. Retorna Position? (= Float3?) — anulável, porque devolve
// null quando a view ainda não tem câmera. Nunca confundir com uma função deste projeto:
// não existe dev.zyvox.mr.spatial.screenToWorld.
import io.github.sceneview.utils.screenToWorld
import kotlin.math.sqrt

// ─── Constantes da projeção do cursor ─────────────────────────────────────────────────────
//
// `View.screenToWorld(xPx, yPx, z)` não recebe metros. O `z` é profundidade normalizada de
// recorte, e a KDoc de `Camera.viewToWorld` no SceneView define: **1 = plano próximo,
// 0 = infinito**. Estes valores ecoam exatamente as duas amostras que o próprio SceneView usa
// em `Camera.viewToRay` (`startPosition = z 0.0f`, `endPosition = z 1.0f`), então são as
// extremidades reais do intervalo e não um palpite.
private const val NEAR_SAMPLE_Z = 1.0f
private const val FAR_SAMPLE_Z = 0.0f

// Distância da câmera ao plano próximo, em metros. Espelha o padrão do `CameraNode` do
// SceneView (`_near = 0.01f`). Só é usada para converter "distância desde o plano próximo" em
// "distância desde a câmera"; como 0,01 m é 2% de INTERACTION_DEPTH_M, uma pequena deriva aqui
// desloca o cursor em milímetros — e o plano de interação já é arbitrário por construção.
private const val NEAR_PLANE_M = 0.01f

/**
 * A experiência MR: passthrough no fundo, cena 3D transparente por cima, HUD 2D no topo.
 *
 * ### As três camadas e por que nesta ordem
 *
 * 1. **`PreviewView`** — a câmera traseira, desenhada pelo CameraX direto numa Surface. É o
 *    passthrough. Fica embaixo porque é o mundo real.
 * 2. **`SceneView`** com `SurfaceType.TextureSurface` + `isOpaque = false` — a cena 3D. Tem de
 *    ser `TextureSurface`: uma `SurfaceView` desenha numa camada separada abaixo da janela e
 *    não compõe com alpha, então o fundo apareceria preto em vez de transparente. O custo é
 *    uma cópia de textura a mais por frame, que é o preço de ter o passthrough visível.
 * 3. **HUD 2D** em Compose por cima — status e aviso de permissão. É 2D de propósito: não
 *    precisa de profundidade e não deve competir com o conteúdo espacial.
 *
 * ### Por que `FrameRatePolicy.Continuous`
 * O padrão do SceneView é `OnDemand()`, que **estaciona** o loop de render quando nada muda. A
 * documentação é explícita sobre o caso em que isso falha em silêncio: "an external Stream
 * (camera, video) pushing content" e "a texture updated off-thread". É exatamente o que um
 * `WebView` num `ViewNode` faz — a página se redesenha sozinha, fora da biblioteca. Com
 * `OnDemand` a cena congelaria no último frame e o usuário leria isso como app travado, sem
 * nenhum log para diagnosticar. `Continuous(maxFps = 60)` custa bateria, que é a troca correta
 * para uma experiência imersiva.
 *
 * ### O que roda em `onFrame` (e por que nada disso é estado do Compose)
 * `onFrame` roda ~60×/s. Tudo que ele lê é campo `@Volatile` comum, não `mutableStateOf`:
 * se fosse estado do Compose, cada frame de hand tracking recomporia a árvore inteira e a
 * latência do cursor *pioraria*. Ver a KDoc de [MrViewModel].
 */
@Composable
fun MrScreen(viewModel: MrViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val androidView = LocalView.current

    // ─── Hand tracking ──────────────────────────────────────────────────────────────────
    val pipeline = remember(viewModel) { viewModel.ensurePipeline() }

    // ─── Câmera (passthrough + análise) ─────────────────────────────────────────────────
    val previewView = remember(context) {
        PreviewView(context).apply {
            // FILL_CENTER é o aspect-fill que [ScreenMapper] modela. Trocar o ScaleType sem
            // trocar o mapeamento desloca o cursor do dedo.
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            contentDescription = context.getString(dev.zyvox.mr.R.string.cd_camera_preview)
        }
    }

    val cameraController = remember(pipeline) {
        pipeline?.let { CameraController(context, it) }
    }

    DisposableEffect(lifecycleOwner, cameraController) {
        cameraController?.bind(lifecycleOwner, previewView)
        onDispose { cameraController?.release() }
    }

    // ─── Pose de cabeça (3DoF) ──────────────────────────────────────────────────────────
    val headPose = remember(context) { HeadPoseTracker(context) }
    DisposableEffect(headPose) {
        headPose.start()
        onDispose { headPose.stop() }
    }

    // ─── Cena 3D ────────────────────────────────────────────────────────────────────────
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val filamentView: View = rememberView(engine)
    val cameraNode = rememberCameraNode(engine)
    val scene = rememberScene(engine)

    // ─── Raiz de conteúdo dos painéis ───────────────────────────────────────────────────
    // Os painéis (janelas WebView, dock, cursor) são `ViewNode` criados imperativamente e
    // pendurados num único nó-raiz — ver [WindowPanelHost] para o motivo de não usar o
    // composable `ViewNode { }` do SceneScope.
    //
    // Esse nó-raiz NÃO pode ser criado com `rememberNode(engine)` e anexado depois:
    // `SceneScope.attach()` e `NodeLifecycle()` são `internal`/`@RestrictTo(LIBRARY_GROUP_PREFIX)`,
    // e `SceneScope` não expõe `addChildNode` (esse método é de `Node`, não do escopo). A única
    // porta pública para a raiz da cena é o composable `SceneScope.Node(...)`, que cria E anexa
    // o nó. Então deixamos ele criar o nó e o capturamos via `apply`, que roda uma única vez
    // dentro do `remember(engine)` da biblioteca.
    //
    // É `MutableState` (e não `by mutableStateOf`) de propósito: o `apply` precisa escrever sem
    // delegação de propriedade, e os hosts leem `.value` fora da composição.
    val sceneRoot: MutableState<Node?> = remember { mutableStateOf(null) }

    // Dimensões da view, para o mapeamento imagem→tela. `onSizeChanged` dá o valor real da
    // superfície, que é o que o `screenToWorld` espera em pixel.
    var viewWidthPx by remember { mutableStateOf(0) }
    var viewHeightPx by remember { mutableStateOf(0) }

    /**
     * Dimensões da imagem de análise **após** a rotação para upright.
     *
     * O `onFrame` as publica para o [HandSkeleton], que precisa do mesmo mapeamento
     * imagem→tela que o cursor usa. Se cada um derivasse essas dimensões do seu jeito, o
     * esqueleto e o cursor divergiriam e o usuário veria o cursor fora da mão que brilha.
     *
     * É estado observável escrito a ~30 Hz, mas o `onFrame` compara antes de escrever: o valor
     * só muda de fato numa rotação do aparelho ou reconfiguração da câmera, então na prática
     * não há recomposição por quadro.
     */
    var uprightWidth by remember { mutableStateOf(0) }
    var uprightHeight by remember { mutableStateOf(0) }

    /**
     * Viewport para o qual a largura visível do dock já foi medida.
     *
     * Estado do Compose, não campo solto: o `onFrame` roda fora da composição, e escrever em
     * estado aqui só acontece quando o viewport muda de fato (rotação, multijanela), nunca por
     * quadro. Ver a medição em `onFrame` para por que cachear é correto e não apenas econômico.
     */
    var dockFitWidth by remember { mutableStateOf(0) }
    var dockFitHeight by remember { mutableStateOf(0) }

    /**
     * Corda, em metros, entre as direções unitárias das duas bordas da tela.
     *
     * Não é a largura visível: é o fator que, multiplicado pela distância, dá a largura visível
     * naquela distância. Ver o bloco de medição em `onFrame` para o porquê de esta grandeza ser a
     * certa para cachear.
     */
    var frustumSpan by remember { mutableFloatStateOf(0f) }

    // Estado das janelas (observável: muda raramente).
    val windows = viewModel.windows

    // Os hosts são criados uma vez e vivem enquanto a cena viver. `remember` sem chave: se
    // fossem recriados a cada recomposição, cada recomposição vazaria um WebView.
    var windowHost by remember { mutableStateOf<WindowPanelHost?>(null) }
    var dockHost by remember { mutableStateOf<DockPanelHost?>(null) }
    var cursorHost by remember { mutableStateOf<CursorPanelHost?>(null) }
    var router by remember { mutableStateOf<SpatialInputRouter?>(null) }

    val windowController = remember(viewModel) { WindowController(viewModel.stack) }

    // Ação do dock. Definida aqui porque precisa do viewModel.
    val onDockAction: (DockPanelView.Action) -> Unit = { action ->
        when (action) {
            DockPanelView.Action.YOUTUBE -> viewModel.openWindow(
                url = context.getString(dev.zyvox.mr.R.string.url_youtube),
                title = context.getString(dev.zyvox.mr.R.string.dock_youtube),
            )

            DockPanelView.Action.CHROME -> viewModel.openWindow(
                url = context.getString(dev.zyvox.mr.R.string.url_chrome),
                title = context.getString(dev.zyvox.mr.R.string.dock_chrome),
            )

            // "Janela" abre a página inicial, que tem a barra de endereço: é o caminho para
            // qualquer outro site, agora que YouTube e Chrome têm botão próprio.
            DockPanelView.Action.NEW_WINDOW -> viewModel.openWindow(
                context.getString(dev.zyvox.mr.R.string.url_default),
            )

            DockPanelView.Action.RECENTER -> viewModel.recenterWindows()

            DockPanelView.Action.DOCK_SMALLER ->
                viewModel.adjustDockScale(-MrTokens.DOCK_SCALE_STEP)

            DockPanelView.Action.DOCK_BIGGER ->
                viewModel.adjustDockScale(+MrTokens.DOCK_SCALE_STEP)

            DockPanelView.Action.CLOSE_ALL -> viewModel.closeAllWindows()

            DockPanelView.Action.TOGGLE_DOCK -> viewModel.toggleDock()
        }
    }

    // Lido aqui, e não dentro do Box, para que a mudança de modo recomponha esta camada.
    val displayMode = viewModel.settings.displayMode

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Passthrough.
        //
        // Fica na árvore nos DOIS modos de fundo, inclusive na tempestade. Removê-lo aqui
        // desfaria o `DisposableEffect` que faz o bind da câmera, e o `ImageAnalysis` que
        // alimenta o hand tracking morreria junto — o modo virtual ficaria sem rastreamento de
        // mãos, que é justamente a única forma de interagir quando não há vídeo do mundo real.
        // Então a câmera segue rodando e apenas fica coberta pela camada 2.
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // 2. Ambiente virtual, quando escolhido. Cobre o passthrough por completo: é opaco de
        // propósito, porque um céu de tempestade com o vídeo da câmera vazando por baixo não lê
        // como ambiente, lê como defeito.
        if (displayMode != DisplayMode.MR_PASSTHROUGH) {
            StormBackdrop()
        }

        // 3. Cena 3D transparente.
        SceneView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    viewWidthPx = size.width
                    viewHeightPx = size.height
                },
            engine = engine,
            materialLoader = materialLoader,
            view = filamentView,
            scene = scene,
            cameraNode = cameraNode,
            surfaceType = SurfaceType.TextureSurface,
            isOpaque = false,
            frameRatePolicy = FrameRatePolicy.Continuous(maxFps = 60),
            // Sem manipulador de câmera: quem manda na câmera é o sensor de rotação. Deixar o
            // `rememberCameraManipulator` padrão faria um arrasto de dedo na tela girar a cena,
            // brigando com a pose da cabeça.
            cameraManipulator = null,
            onFrame = {
                val wHost = windowHost
                val dHost = dockHost
                val cHost = cursorHost
                val inputRouter = router
                if (wHost == null || dHost == null || cHost == null || inputRouter == null) {
                    return@SceneView
                }

                // ── Câmera segue a orientação do aparelho ─────────────────────────────────
                // Escrever `quaternion` é um mutator público do SceneView, então ele pede o
                // próprio frame (Component.onComponentChanged). Com Continuous isso é só
                // redundante, não errado.
                cameraNode.quaternion = headPose.orientation

                // ── Sincroniza painéis com o estado ───────────────────────────────────────
                wHost.sync(windows)
                dHost.setVisible(viewModel.dockVisible)

                // ── Mede o campo de visão, para o dock ocupar uma fração dele ──────────────
                //
                // O dock ocupa uma FRAÇÃO do que a câmera enxerga, em vez de um número fixo em
                // metros. Metros absolutos não significam nada sem conhecer o FOV: os mesmos
                // 0,34 m podem ocupar metade da tela num aparelho e transbordar noutro. Foi
                // exatamente isso que fez a interface parecer "cada vez menor" — o tamanho em
                // pixel do dock subiu de 1120 para 1400 enquanto a largura em metros ficou em
                // 0,34, então cada botão encolheu de 6,8 para 4,25 cm sem que nada avisasse.
                //
                // E a largura visível CRESCE COM A DISTÂNCIA (`2·d·tan(hfov/2)`), o que é o que
                // torna o tamanho aparente invariante quando o usuário aproxima ou afasta a
                // interface. Medir uma largura só, numa profundidade fixa, e reusá-la para
                // qualquer distância quebraria isso: afastar a interface a faria encolher na tela,
                // que é o defeito que este bloco existe para impedir.
                //
                // Então o que se cacheia é a CORDA entre as direções unitárias das duas bordas,
                // que multiplicada pela distância dá a largura naquela distância. A corda é
                // invariante à orientação da cabeça porque as duas direções giram juntas — o
                // ângulo entre elas não muda — então medí-la uma vez por viewport é correto, não
                // apenas econômico. Custa quatro `screenToWorld` por mudança de viewport, e zero
                // por quadro.
                if (frustumSpan <= 0f || dockFitWidth != viewWidthPx || dockFitHeight != viewHeightPx) {
                    val midY = viewHeightPx / 2f
                    val leftDir = rayDirection(filamentView, 0f, midY)
                    val rightDir = rayDirection(filamentView, viewWidthPx.toFloat(), midY)
                    if (leftDir != null && rightDir != null) {
                        val spanX = rightDir.x - leftDir.x
                        val spanY = rightDir.y - leftDir.y
                        val spanZ = rightDir.z - leftDir.z
                        val span = sqrt(spanX * spanX + spanY * spanY + spanZ * spanZ)
                        if (span.isFinite() && span > 1e-4f) {
                            frustumSpan = span
                            dockFitWidth = viewWidthPx
                            dockFitHeight = viewHeightPx
                        }
                    }
                }

                val layout = viewModel.settings.layout
                if (frustumSpan > 0f) {
                    dHost.applyVisibleWidth(frustumSpan * layout.distanceM * MrTokens.DOCK_WIDTH_FRACTION)
                }
                // Posição, inclinação e escala vêm das configurações do usuário, aplicadas por
                // quadro. `applyPlacement` é idempotente — cada setter só escreve quando o valor
                // mudou — então o custo por quadro são algumas comparações de float, e é isso que
                // faz um slider mover a interface em tempo real, sem reiniciar nem esperar
                // recomposição.
                dHost.applyPlacement(InterfaceTransform.placementFor(layout))

                // ── Projeção do cursor ────────────────────────────────────────────────────
                val hand: TrackedHand? = viewModel.latestHandFrame.hands.firstOrNull()
                var cursorValid = false
                var cx = 0f
                var cy = 0f
                var cz = 0f

                if (hand != null && cameraController != null && viewWidthPx > 0 && viewHeightPx > 0) {
                    val upright = UprightSize.of(
                        cameraController.analysisWidth,
                        cameraController.analysisHeight,
                        cameraController.rotationDegrees,
                    )
                    if (upright != null) {
                        if (uprightWidth != upright.width || uprightHeight != upright.height) {
                            uprightWidth = upright.width
                            uprightHeight = upright.height
                        }
                        // Landmark → pixel de tela, compensando o corte do aspect-fill.
                        val (sx, sy) = ScreenMapper.imageToView(
                            nx = hand.viewX,
                            ny = hand.viewY,
                            imageWidth = upright.width,
                            imageHeight = upright.height,
                            viewWidth = viewWidthPx,
                            viewHeight = viewHeightPx,
                        )
                        viewModel.cursorScreenX = sx
                        viewModel.cursorScreenY = sy

                        // ── Pixel → mundo, a uma distância MÉTRICA fixa da câmera ──────────
                        //
                        // `screenToWorld(xPx, yPx, z)` NÃO recebe metros: o `z` é profundidade
                        // normalizada de recorte, 1 = plano próximo e 0 = infinito (ver a KDoc de
                        // `Camera.viewToWorld` no SceneView). Passar `INTERACTION_DEPTH_M` direto
                        // ali — como uma versão anterior deste código fazia — colocaria o cursor
                        // numa profundidade sem relação com 0,45 m, e o erro seria invisível: o
                        // cursor apareceria, só que na distância errada, e o toque não alinharia
                        // com as janelas.
                        //
                        // Então amostramos o mesmo pixel nas duas extremidades do intervalo e
                        // derivamos a direção do raio pela diferença.
                        //
                        // O SINAL dessa direção não é assumido. O `Camera.viewToRay` do SceneView
                        // monta `direction = viewToWorld(z=1) - viewToWorld(z=0)`, ou seja, do
                        // plano próximo para o infinito — o oposto do que queremos aqui. Em vez de
                        // decorar qual convenção vale, comparamos a distância de cada amostra à
                        // câmera: em 3DoF a câmera fica na origem da cena (só escrevemos
                        // `quaternion`, nunca `position`), então a amostra mais distante da origem
                        // é a mais funda na cena, e o vetor que sai da mais próxima e vai à mais
                        // distante aponta para longe da câmera por construção. Se uma futura
                        // versão do SceneView inverter o mapeamento de `z`, isto continua certo.
                        //
                        // Custo: duas `screenToWorld` por frame, cada uma invertendo uma matriz
                        // 4×4. É aceitável, mas é o primeiro lugar a otimizar se o orçamento de
                        // frame apertar — a projeção não muda entre frames, então as duas amostras
                        // podem virar uma origem + direção pré-calculadas.
                        val nearSample = filamentView.screenToWorld(sx, sy, NEAR_SAMPLE_Z)
                        val farSample = filamentView.screenToWorld(sx, sy, FAR_SAMPLE_Z)
                        if (nearSample != null && farSample != null) {
                            // `viewToWorld` devolve `Position()` — a origem — quando o `w` do
                            // espaço de recorte degenera (`almostEquals 0.0f`). Uma amostra
                            // degenerada na origem faria a comparação de distância abaixo escolher
                            // o lado errado, então as duas precisam ser utilizáveis.
                            val nearDist2 = nearSample.x * nearSample.x +
                                nearSample.y * nearSample.y + nearSample.z * nearSample.z
                            val farDist2 = farSample.x * farSample.x +
                                farSample.y * farSample.y + farSample.z * farSample.z

                            if (nearDist2 > 1e-12f && farDist2 > 1e-12f && farDist2 > nearDist2) {
                                var dx = farSample.x - nearSample.x
                                var dy = farSample.y - nearSample.y
                                var dz = farSample.z - nearSample.z
                                val len = sqrt(dx * dx + dy * dy + dz * dz)

                                // Sem esta guarda, dividir por ~0 produz NaN, que se propaga para a
                                // posição do cursor e congela o painel sem nenhum log.
                                if (len > 1e-6f) {
                                    dx /= len; dy /= len; dz /= len
                                    val travel = MrTokens.INTERACTION_DEPTH_M - NEAR_PLANE_M
                                    val px = nearSample.x + dx * travel
                                    val py = nearSample.y + dy * travel
                                    val pz = nearSample.z + dz * travel
                                    // Última barreira antes de publicar: um NaN aqui viraria um
                                    // painel invisível e um gesto que nunca fecha.
                                    if (px.isFinite() && py.isFinite() && pz.isFinite()) {
                                        cx = px; cy = py; cz = pz
                                        cursorValid = true
                                    }
                                }
                            }
                        }
                    }
                }

                viewModel.cursorWorldX = cx
                viewModel.cursorWorldY = cy
                viewModel.cursorWorldZ = cz
                viewModel.cursorValid = cursorValid

                // ── Roteia o gesto ────────────────────────────────────────────────────────
                inputRouter.onFrame(
                    cursorX = cx,
                    cursorY = cy,
                    cursorZ = cz,
                    cursorValid = cursorValid,
                    hand = hand,
                    // O quadro inteiro, não só a mão do cursor: os gestos globais olham a mão
                    // DIREITA, que pode não ser a mesma que o `firstOrNull()` acima escolheu.
                    frame = viewModel.latestHandFrame,
                    windows = windows,
                )
            },
        ) {
            // `SceneScope` não tem `addChildNode` — esse método é de `Node`, não do escopo — e
            // `attach()`/`NodeLifecycle()` são `internal`/`@RestrictTo(LIBRARY_GROUP_PREFIX)`,
            // então não dá para adotar um nó criado lá fora. A única porta pública para a raiz da
            // cena é o composable `Node(...)`, que cria E anexa. Deixamos ele criar o nó e o
            // capturamos em `apply`, que roda uma única vez dentro do `remember(engine)` da
            // biblioteca. Os painéis imperativos são pendurados nele pelo DisposableEffect abaixo.
            Node(apply = { sceneRoot.value = this })
        }

        // 4. Esqueleto da mão, sobre o fundo (câmera ou tempestade) e sobre a cena.
        //
        // Acima do `SceneView` de propósito: as "cordinhas" descrevem a mão real, que está no
        // vídeo. Se ficassem abaixo da cena, uma janela WebView aberta cobriria o esqueleto e o
        // feedback visual sumiria justo quando o usuário está tentando acertar algo.
        HandSkeleton(
            viewModel = viewModel,
            imageWidth = uprightWidth,
            imageHeight = uprightHeight,
        )

        // 5. HUD 2D.
        MrHud(viewModel = viewModel)

        // 6. Centro de configurações, por cima do HUD.
        //
        // Depois do `MrHud` porque o painel cobre a tela inteira: se viesse antes, o botão "Ajustes"
        // ficaria desenhado por cima do scrim e continuaria recebendo toque com o painel aberto, o
        // que fecharia e reabriria o painel a cada toque no canto.
        if (viewModel.settingsPanelOpen) {
            SettingsPanel(
                viewModel = viewModel,
                onClose = { viewModel.toggleSettingsPanel() },
            )
        }
    }

    // ─── Criação dos hosts, depois que a cena existe ────────────────────────────────────
    // A chave inclui `sceneRoot.value` de propósito: este bloco está DEPOIS do `Box` no corpo do
    // composable, então o `Node(...)` lá dentro já rodou e já publicou o nó — mas se a ordem um
    // dia mudar, a chave faz o efeito relançar quando o nó aparecer, em vez de construir os hosts
    // contra `null` e morrer silenciosamente.
    val root = sceneRoot.value
    DisposableEffect(engine, materialLoader, root, androidView) {
        // Sem nó-raiz não há onde pendurar painel, e o bloco fica vazio. Mas NÃO com
        // `if (root == null) { onDispose { }; return@DisposableEffect }`: o bloco de um
        // DisposableEffect tem de DEVOLVER um DisposableEffectResult, e `return@DisposableEffect`
        // sem valor devolve Unit. Chamar `onDispose { }` e depois retornar constrói o resultado e
        // o joga fora — o compilador responde "Return type mismatch: expected
        // 'DisposableEffectResult', actual 'Unit'". Daí a forma positiva: o `onDispose` final é a
        // última expressão e vale como retorno em todos os caminhos.
        if (root != null) {
            val wHost = WindowPanelHost(
                engine = engine,
                materialLoader = materialLoader,
                root = root,
                ownerView = androidView,
            ).apply {
                onMinimize = { id -> viewModel.minimizeWindow(id) }
                onClose = { id -> viewModel.closeWindow(id) }
                onTitle = { id, title -> viewModel.setTitle(id, title) }
            }
            val dHost = DockPanelHost(
                engine = engine,
                materialLoader = materialLoader,
                root = root,
                ownerView = androidView,
                onAction = onDockAction,
            )
            val cHost = CursorPanelHost(
                engine = engine,
                materialLoader = materialLoader,
                root = root,
                ownerView = androidView,
            )
            val inputRouter = SpatialInputRouter(
                cursorHost = cHost,
                dockHost = dHost,
                windowHost = wHost,
                windowController = windowController,
            )
            // Fora do `apply` de propósito. Lá dentro, `onDockAction = onDockAction` não faz o que
            // parece: o lado DIREITO resolve pelo receiver implícito, mas o lado ESQUERDO de uma
            // atribuição não resolve — ele pega o `val onDockAction` do composable (linha 166), e o
            // compilador responde "'val' cannot be reassigned". Os dois lados da mesma expressão
            // apontavam para coisas diferentes. Aqui, `inputRouter.` e o nome simples são inequívocos.
            inputRouter.onDockAction = onDockAction
            // Os dois gestos globais da mão direita. Ver `updateGlobalGestures` no router para o
            // porquê de pinch valer só no espaço vazio: sem essa restrição não haveria como usar
            // a mão direita para tocar num botão, porque todo pinch esconderia a interface.
            inputRouter.onToggleDock = { viewModel.toggleDock() }
            inputRouter.onToggleBackground = { viewModel.toggleBackground() }

            windowHost = wHost
            dockHost = dHost
            cursorHost = cHost
            router = inputRouter
        }

        onDispose {
            // Ordem: parar de rotear (pode haver gesto em voo com pointer preso no WebView),
            // depois destruir os painéis, que liberam WebView e janelas off-screen. As variáveis
            // dos hosts vivem dentro do `if`, então isto não as referencia — e nem precisa: com
            // root nulo nada foi criado, e os `var ... by remember` já são null.
            router?.reset()
            cursorHost?.destroy()
            dockHost?.destroy()
            windowHost?.destroyAll()
            windowHost = null
            dockHost = null
            cursorHost = null
            router = null
        }
    }
}

/**
 * Dimensões da imagem **após** a rotação para upright.
 *
 * O conversor YUV produz uma imagem já rotacionada, e o [ScreenMapper] precisa das dimensões
 * dessa imagem rotacionada — não das do sensor. Passar as do sensor quando a rotação é 90/270
 * inverte a proporção e o cursor sai completamente do lugar, o que é o bug mais fácil de
 * introduzir aqui e o mais difícil de perceber sem olhar o número.
 */
/**
 * Direção unitária do raio que sai da câmera e passa por um pixel da tela.
 *
 * ### Por que duas amostras em vez de um `z` de profundidade
 * `screenToWorld(x, y, z)` recebe um `z` NORMALIZADO (1 = plano próximo), não uma distância em
 * metros, então ele sozinho não responde "onde está este pixel a 0,8 m da câmera". Duas amostras
 * no mesmo pixel dão dois pontos do mesmo raio, e a diferença é a direção.
 *
 * ### Por que o sinal da direção não é decorado
 * `Camera.viewToRay` do SceneView monta `viewToWorld(z=1) - viewToWorld(z=0)`, ou seja, do plano
 * próximo para o infinito — o oposto do que se quer aqui. Em vez de confiar na convenção, comparamos
 * a distância de cada amostra à origem: em 3DoF a câmera fica na origem (só escrevemos `quaternion`,
 * nunca `position`), então a amostra mais distante da origem é a mais funda na cena, e o vetor que
 * vai da mais próxima à mais distante aponta para longe da câmera por construção. Se uma versão
 * futura do SceneView inverter o mapeamento de `z`, isto continua certo.
 *
 * @return `null` quando qualquer amostra é degenerada. Nunca devolve vetor de comprimento zero ou
 *   com NaN: quem chama usa o resultado como divisor.
 */
private fun rayDirection(view: View, screenX: Float, screenY: Float): Float3? {
    val near = view.screenToWorld(screenX, screenY, NEAR_SAMPLE_Z) ?: return null
    val far = view.screenToWorld(screenX, screenY, FAR_SAMPLE_Z) ?: return null

    // `screenToWorld` devolve a origem quando o `w` do espaço de recorte degenera. Uma amostra na
    // origem faria a comparação de distância abaixo escolher o lado errado.
    val nearDist2 = near.x * near.x + near.y * near.y + near.z * near.z
    val farDist2 = far.x * far.x + far.y * far.y + far.z * far.z
    if (nearDist2 <= 1e-12f || farDist2 <= 1e-12f || farDist2 <= nearDist2) return null

    val dx = far.x - near.x
    val dy = far.y - near.y
    val dz = far.z - near.z
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    if (len <= 1e-6f) return null

    return Float3(dx / len, dy / len, dz / len)
}

internal class UprightSize(val width: Int, val height: Int) {
    companion object {
        fun of(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): UprightSize? {
            if (rawWidth <= 0 || rawHeight <= 0) return null
            val swap = rotationDegrees == 90 || rotationDegrees == 270
            return if (swap) UprightSize(rawHeight, rawWidth) else UprightSize(rawWidth, rawHeight)
        }
    }
}
