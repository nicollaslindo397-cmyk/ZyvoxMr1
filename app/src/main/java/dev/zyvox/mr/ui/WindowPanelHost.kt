package dev.zyvox.mr.ui

import android.view.View
import com.google.android.filament.Engine
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import dev.zyvox.mr.spatial.SyntheticTouch
import dev.zyvox.mr.window.MrWindow
import dev.zyvox.mr.window.WindowMetrics
import dev.zyvox.mr.window.WindowPanelView

/**
 * Um painel de janela já materializado na cena: o `ViewNode`, o `WindowManager` off-screen
 * dele, a View hospedeira (com o `WebView`) e o injetor de toque da mão.
 */
class WindowPanelRecord(
    val id: String,
    val node: ViewNode,
    val windowManager: ViewNode.WindowManager,
    val panelView: WindowPanelView,
    val touch: SyntheticTouch,
) {
    /**
     * O último estado aplicado. [apply] roda a cada frame (é chamado de `onFrame`), então
     * sem esta guarda re-escreveríamos `pxPerUnits` 60×/s — e o setter dele chama
     * `updateGeometry()`, que re-deriva geometria **e** collision shape. Isso é trabalho
     * pesado e inútil numa janela parada, e é o tipo de custo que some o frame budget sem
     * aparecer em nenhum log.
     */
    private var applied: MrWindow? = null

    fun apply(window: MrWindow) {
        if (applied == window) return
        applied = window

        // `position` é o CENTRO do quad, e o mundo olha para -Z, então a profundidade entra
        // como z negativo. Um painel "a 0,55 m à frente" fica em z = -0,55.
        node.position = Position(window.xMeters, window.yMeters, -window.depthMeters)

        // O conteúdo é sempre o mesmo tamanho em pixel; o que muda com o redimensionamento é
        // quantos metros cada pixel ocupa. Mexer em `pxPerUnits` (e não no tamanho da View)
        // é o que mantém o texto nítido em vez de esticá-lo.
        node.pxPerUnits = WindowMetrics.CONTENT_WIDTH_PX / window.widthMeters

        // Minimizar oculta sem destruir: recriar um WebView custa centenas de ms e perderia
        // o estado da página (scroll, formulário preenchido, vídeo).
        node.isVisible = !window.minimized

        panelView.setTitle(window.title)
    }

    fun destroy(root: Node) {
        // Ordem importa, e a inversa dela vaza janela do sistema:
        //
        // 1. remover da cena → dispara `onRemovedFromScene` → `windowManager.removeView(layout)`,
        //    desanexando a hierarquia da View da janela off-screen;
        // 2. destruir o WebView, que é o objeto pesado e que segura contexto;
        // 3. `pause()`/`destroy()` no manager, que remove a janela off-screen do
        //    WindowManager do sistema. Pular este passo deixa uma janela fantasma viva até o
        //    processo morrer — e com várias janelas abertas e fechadas isso acumula.
        runCatching { root.removeChildNode(node) }
        runCatching { panelView.destroyWebView() }
        runCatching { windowManager.pause() }
        runCatching { windowManager.destroy() }
    }
}

/**
 * Mantém o conjunto de `ViewNode` de janelas em sincronia com [dev.zyvox.mr.window.WindowStack].
 *
 * ### Por que imperativo e não o composable `ViewNode { }`
 * Três motivos, todos vindos de limitações documentadas do `ViewNode`:
 *
 * 1. **Um `WindowManager` se dimensiona pelo MAIOR filho.** Se todas as janelas dividissem um
 *    manager, cada painel seria re-medido para o tamanho do maior — e uma janela que cresce
 *    arrastaria e deslocaria as outras. Um manager por janela custa uma janela de sistema a
 *    mais cada, que é barato; o artefato visual não é.
 * 2. **`SceneView` aceita um único `viewNodeWindowManager`**, então managers extras precisam
 *    ser criados e ter o ciclo de vida gerenciados por nós de qualquer forma.
 * 3. Precisamos da **View hospedeira de verdade** (para injetar o toque da mão no WebView). O
 *    construtor imperativo aceita uma `View` direto; o composable embrulha tudo num
 *    `ComposeView` e não herda `CompositionLocal` nenhum.
 *
 * ### A ordem de inicialização é crítica
 * `windowManager.resume(ownerView)` **antes** de `root.addChildNode(node)`. O motivo:
 * `addChildNode` dispara `onAddedToScene`, que faz `windowManager.addView(layout)` — e o
 * `layout` só é medido e desenhado depois que a janela off-screen existe. Inverter produz um
 * retângulo preto: o nó está na cena, mas a textura nunca recebeu pixel.
 */
class WindowPanelHost(
    private val engine: Engine,
    private val materialLoader: MaterialLoader,
    private val root: Node,
    private val ownerView: View,
) {
    private val panels = LinkedHashMap<String, WindowPanelRecord>()

    val all: Collection<WindowPanelRecord> get() = panels.values

    fun panel(id: String): WindowPanelRecord? = panels[id]

    /** Cria, atualiza e remove painéis para refletir [windows]. */
    fun sync(windows: List<MrWindow>) {
        val wanted = windows.map { it.id }.toSet()

        // Remove primeiro: libera memória antes de alocar a janela nova, o que importa
        // porque estamos perto do teto de WebView simultâneos.
        panels.keys.filterNot { it in wanted }.forEach { id ->
            panels.remove(id)?.destroy(root)
        }

        windows.forEach { window ->
            val existing = panels[window.id]
            if (existing != null) {
                existing.apply(window)
            } else {
                create(window)?.let { panels[window.id] = it }
            }
        }
    }

    private fun create(window: MrWindow): WindowPanelRecord? {
        return runCatching {
            val context = materialLoader.context
            val windowManager = ViewNode.WindowManager(context)
            val panelView = WindowPanelView(context).apply {
                onMinimize = { onMinimize?.invoke(window.id) }
                onClose = { onClose?.invoke(window.id) }
                onTitle = { title -> onTitle?.invoke(window.id, title) }
            }
            val node = ViewNode(
                engine = engine,
                windowManager = windowManager,
                materialLoader = materialLoader,
                view = panelView,
                // `unlit = true`: o conteúdo é uma UI auto-iluminada, como uma tela de
                // verdade. Deixar a luz da cena agir sobre ela faz o painel escurecer quando
                // o usuário vira para longe da luz principal — e um site não deve mudar de
                // brilho conforme a iluminação do ambiente.
                unlit = true,
            )

            // 1. Janela off-screen existe e o owner está anexado.
            windowManager.resume(ownerView)
            // 2. Entrar na cena → `onAddedToScene` → `windowManager.addView(layout)`.
            root.addChildNode(node)

            val record = WindowPanelRecord(
                id = window.id,
                node = node,
                windowManager = windowManager,
                panelView = panelView,
                touch = SyntheticTouch(panelView.webView),
            )
            record.apply(window)
            panelView.load(window.url)
            record
        }.onFailure {
            // Falha aqui não pode derrubar a sessão inteira: melhor uma janela a menos e um
            // log do que a cena 3D inteira fora do ar.
            android.util.Log.e(TAG, "falha ao criar painel ${window.id}", it)
        }.getOrNull()
    }

    /** Libera tudo. Chamado quando a cena sai de composição. */
    fun destroyAll() {
        panels.values.forEach { it.destroy(root) }
        panels.clear()
    }

    /** Callbacks de ação, ligados pelo dono da cena. */
    var onMinimize: ((String) -> Unit)? = null
    var onClose: ((String) -> Unit)? = null
    var onTitle: ((String, String) -> Unit)? = null

    private companion object {
        const val TAG = "ZyvoxWindowPanel"
    }
}
