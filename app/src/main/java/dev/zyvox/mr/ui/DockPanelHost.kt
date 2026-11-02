package dev.zyvox.mr.ui

import android.view.View
import com.google.android.filament.Engine
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import dev.zyvox.mr.dock.DockPanelView
import dev.zyvox.mr.ui.MrTokens

/**
 * O dock flutuante como painel 3D, com seu próprio `WindowManager`.
 *
 * ### Por que um manager só para ele
 * Um `ViewNode.WindowManager` mede o conteúdo com `AT_MOST(display)` e se dimensiona pelo
 * **maior** filho. Se o dock (1120×190 px) dividisse manager com as janelas (1280×800 px),
 * o dock seria re-medido para 1280×800 e apareceria como uma faixa larga com um vazio enorme
 * embaixo dos botões. Manager separado custa uma janela de sistema a mais e resolve o
 * problema de vez.
 *
 * ### Posição
 * Fica em [MrTokens.DOCK_Y_M] (abaixo do centro do campo de visão) e
 * [MrTokens.DOCK_DEPTH_M] (um pouco mais perto que as janelas). Mais perto = projeta menor
 * na tela mas lê como "à frente", que é onde um dock deve estar para não competir com o
 * conteúdo.
 */
class DockPanelHost(
    engine: Engine,
    materialLoader: MaterialLoader,
    private val root: Node,
    ownerView: View,
    private val onAction: (DockPanelView.Action) -> Unit,
) {
    val view = DockPanelView(materialLoader.context).apply {
        onAction = { action -> this@DockPanelHost.onAction(action) }
    }

    // O dock NÃO usa `SyntheticTouch`. As células têm `setOnClickListener`, então um stream
    // sintético de MotionEvent já dispararia a ação — e o `SpatialInputRouter` invoca a ação
    // diretamente no fim do gesto. Fazer os dois dispararia cada botão duas vezes. O
    // feedback de "pressionado" vem de `setHighlighted`, que é o suficiente e é determinístico.

    private val windowManager = ViewNode.WindowManager(materialLoader.context)

    val node: ViewNode = ViewNode(
        engine = engine,
        windowManager = windowManager,
        materialLoader = materialLoader,
        view = this.view,
        unlit = true,
    )

    init {
        // Ordem obrigatória: janela off-screen antes de entrar na cena. Ver
        // [WindowPanelHost] para o porquê detalhado.
        windowManager.resume(ownerView)
        root.addChildNode(node)
        node.pxPerUnits = DockPanelView.DOCK_WIDTH_PX / MrTokens.DOCK_WIDTH_M
        node.position = Position(0f, MrTokens.DOCK_Y_M, -MrTokens.DOCK_DEPTH_M)
    }

    fun setVisible(visible: Boolean) {
        node.isVisible = visible
    }

    /**
     * Converte posição de mundo em pixel do dock, para o injetor de toque.
     *
     * O dock é centrado em `x = 0` e tem largura/altura fixas no mundo, então o mapeamento é
     * linear. `y` é invertido porque no mundo +y é para cima e em pixel de view é para baixo.
     */
    fun worldToPixel(worldX: Float, worldY: Float): Pair<Float, Float> {
        val metersPerPx = MrTokens.DOCK_WIDTH_M / DockPanelView.DOCK_WIDTH_PX
        val px = (worldX / metersPerPx) + DockPanelView.DOCK_WIDTH_PX / 2f
        val py = (MrTokens.DOCK_Y_M - worldY) / metersPerPx
        return px to py
    }

    /** O ponto (em metros) está dentro do quad do dock? */
    fun contains(worldX: Float, worldY: Float): Boolean {
        val metersPerPx = MrTokens.DOCK_WIDTH_M / DockPanelView.DOCK_WIDTH_PX
        val heightM = DockPanelView.DOCK_HEIGHT_PX * metersPerPx
        val halfW = MrTokens.DOCK_WIDTH_M / 2f
        return worldX in -halfW..halfW &&
            worldY in (MrTokens.DOCK_Y_M - heightM / 2f)..(MrTokens.DOCK_Y_M + heightM / 2f)
    }

    fun destroy() {
        runCatching { root.removeChildNode(node) }
        runCatching { windowManager.pause() }
        runCatching { windowManager.destroy() }
    }

    companion object {
        /** Largura do dock no mundo, espelhada aqui para evitar import circular no KDoc. */
        private val DOCK_WIDTH_M = MrTokens.DOCK_WIDTH_M
    }
}
