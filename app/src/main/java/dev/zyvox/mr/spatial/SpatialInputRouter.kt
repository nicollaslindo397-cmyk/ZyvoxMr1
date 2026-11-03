package dev.zyvox.mr.spatial

import android.os.SystemClock
import android.util.Log
import dev.zyvox.mr.dock.DockPanelView
import dev.zyvox.mr.hand.HandFrame
import dev.zyvox.mr.hand.TrackedHand
import dev.zyvox.mr.ui.CursorPanelHost
import dev.zyvox.mr.ui.DockPanelHost
import dev.zyvox.mr.ui.WindowPanelHost
// `WindowPanelRecord` mora em `dev.zyvox.mr.ui` (é declarada em WindowPanelHost.kt), NÃO em
// `dev.zyvox.mr.window`. Um import errado aqui custa muito mais que uma linha: com o tipo não
// resolvido, tudo que deriva dele degrada para `Any`, e o compilador passa a reportar cascata —
// `Unresolved reference 'touch'`, `'node'`, e `let` "inapplicable because of a receiver type
// mismatch" — em pontos do arquivo que estão corretos. Foram ~19 erros vindos desta linha.
import dev.zyvox.mr.ui.WindowPanelRecord
import dev.zyvox.mr.window.MrWindow
import dev.zyvox.mr.window.WindowController
import dev.zyvox.mr.window.WindowMetrics
import kotlin.math.roundToInt

/**
 * Roteia o gesto da mão para o alvo certo, uma vez por frame renderizado.
 *
 * **Contrato de thread:** [onFrame] deve rodar na main thread. O `SceneView.onFrame` roda lá,
 * e isto é obrigatório porque daqui saem `View.dispatchTouchEvent` e chamadas de `WebView`,
 * que não são thread-safe.
 *
 * ### A regra de precedência
 * Quem está **mais perto** do usuário é testado primeiro. Num plano de interação único, o objeto
 * mais próximo é o que o usuário vê na frente e é o que ele espera acertar.
 *
 * Isto costumava ser uma ordem fixa (dock antes das janelas) justificada por o dock estar a 0,40 m
 * e as janelas a 0,55 m. A distância do dock passou a ser ajustável pelo usuário, de 0,25 a 3,0 m,
 * então a ordem não pode mais ser fixa: com o dock a 1,6 m e uma janela a 0,55 m, testar o dock
 * primeiro faria ele roubar o clique de uma janela que está visivelmente na frente. Por isso a
 * comparação agora é feita entre [dev.zyvox.mr.ui.DockPanelHost.worldDepthM] e a profundidade da
 * janela mais próxima não minimizada, no quadro do gesto.
 *
 * A regra vale apenas para a sobreposição: se só um dos dois contém o ponto, é ele que recebe o
 * gesto, independente da ordem. O dock não deixa de ser alcançável por estar atrás.
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
     * @param frame o quadro completo. Os gestos globais olham a mão DIREITA nele, que pode não
     *   ser a mesma que controla o cursor — ver [updateGlobalGestures].
     * @param windows estado atual das janelas (para achar o painel pelo id).
     */
    fun onFrame(
        cursorX: Float,
        cursorY: Float,
        cursorZ: Float,
        cursorValid: Boolean,
        hand: TrackedHand?,
        frame: HandFrame,
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

        // Antes dos early-returns abaixo, senão os gestos globais parariam de ser avaliados
        // justamente quando não há mão no cursor — que é um dos casos em que precisam responder.
        updateGlobalGestures(frame)

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
            // Ver a regra de precedência no KDoc da classe: a ordem depende de quem está mais
            // perto, e isso agora é decidido por quadro porque a distância do dock é configurável.
            val dockFirst = dockHost.worldDepthM <= nearestWindowDepthM(windows)
            val dockHit = dockFirst && tryDock(cursorX, cursorY)

            if (!dockHit) {
                clearDock()
                // O controller decide entre mover/redimensionar/tocar no conteúdo.
                windowController.onHand(hand, cursorX, cursorY, cursorZ)
                val windowHit = windowController.mode != WindowController.Mode.IDLE
                if (windowController.mode == WindowController.Mode.TOUCH_CONTENT) {
                    activeTarget = Target.WINDOW
                    activePanelId = windowController.activeWindowId
                    panelOf(activePanelId)?.let { panel ->
                        val (px, py) = webPixel(panel, cursorX, cursorY)
                        panel.touch.down(px, py)
                    }
                }
                // Nenhuma janela pegou o gesto: o dock ainda pode estar debaixo do cursor, mesmo
                // estando mais longe. Não testá-lo aqui deixaria o dock inalcançável exatamente
                // quando o usuário o afastou — que é o ajuste que a seção de interface pede.
                if (!windowHit && !dockFirst) tryDock(cursorX, cursorY)
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

    /**
     * Tenta travar o gesto no dock.
     *
     * @return `true` se o ponto está dentro do dock, e então o alvo fica travado nele até o fim do
     *   gesto. `false` não muda estado nenhum, para poder ser chamado depois de o controller já
     *   ter olhado as janelas.
     */
    private fun tryDock(x: Float, y: Float): Boolean {
        if (!dockHost.contains(x, y)) return false
        activeTarget = Target.DOCK
        activeDockAction = actionAt(x)
        // O feedback de "pressionado" vem do destaque, não de um MotionEvent sintético.
        // Despachar DOWN aqui E invocar a ação diretamente no UP dispararia duas vezes:
        // a célula tem `setOnClickListener`, então o stream sintético já acionaria a
        // ação. Escolhemos o caminho direto (determinístico) e abrimos mão do ripple.
        dockHost.view.setHighlighted(activeDockAction, true)
        return true
    }

    /**
     * Limpa o estado do dock antes de oferecer o gesto às janelas.
     *
     * Sem isto, um destaque de um quadro anterior ficaria aceso sobre uma célula que não vai
     * receber o clique — o usuário vê um botão pressionado que não faz nada.
     */
    private fun clearDock() {
        activeTarget = Target.NONE
        activePanelId = null
        activeDockAction = null
        dockHost.view.setHighlighted(null, false)
    }

    /**
     * Profundidade da janela mais próxima que pode receber o gesto.
     *
     * Janelas minimizadas ficam fora: elas não estão na tela, então não podem bloquear o dock.
     * Sem janelas, devolve [Float.MAX_VALUE] — o dock é o único alvo e portanto tem precedência.
     */
    private fun nearestWindowDepthM(windows: List<MrWindow>): Float {
        var nearest = Float.MAX_VALUE
        for (window in windows) {
            if (window.minimized) continue
            if (window.depthMeters < nearest) nearest = window.depthMeters
        }
        return nearest
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

    /** Mostra/oculta a interface. Disparado por pinch da mão direita no espaço vazio. */
    var onToggleDock: (() -> Unit)? = null

    /** Alterna o fundo entre a câmera e o ambiente virtual. Disparado por mão direita fechada. */
    var onToggleBackground: (() -> Unit)? = null

    // ── Gestos globais da mão direita ────────────────────────────────────────────────────
    //
    // Por que a mão direita e não "a mão do cursor": o cursor é a mão que o usuário aponta, e
    // se os dois papéis fossem da mesma mão não haveria como distinguir "quero clicar nisso" de
    // "quero esconder a interface". Separar em mão esquerda = ponteiro, mão direita = controle é
    // o que torna os dois gestos possíveis sem ambiguidade.
    //
    // Por que `isRightHand` vem da posição no quadro e não do rótulo do MediaPipe: ver a KDoc de
    // [TrackedHand]. Em resumo, o rótulo assume imagem espelhada e oscila quando a mão vira.

    private var rightPinchWasActive = false
    private var rightFistWasActive = false
    private var lastDockToggleNs = 0L
    private var lastBackgroundToggleNs = 0L

    /**
     * Pinch da mão direita **no espaço vazio** esconde/mostra a interface; mão direita fechada
     * troca o fundo.
     *
     * ### Por que "no espaço vazio"
     * Pinch já é o gesto de selecionar. Se a mão direita fechasse a interface a cada pinch, não
     * seria mais possível usar a mão direita para tocar num botão do dock ou numa janela.
     * Restringir ao caso em que o pinch não acertou alvo nenhum resolve o conflito sem inventar
     * gesto: `Target.NONE` era literalmente `Unit` — um pinch no vazio não fazia nada antes.
     *
     * ### Por que edge-triggered com cooldown
     * `onFrame` roda ~60×/s e o estado do gesto dura vários quadros. Disparar por nível em vez
     * de por borda esconderia e reexibiria a interface dezenas de vezes no mesmo gesto. E o
     * cooldown cobre um caso que a borda sozinha não cobre: se a mão some do quadro por um
     * instante (oclusão, inferência que perdeu o frame), `right` vira `null` e o estado anterior
     * reseta para `false` — sem cooldown, reaparecer ainda pinçando contaria como uma segunda
     * borda de subida e desfaria a ação.
     */
    private fun updateGlobalGestures(frame: HandFrame) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val right = frame.hands.firstOrNull { it.isRightHand }

        val fistActive = right?.isFist == true
        if (fistActive && !rightFistWasActive &&
            nowNs - lastBackgroundToggleNs > GESTURE_COOLDOWN_NS
        ) {
            lastBackgroundToggleNs = nowNs
            onToggleBackground?.invoke()
        }
        rightFistWasActive = fistActive

        // `activeTarget == Target.NONE` é o "não acertou nada": nem dock, nem título, nem alça,
        // nem corpo de janela. É avaliado no instante da subida do pinch, que é quando
        // `tryDock` já decidiu o alvo (ver a regra de precedência no KDoc da classe).
        val pinchActive = right != null && right.isPinching && !right.isFist &&
            activeTarget == Target.NONE
        if (pinchActive && !rightPinchWasActive &&
            nowNs - lastDockToggleNs > GESTURE_COOLDOWN_NS
        ) {
            lastDockToggleNs = nowNs
            onToggleDock?.invoke()
        }
        rightPinchWasActive = pinchActive
    }

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
        val actions = DockPanelView.ACTION_ORDER
        // Largura **efetiva** (com a escala do usuário), não `MrTokens.DOCK_WIDTH_M`. Dividir
        // pela base com o dock ampliado faria o índice sair deslocado: o cursor estaria sobre
        // "YouTube" e a ação disparada seria a de "Centrar". O erro cresce com a escala e some
        // em 1.0, que é o tipo de bug que só aparece depois que o usuário mexe no tamanho.
        val half = dockHost.worldWidthM / 2f
        val fraction = ((worldX + half) / (half * 2f)).coerceIn(0f, 0.9999f)
        val index = (fraction * actions.size).roundToInt().coerceIn(0, actions.size - 1)
        return actions.getOrNull(index)
    }

    /** Libera qualquer gesto em andamento. Chamado ao desfazer a cena. */
    fun reset() {
        if (activeTarget != Target.NONE) releaseGesture()
        windowController.endGesture()
        windowController.clearHover()
        // O estado de borda dos gestos globais também. Sem isto, desfazer a cena com a mão
        // fechada e refazê-la já aberta contaria como uma subida falsa e trocaria o fundo.
        rightPinchWasActive = false
        rightFistWasActive = false
    }

    companion object {
        private const val TAG = "ZyvoxInputRouter"

        /**
         * Intervalo mínimo entre dois disparos do mesmo gesto global.
         *
         * 600 ms: longo o bastante para um gesto trêmulo não disparar duas vezes, curto o
         * bastante para o usuário poder esconder e reexibir a interface em sequência sem sentir
         * que o app ignorou o segundo gesto. Ver [updateGlobalGestures] para o caso de oclusão
         * que a detecção de borda sozinha não cobre.
         */
        private const val GESTURE_COOLDOWN_NS = 600_000_000L

        /** Log de diagnóstico: liga com `adb shell setprop log.tag.ZyvoxInputRouter VERBOSE`. */
        fun debugEnabled(): Boolean = Log.isLoggable(TAG, Log.VERBOSE)
    }
}
