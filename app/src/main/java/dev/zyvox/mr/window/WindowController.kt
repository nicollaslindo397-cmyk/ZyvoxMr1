package dev.zyvox.mr.window

import dev.zyvox.mr.hand.TrackedHand

/**
 * Decide o que o gesto da mão faz com as janelas.
 *
 * ### Máquina de estados, e por quê
 * O pinch é um sinal booleano ruidoso. Sem uma máquina de estados explícita, o que acontece
 * na prática é: o usuário fecha o gesto sobre a barra de título, arrasta um pouco, o
 * detector perde um quadro por oclusão do polegar, e a janela "solta" no meio do movimento.
 * Aqui o modo é **travado no início do gesto** e só é reavaliado quando o gesto termina de
 * verdade. Perder um quadro de detecção não muda o modo.
 *
 * Os modos são mutuamente exclusivos de propósito. "Arrastar a janela" e "tocar na página"
 * competem pelo mesmo gesto, e tentar adivinhar no meio do movimento é o que produz o pior
 * defeito possível nesta UI: a página rola quando o usuário queria mover a janela.
 *
 * Sem Android: testável em JVM.
 */
class WindowController(
    private val stack: WindowStack,
    /** Distância, em metros, para considerar que o cursor "chegou" numa janela. */
    private val snapMeters: Float = 0.0f,
) {

    /** O que o gesto em andamento está fazendo. */
    enum class Mode { IDLE, DRAG_WINDOW, RESIZE_WINDOW, TOUCH_CONTENT }

    var mode: Mode = Mode.IDLE
        private set

    var activeWindowId: String? = null
        private set

    /**
     * Posição do cursor **dentro da janela ativa**, em fração do quad: `0..1` da esquerda
     * para a direita e de cima para baixo (convenção de pixel de view, não de mundo).
     *
     * É o que o chamador converte em pixel para injetar no WebView — ver
     * [dev.zyvox.mr.spatial.SyntheticTouch]. Manter em fração aqui deixa esta classe sem
     * saber o tamanho em pixel do conteúdo.
     */
    var contentU: Float = 0f
        private set
    var contentV: Float = 0f
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    /**
     * Processa uma mão. Retorna `true` se o gesto foi consumido por uma janela (e portanto
     * não deve virar outra coisa, como abrir o dock).
     *
     * @param cursorX/Y/Z posição do cursor no mundo, em metros, no plano das janelas.
     */
    fun onHand(hand: TrackedHand?, cursorX: Float, cursorY: Float, cursorZ: Float): Boolean {
        if (hand == null) {
            // Mão perdida por completo (saiu do quadro): encerra o gesto em vez de deixá-lo
            // pendurado. Um `TOUCH_CONTENT` órfão deixaria o WebView com pointer preso.
            if (mode != Mode.IDLE) endGesture()
            return false
        }

        if (hand.isPinching) {
            if (mode == Mode.IDLE) beginGesture(hand, cursorX, cursorY, cursorZ)
            else continueGesture(cursorX, cursorY, cursorZ)
            return mode != Mode.IDLE
        }

        // Gesto aberto.
        if (mode != Mode.IDLE) endGesture()
        // Mesmo sem pinch, atualizamos qual janela está sob o cursor — é o que permite
        // desenhar o destaque de hover.
        activeWindowId = stack.windowAt(cursorX, cursorY)?.id
        return false
    }

    private fun beginGesture(hand: TrackedHand, x: Float, y: Float, z: Float) {
        val window = stack.windowAt(x, y)
        if (window == null) {
            // Pinch no vazio: não faz nada com janelas. Deixa `mode = IDLE` para que o
            // chamador possa usar o gesto para outra coisa (ex.: chamar o dock).
            return
        }
        stack.focus(window.id)
        activeWindowId = window.id
        lastX = x
        lastY = y
        lastZ = z

        mode = when (stack.regionAt(window, x, y)) {
            WindowRegion.TITLE_BAR -> Mode.DRAG_WINDOW
            WindowRegion.RESIZE_GRIP -> Mode.RESIZE_WINDOW
            WindowRegion.CONTENT -> Mode.TOUCH_CONTENT
        }

        if (mode == Mode.TOUCH_CONTENT) {
            updateContentFraction(window, x, y)
        }
    }

    private fun continueGesture(x: Float, y: Float, z: Float) {
        val id = activeWindowId ?: return
        val window = stack.all.firstOrNull { it.id == id } ?: return

        when (mode) {
            Mode.DRAG_WINDOW -> {
                // Delta no mundo = delta na janela, 1:1. Multiplicar por ganho > 1 faria a
                // janela andar mais rápido que a mão, o que quebra a ilusão de que ela está
                // sendo segurada.
                stack.moveBy(id, x - lastX, y - lastY)
            }

            Mode.RESIZE_WINDOW -> {
                // A alça fica no canto inferior direito: +x alarga, e -y (para baixo no
                // mundo) aumenta a altura. O `resizeBy` já cuida de manter a alça sob o
                // cursor ajustando o centro.
                stack.resizeBy(id, x - lastX, -(y - lastY))
            }

            Mode.TOUCH_CONTENT -> updateContentFraction(window, x, y)

            Mode.IDLE -> Unit
        }
        lastX = x
        lastY = y
        lastZ = z
    }

    /**
     * Converte posição de mundo em fração do quad, já descontando a barra de título.
     *
     * O `v` é invertido: no mundo +y é para cima, em pixel de view +y é para baixo. Errar o
     * sinal aqui faz o toque mirar no espelho vertical da página — o link do rodapé abre
     * quando se aponta para o cabeçalho.
     */
    private fun updateContentFraction(window: MrWindow, x: Float, y: Float) {
        val u = ((x - window.leftMeters) / window.widthMeters).coerceIn(0f, 1f)
        val vWorld = ((window.topMeters - y) / window.heightMeters).coerceIn(0f, 1f)

        // A barra de título ocupa o topo do quad mas NÃO faz parte do WebView. Remapeamos o
        // restante para `0..1` do WebView, senão o toque fica comprimido e desalinhado.
        val titleFraction = window.titleBarMeters / window.heightMeters
        val v = ((vWorld - titleFraction) / (1f - titleFraction)).coerceIn(0f, 1f)

        contentU = u
        contentV = v
    }

    /**
     * Termina o gesto. O chamador é responsável por mandar o `UP`/`CANCEL` correspondente no
     * [dev.zyvox.mr.spatial.SyntheticTouch] **antes** de chamar isto, porque aqui já não
     * sabemos se o pointer estava dentro da área de conteúdo.
     */
    fun endGesture() {
        mode = Mode.IDLE
        contentU = 0f
        contentV = 0f
        // `activeWindowId` é preservado: é o hover atual, útil para o destaque visual.
    }

    /** Esquece o hover. Usado quando nenhuma mão está visível. */
    fun clearHover() {
        activeWindowId = null
    }

    companion object {
        /** Distância máxima, em metros, que um arrasto de janela percorre por quadro. */
        const val MAX_DRAG_STEP_M = 0.25f
    }
}
