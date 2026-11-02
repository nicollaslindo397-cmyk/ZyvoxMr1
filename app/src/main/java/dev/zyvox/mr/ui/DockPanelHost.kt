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

    // Declarados ANTES de `node` e do `init` de propósito: em Kotlin os inicializadores de
    // propriedade e os blocos `init` rodam na ordem em que aparecem no corpo da classe. O `init`
    // chama `applyScale`, que chama `layout()`, que lê estes dois campos — se estivessem
    // declarados depois, `layout()` leria zeros e o dock nasceria com `pxPerUnits` infinito.
    private var currentScale = 0f
    private var visibleWidthM = 0f

    val node: ViewNode = ViewNode(
        engine = engine,
        windowManager = windowManager,
        materialLoader = materialLoader,
        view = this.view,
        unlit = true,
    )

    /**
     * Largura **efetiva** do dock no mundo, já com a escala do usuário aplicada.
     *
     * É derivada, não armazenada: se fosse um campo escrito por [layout], haveria duas fontes de
     * verdade que poderiam divergir em silêncio, e divergência aqui significa o toque ser
     * registrado num botão enquanto a ação disparada é a de outro.
     *
     * Tudo que precisa saber onde o dock termina — teste de contenção, mapeamento de toque,
     * divisão dos botões no [dev.zyvox.mr.spatial.SpatialInputRouter] — tem de ler daqui, nunca
     * de [MrTokens.DOCK_WIDTH_M].
     */
    val worldWidthM: Float
        get() = (if (visibleWidthM > 0f) visibleWidthM else MrTokens.DOCK_WIDTH_M) * currentScale

    /** Altura efetiva no mundo. Derivada da largura pela proporção fixa da view em pixel. */
    val worldHeightM: Float
        get() = worldWidthM * DockPanelView.DOCK_HEIGHT_PX / DockPanelView.DOCK_WIDTH_PX

    init {
        // Ordem obrigatória: janela off-screen antes de entrar na cena. Ver
        // [WindowPanelHost] para o porquê detalhado.
        windowManager.resume(ownerView)
        root.addChildNode(node)
        applyScale(1.0f)
        node.position = Position(0f, MrTokens.DOCK_Y_M, -MrTokens.DOCK_DEPTH_M)
    }

    /**
     * Largura do campo de visão na profundidade do dock, medida pelo dono da cena.
     *
     * Ver [applyScale] para por que o tamanho é uma fração disto e não um número fixo em metros.
     */
    fun applyVisibleWidth(widthM: Float) {
        if (!widthM.isFinite() || widthM <= 0f) return
        if (widthM == visibleWidthM) return
        visibleWidthM = widthM
        layout()
    }

    /**
     * Redimensiona o dock. Idempotente e barato: só reescreve `pxPerUnits` quando algo mudou de
     * fato, porque esse setter remeide a superfície do `ViewNode`.
     *
     * Escalar por `pxPerUnits` (e não por `node.scale`) é o que mantém o conteúdo nítido:
     * `pxPerUnits` muda quantos metros a view de pixel ocupa, então o texto é rasterizado de
     * novo no tamanho novo. Multiplicar `scale` ampliaria a textura já rasterizada — o dock
     * cresceria borrado, que é exatamente o defeito que um redimensionar precisa não ter.
     * É o mesmo contrato das janelas WebView (ver [WindowMetrics]).
     */
    fun applyScale(scale: Float) {
        val clamped = scale.coerceIn(MrTokens.DOCK_SCALE_MIN, MrTokens.DOCK_SCALE_MAX)
        if (clamped == currentScale) return
        currentScale = clamped
        layout()
    }

    /** Aplica o tamanho efetivo ao `ViewNode`. Único lugar que escreve `pxPerUnits`. */
    private fun layout() {
        val width = worldWidthM
        if (width <= 0f) return
        node.pxPerUnits = DockPanelView.DOCK_WIDTH_PX / width
    }

    fun setVisible(visible: Boolean) {
        node.isVisible = visible
    }

    /**
     * Converte posição de mundo em pixel do dock, para o injetor de toque.
     *
     * O dock é centrado em `x = 0`, então o mapeamento é linear. `y` é invertido porque no
     * mundo +y é para cima e em pixel de view é para baixo.
     *
     * Usa [worldWidthM] (a largura **efetiva**, com a escala aplicada) e não
     * [MrTokens.DOCK_WIDTH_M]. Com o dock redimensionável, converter pela base faria o toque
     * cair num pixel que não corresponde ao botão desenhado — o cursor estaria sobre "YouTube"
     * e a ação disparada seria a de outro botão.
     */
    fun worldToPixel(worldX: Float, worldY: Float): Pair<Float, Float> {
        val metersPerPx = worldWidthM / DockPanelView.DOCK_WIDTH_PX
        val px = (worldX / metersPerPx) + DockPanelView.DOCK_WIDTH_PX / 2f
        val py = (MrTokens.DOCK_Y_M - worldY) / metersPerPx
        return px to py
    }

    /** O ponto (em metros) está dentro do quad do dock? Usa as dimensões efetivas. */
    fun contains(worldX: Float, worldY: Float): Boolean {
        val halfW = worldWidthM / 2f
        val halfH = worldHeightM / 2f
        return worldX in -halfW..halfW &&
            worldY in (MrTokens.DOCK_Y_M - halfH)..(MrTokens.DOCK_Y_M + halfH)
    }

    fun destroy() {
        runCatching { root.removeChildNode(node) }
        runCatching { windowManager.pause() }
        runCatching { windowManager.destroy() }
    }

    // Não há companion object aqui. Ele existia só para um `private val DOCK_WIDTH_M` espelhado
    // que nenhuma linha do arquivo lia — e que, agora que o dock é redimensionável, seria uma
    // cópia permanentemente errada da largura efetiva. Um espelho morto de uma constante que
    // deixou de ser constante é o tipo de coisa que alguém lê e confia.
}
