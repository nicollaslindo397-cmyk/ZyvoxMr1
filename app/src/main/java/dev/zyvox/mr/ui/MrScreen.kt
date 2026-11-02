package dev.zyvox.mr.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import dev.zyvox.mr.spatial.screenToWorld
import dev.zyvox.mr.window.WindowController
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberNode
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView

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

    // Raiz de conteúdo. Os painéis são criados imperativamente e pendurados aqui — ver
    // [WindowPanelHost] para o motivo de não usar o composable `ViewNode { }`.
    val contentRoot = rememberNode(engine)

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
                val root = contentRoot
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

                        // Pixel → mundo no plano de interação. `screenToWorld` usa a projeção
                        // real que está sendo desenhada, então não precisamos reconstituir
                        // FOV nem base da câmera à mão.
                        val world = filamentView.screenToWorld(
                            sx,
                            sy,
                            MrTokens.INTERACTION_DEPTH_M,
                        )
                        cx = world.x
                        cy = world.y
                        cz = world.z
                        cursorValid = true
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
            // Os painéis vivem em `contentRoot`, adicionado à cena uma única vez.
            addChildNode(contentRoot)
        }

        // 3. HUD 2D.
        MrHud(viewModel = viewModel)
    }

    // ─── Criação dos hosts, depois que a cena existe ────────────────────────────────────
    DisposableEffect(engine, materialLoader, contentRoot, androidView) {
        val wHost = WindowPanelHost(
            engine = engine,
            materialLoader = materialLoader,
            root = contentRoot,
            ownerView = androidView,
        ).apply {
            onMinimize = { id -> viewModel.minimizeWindow(id) }
            onClose = { id -> viewModel.closeWindow(id) }
            onTitle = { id, title -> viewModel.setTitle(id, title) }
        }
        val dHost = DockPanelHost(
            engine = engine,
            materialLoader = materialLoader,
            root = contentRoot,
            ownerView = androidView,
            onAction = onDockAction,
        )
        val cHost = CursorPanelHost(
            engine = engine,
            materialLoader = materialLoader,
            root = contentRoot,
            ownerView = androidView,
        )
        val inputRouter = SpatialInputRouter(
            cursorHost = cHost,
            dockHost = dHost,
            windowHost = wHost,
            windowController = windowController,
        ).apply { onDockAction = onDockAction }

        windowHost = wHost
        dockHost = dHost
        cursorHost = cHost
        router = inputRouter

        onDispose {
            // Ordem: parar de rotear (pode haver gesto em voo com pointer preso no WebView),
            // depois destruir os painéis, que liberam WebView e janelas off-screen.
            inputRouter.reset()
            cHost.destroy()
            dHost.destroy()
            wHost.destroyAll()
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
