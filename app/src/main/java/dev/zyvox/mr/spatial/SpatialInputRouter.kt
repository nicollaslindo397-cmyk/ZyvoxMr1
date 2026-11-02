package dev.zyvox.mr.spatial

import android.util.Log
import dev.zyvox.mr.dock.DockPanelView
import dev.zyvox.mr.hand.TrackedHand
import dev.zyvox.mr.ui.CursorPanelHost
import dev.zyvox.mr.ui.DockPanelHost
import dev.zyvox.mr.ui.WindowPanelHost
import dev.zyvox.mr.window.MrWindow
import dev.zyvox.mr.window.WindowController
import dev.zyvox.mr.window.WindowMetrics
import dev.zyvox.mr.window.WindowPanelRecord
import kotlin.math.roundToInt

/**
 * Roteia o gesto da mão para o alvo certo, uma vez por frame renderizado.
 *
 * **Contrato de thread:** [onFrame] deve rodar na main thread. O `SceneView.onFrame` roda lá,
 * e isto é obrigatório porque daqui saem `View.dispatchTouchEvent` e chamadas de `WebView`,
 * que não são thread-safe.
 *
 * ### A regra de precedência
 * O dock é testado **antes** das janelas. Não é arbitrário: o dock está a
 * [dev.zyvox.mr.ui.MrTokens.DOCK_DEPTH_M] e as janelas a
 * [dev.zyvox.mr.window.MrWindow.DEFAULT_DEPTH], ou seja, o dock está *mais perto* do
 * usuário. Num plano de interação único, o objeto mais próximo é o que o usuário vê na frente
 * e é o que ele espera acertar. Testar as janelas primeiro faria o dock ficar "morto" numa
 * faixa onde uma janela passa por trás dele.
 *
 * ### Por que o alvo é travado no início do gesto
 * [activeTarget] é decidido no `DOWN` e não é reavaliado até o gesto terminar. Sem isso,
 * arrastar uma janela e passar o cursor por cima do dock no meio do movimento transferiria o
 * gesto para o dock — a janela soltaria e um botão clicaria sozinho. É o mesmo motivo pelo
 * qual o Android trava o touch target no `ACTION_DOWN`.
 */
class SpatialInputRouter(
    private val cursorHost: CursorPanelHost,
    private val dockHost: DockPanelHost,
    private val windowHost: WindowPanelHost,
    private val windowController: WindowController,
) {

    private enum class Target { NONE, DOCK, WINDOW }

    private var activeTarget = Target.NONE
    private var activePanelId: String? = null
    private var activeDockAction: DockPanelView.Action? = null

    /**
     * @param cursorX/Y/Z posição do cursor no mundo, em metros.
     * @param hand a mão que controla o cursor, ou `null` se nenhuma está visível.
     * @param windows estado atual das janelas (para achar o painel pelo id).
     */
    fun onFrame(
        cursorX: Float,
        cursorY: Float,
        cursorZ: Float,
        cursorValid: Boolean,
        hand: TrackedHand?,
        windows: List<MrWindow>,
    ) {
        val pinching = hand?.isPinching == true

        // Registra ANTES de qualquer decisão: `releaseGesture()` precisa saber onde o gesto
        // terminou para escolher entre `up` (confirma) e `cancel` (desiste). Esquecer isto
        // faz o dock clicar na posição do gesto *anterior*.
        recordCursor(cursorX, cursorY)

        cursorHost.update(
            x = cursorX,
            y = cursorY,
            z = cursorZ,
            visible = cursorValid,
            pinching = pinching,
        )

        // ── Sem mão, ou gesto solto: encerra tudo ─────────────────────────────────────────
        if (hand == null || !pinching) {
            if (activeTarget != Target.NONE) releaseGesture()
            // O controller cuida do hover (destaque) mesmo sem pinch.
            windowController.onHand(hand, cursorX, cursorY, cursorZ)
            if (hand == null) {
                dockHost.view.setHighlighted(null, false)
                windowController.clearHover()
            }
            return
        }

        // ── Gesto começando agora: escolhe o alvo ─────────────────────────────────────────
        if (activeTarget == Target.NONE) {
            beginGesture(cursorX, cursorY)
            // Se o alvo não for o dock, o controller decide entre mover/redimensionar/tocar.
            if (activeTarget != Target.DOCK) {
                windowController.onHand(hand, cursorX, cursorY, cursorZ)
                if (windowController.mode == WindowController.Mode.TOUCH_CONTENT) {
                    activeTarget = Target.WINDOW
                    activePanelId = windowController.activeWindowId
                    panelOf(activePanelId)?.let { panel ->
                        val (px, py) = webPixel(panel, cursorX, cursorY)
                        panel.touch.down(px, py)
                    }
                }
            }
            return
        }

        // ── Gesto em andamento: continua no alvo travado ──────────────────────────────────
        when (activeTarget) {
            Target.DOCK -> {
                // Destaque segue o cursor dentro do dock, mas o clique só acontece no UP —
                // igual a um botão de verdade, que permite arrastar para fora e desistir.
                activeDockAction = if (dockHost.contains(cursorX, cursorY)) {
                    actionAt(cursorX)
                } else {
                    null
                }
                dockHost.view.setHighlighted(activeDockAction, true)
            }

            Target.WINDOW -> {
                windowController.onHand(hand, cursorX, cursorY, cursorZ)
                val panel = panelOf(activePanelId)
                if (panel == null) {
                    // A janela foi fechada no meio do gesto (pelo outro botão do dock, ou por
                    // um toque de dedo). Cancela em vez de deixar o controller pendurado.
                    releaseGesture()
                } else if (windowController.mode == WindowController.Mode.TOUCH_CONTENT) {
                    val (px, py) = webPixel(panel, cursorX, cursorY)
                    panel.touch.move(px, py)
                }
            }

            Target.NONE -> Unit
        }
    }

    private fun beginGesture(x: Float, y: Float) {
        if (dockHost.contains(x, y)) {
            activeTarget = Target.DOCK
            activeDockAction = actionAt(x)
            // O feedback de "pressionado" vem do destaque, não de um MotionEvent sintético.
            // Despachar DOWN aqui E invocar a ação diretamente no UP dispararia duas vezes:
            // a célula tem `setOnClickListener`, então o stream sintético já acionaria a
            // ação. Escolhemos o caminho direto (determinístico) e abrimos mão do ripple.
            dockHost.view.setHighlighted(activeDockAction, true)
            return
        }

        // Fora do dock: deixa o controller classificar (título → mover, alça → redimensionar,
        // corpo → tocar no WebView).
        activeTarget = Target.NONE
        activePanelId = null
        activeDockAction = null
        dockHost.view.setHighlighted(null, false)
    }

    private fun releaseGesture() {
        when (activeTarget) {
            Target.DOCK -> {
                // Confirma só se o cursor ainda está sobre o dock no momento de soltar.
                // Arrastar para fora e soltar cancela — mesmo contrato de um botão de
                // verdade, que permite desistir de um clique arrastando o dedo para longe.
                val action = activeDockAction
                if (action != null && dockHost.contains(lastX, lastY)) {
                    onDockAction?.invoke(action)
                }
                dockHost.view.setHighlighted(null, false)
            }

            Target.WINDOW -> {
                panelOf(activePanelId)?.let { panel ->
                    val (px, py) = webPixel(panel, lastX, lastY)
                    panel.touch.up(px, py)
                }
                windowController.endGesture()
            }

            Target.NONE -> {
                windowController.endGesture()
            }
        }
        activeTarget = Target.NONE
        activePanelId = null
        activeDockAction = null
    }

    /** Último cursor conhecido, para o `up`/`cancel` saber onde o gesto terminou. */
    private var lastX = 0f
    private var lastY = 0f

    /** Atualiza o último cursor. Chamado por [onFrame] antes de qualquer decisão. */
    fun recordCursor(x: Float, y: Float) {
        lastX = x
        lastY = y
    }

    /** Ação do dock disparada pelo gesto da mão. Ligado pelo dono da cena. */
    var onDockAction: ((DockPanelView.Action) -> Unit)? = null

    private fun panelOf(id: String?): WindowPanelRecord? =
        id?.let { windowHost.panel(it) }

    /**
     * Pixel **dentro do WebView** correspondente a um ponto do mundo sobre esta janela.
     *
     * Três transformações encadeadas, e cada uma tem uma pegadinha:
     *
     * 1. mundo → offset desde o centro da janela (o `ViewNode` posiciona pelo centro);
     * 2. offset em metros → pixel, usando o `pxPerUnits` *desta* janela (que muda quando ela
     *    é redimensionada — usar o valor padrão aqui desalinha o toque nas janelas
     *    redimensionadas);
     * 3. `y` invertido (mundo +y é para cima, pixel +y é para baixo) e descontada a altura da
     *    barra de título, que não faz parte do WebView.
     */
    private fun webPixel(panel: WindowPanelRecord, worldX: Float, worldY: Float): Pair<Float, Float> {
        val window = panel.node.let { node ->
            // O painel não guarda o MrWindow; recuperamos a posição do nó, que é a fonte da
            // verdade do que está sendo desenhado agora.
            node.worldPosition
        }
        val widthPx = WindowMetrics.CONTENT_WIDTH_PX.toFloat()
        val heightPx = WindowMetrics.CONTENT_HEIGHT_PX.toFloat()
        val pxPerUnits = panel.node.pxPerUnits
        if (pxPerUnits <= 0f) return 0f to 0f

        val widthM = widthPx / pxPerUnits
        val heightM = heightPx / pxPerUnits

        val u = ((worldX - window.x) / widthM + 0.5f).coerceIn(0f, 1f)
        // +y do mundo é para cima; +y do pixel é para baixo → inverte.
        val v = (0.5f - (worldY - window.y) / heightM).coerceIn(0f, 1f)

        val hostX = u * widthPx
        val hostY = v * heightPx
        return WindowMetrics.hostToWebPx(hostX, hostY)
    }

    /**
     * Qual botão do dock o cursor cobre, a partir do x no mundo.
     *
     * Os botões são células de peso igual num `LinearLayout` horizontal, então a divisão é
     * uniforme: índice = fração da largura × número de itens. Derivar do layout em vez de
     * hardcodear o índice é o que mantém isto correto se um item for adicionado ou removido.
     */
    private fun actionAt(worldX: Float): DockPanelView.Action? {
        val actions = listOf(
            DockPanelView.Action.BROWSER,
            DockPanelView.Action.NEW_WINDOW,
            DockPanelView.Action.RECENTER,
            DockPanelView.Action.CLOSE_ALL,
            DockPanelView.Action.TOGGLE_DOCK,
        )
        val half = dev.zyvox.mr.ui.MrTokens.DOCK_WIDTH_M / 2f
        val fraction = ((worldX + half) / (half * 2f)).coerceIn(0f, 0.9999f)
        val index = (fraction * actions.size).roundToInt().coerceIn(0, actions.size - 1)
        return actions.getOrNull(index)
    }

    /** Libera qualquer gesto em andamento. Chamado ao desfazer a cena. */
    fun reset() {
        if (activeTarget != Target.NONE) releaseGesture()
        windowController.endGesture()
        windowController.clearHover()
    }

    companion object {
        private const val TAG = "ZyvoxInputRouter"

        /** Log de diagnóstico: liga com `adb shell setprop log.tag.ZyvoxInputRouter VERBOSE`. */
        fun debugEnabled(): Boolean = Log.isLoggable(TAG, Log.VERBOSE)
    }
}
