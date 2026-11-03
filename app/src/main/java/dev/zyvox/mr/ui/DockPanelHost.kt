package dev.zyvox.mr.ui

import android.view.View
import com.google.android.filament.Engine
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import dev.romainguy.kotlin.math.Quaternion
import dev.zyvox.mr.dock.DockPanelView
import dev.zyvox.mr.settings.InterfaceLayout
import dev.zyvox.mr.spatial.InterfaceTransform
import dev.zyvox.mr.spatial.SpatialPlacement

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
 * Vem das configurações do usuário, via [applyPlacement]: distância, altura, deslocamento
 * horizontal, inclinação e escala são todos ajustáveis em tempo real. Nada aqui define onde o dock
 * fica — quem define é `dev.zyvox.mr.settings.InterfaceLayout`, e o padrão coloca o dock no centro
 * exato do campo de visão, a 0,80 m.
 *
 * Centrado não é preferência estética: num aparelho segurado à frente do rosto, qualquer
 * deslocamento vertical vira um ângulo que o braço tem de sustentar. O dock do Quest 3 fica abaixo
 * do centro porque o headset sabe para onde você olha e o acompanha; aqui a câmera é fixa no
 * aparelho. E como a distância passou a ser configurável, "abaixo do centro" virou um ajuste que o
 * usuário faz se quiser, não uma decisão embutida no código.
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
    // chama `applyScale`, que chama `layout()`, que lê estes campos — se estivessem declarados
    // depois, `layout()` leria zeros e o dock nasceria com `pxPerUnits` infinito.
    private var currentScale = 0f
    private var visibleWidthM = 0f

    /**
     * Centro vertical efetivo do dock, em metros de mundo.
     *
     * Era `MrTokens.DOCK_Y_M`, uma constante. Com a altura ajustável pelo usuário, continuar
     * lendo a constante faria o teste de contenção e o mapeamento de toque usarem uma posição que
     * não é mais a do dock — o cursor estaria sobre um botão e a ação disparada seria a de outro,
     * ou de nenhum. Então a posição aplicada é guardada aqui e é ela que os dois métodos leem.
     */
    private var centerYM = 0f
    private var centerXM = 0f

    /** Ver [worldDepthM]. Guardado junto com os centros porque vem do mesmo `applyPlacement`. */
    private var centerZM = -InterfaceLayout.DEFAULT_DISTANCE_M

    /**
     * Cosseno da inclinação vigente, derivado do quaternion em vez de recalculado com trigonometria.
     *
     * Para uma rotação θ em torno de X, o quaternion é `(sin(θ/2), 0, 0, cos(θ/2))`, então
     * `cos(θ) = 2·w² − 1`. Usar o próprio quaternion garante que o valor usado no teste de toque
     * venha da **mesma** rotação que foi aplicada ao nó — recalcular a partir de `tiltDeg` abriria
     * espaço para os dois divergirem se um dia a rotação passar a vir de outro lugar.
     */
    private var cosTilt = 1f

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

    /**
     * Profundidade efetiva do dock, em metros POSITIVOS à frente da câmera.
     *
     * O nó guarda `-distance` em Z (a câmera olha para -Z); quem compara profundidades quer o
     * número positivo, então o sinal é desfeita aqui, uma vez, em vez de em cada comparação.
     *
     * Existe porque a distância passou a ser configurável de 0,25 a 3,0 m, e o
     * [dev.zyvox.mr.spatial.SpatialInputRouter] decidia a precedência assumindo que o dock está
     * sempre mais perto que as janelas. Com o dock a 1,6 m e as janelas a 0,55 m essa premissa é
     * falsa, e testar o dock primeiro faria ele roubar o toque de uma janela que está na frente.
     */
    val worldDepthM: Float get() = -centerZM

    init {
        // Ordem obrigatória: janela off-screen antes de entrar na cena. Ver
        // [WindowPanelHost] para o porquê detalhado.
        windowManager.resume(ownerView)
        root.addChildNode(node)
        // Posição inicial vem do layout padrão, não de constantes próprias: se houvesse um
        // segundo lugar definindo onde o dock nasce, o primeiro quadro poderia divergir do que as
        // configurações dizem, e a interface "pularia" no primeiro ajuste.
        applyPlacement(InterfaceTransform.placementFor(InterfaceLayout()))
    }

    /**
     * Aplica posição, inclinação e escala de uma vez. É o que faz os ajustes valerem em tempo
     * real: quem chama passa o [SpatialPlacement] derivado das configurações a cada quadro, e este
     * método é idempotente (cada setter só reescreve quando o valor mudou de fato).
     *
     * A escala entra por [applyScale] e não por `node.scale`, ver o comentário lá.
     */
    fun applyPlacement(placement: SpatialPlacement) {
        val x = if (placement.xM.isFinite()) placement.xM else 0f
        val y = if (placement.yM.isFinite()) placement.yM else 0f
        val z = if (placement.zM.isFinite()) placement.zM else -InterfaceLayout.DEFAULT_DISTANCE_M
        node.position = Position(x, y, z)
        // `Quaternion(x, y, z, w)` é a ordem do kotlin-math. Trocar w de lugar aqui produz uma
        // rotação errada sem erro nenhum — a mesma armadilha que `HeadPoseTracker` documenta.
        node.quaternion = Quaternion(
            placement.quaternionX,
            placement.quaternionY,
            placement.quaternionZ,
            placement.quaternionW,
        )
        centerXM = x
        centerYM = y
        centerZM = z
        val w = placement.quaternionW
        // `coerceAtLeast(0.1f)`: com |θ| ≤ 45° o cosseno é ≥ 0.707, então o piso só existe para
        // impedir divisão por zero caso um quaternion degenerado chegue aqui. Sem ele, uma
        // inclinação de 90° produziria `py` infinito e o destaque sumiria.
        cosTilt = (2f * w * w - 1f).coerceIn(0.1f, 1f)
        applyScale(placement.scale)
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
        // Os limites são os de `InterfaceLayout`, os mesmos que a sanitização das configurações
        // aplica. Ter uma segunda faixa aqui seria duas fontes de verdade sobre o tamanho
        // permitido, e elas divergem justamente quando alguém ajusta uma delas.
        val clamped = if (!scale.isFinite()) 1f else scale.coerceIn(InterfaceLayout.MIN_SCALE, InterfaceLayout.MAX_SCALE)
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
        if (metersPerPx <= 0f) return 0f to 0f
        val px = ((worldX - centerXM) / metersPerPx) + DockPanelView.DOCK_WIDTH_PX / 2f
        // A inclinação comprime a altura projetada: um painel inclinado θ ocupa `h·cos(θ)` na
        // vertical. Dividir por cosTilt desfaz essa compressão para o pixel da view, senão o
        // destaque ficaria deslocado do botão assim que o usuário inclinasse a interface.
        val py = ((centerYM - worldY) / cosTilt) / metersPerPx
        return px to py
    }

    /** O ponto (em metros) está dentro do quad do dock? Usa as dimensões efetivas. */
    fun contains(worldX: Float, worldY: Float): Boolean {
        val halfW = worldWidthM / 2f
        // Ver `worldToPixel` para o porquê do cosTilt. Sem ele, com a interface inclinada a área
        // aceita seria maior que a área desenhada, e o usuário tocaria "fora do dock" e ainda
        // assim dispararia um botão.
        val halfH = worldHeightM / 2f * cosTilt
        return worldX in (centerXM - halfW)..(centerXM + halfW) &&
            worldY in (centerYM - halfH)..(centerYM + halfH)
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
