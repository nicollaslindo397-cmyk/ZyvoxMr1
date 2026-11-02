package dev.zyvox.mr.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
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
import dev.zyvox.mr.MrViewModel
import dev.zyvox.mr.camera.CameraController
import dev.zyvox.mr.dock.DockPanelView
import dev.zyvox.mr.hand.TrackedHand
import dev.zyvox.mr.spatial.HeadPoseTracker
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
            DockPanelView.Action.BROWSER ->
                viewModel.openWindow(context.getString(dev.zyvox.mr.R.string.url_default))

            DockPanelView.Action.NEW_WINDOW ->
                viewModel.openWindow(context.getString(dev.zyvox.mr.R.string.url_default))

            DockPanelView.Action.RECENTER -> viewModel.recenterWindows()

            DockPanelView.Action.CLOSE_ALL -> viewModel.closeAllWindows()

            DockPanelView.Action.TOGGLE_DOCK -> viewModel.toggleDock()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Passthrough.
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // 2. Cena 3D transparente.
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

        // 3. HUD 2D.
        MrHud(viewModel = viewModel)
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
internal class UprightSize(val width: Int, val height: Int) {
    companion object {
        fun of(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): UprightSize? {
            if (rawWidth <= 0 || rawHeight <= 0) return null
            val swap = rotationDegrees == 90 || rotationDegrees == 270
            return if (swap) UprightSize(rawHeight, rawWidth) else UprightSize(rawWidth, rawHeight)
        }
    }
}
