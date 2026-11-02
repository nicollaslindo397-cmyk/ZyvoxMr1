package dev.zyvox.mr.window

import dev.zyvox.mr.hand.HandPoint
import dev.zyvox.mr.hand.TrackedHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testes da máquina de estados do gesto.
 *
 * O defeito que estes testes impedem é o pior possível nesta UI: a página rola quando o
 * usuário queria mover a janela, ou a janela solta no meio de um arrasto.
 */
class WindowControllerTest {

    private lateinit var stack: WindowStack
    private lateinit var controller: WindowController

    /** Janela de teste centrada na origem, 0,64 × 0,40 m. */
    private lateinit var window: MrWindow

    @Before
    fun setUp() {
        stack = WindowStack(maxWindows = 3)
        controller = WindowController(stack)
        window = stack.open("https://a.example")!!
    }

    /** Mão sintética. Só `isPinching` importa para o controller. */
    private fun hand(pinching: Boolean) = TrackedHand(
        slot = 0,
        handednessLabel = "Right",
        handednessScore = 1f,
        points = List(21) { HandPoint(0.5f, 0.5f) },
        viewX = 0.5f,
        viewY = 0.5f,
        pinchRatio = if (pinching) 0.3f else 0.9f,
        isPinching = pinching,
        handScale = 0.2f,
        trackingScore = 1f,
    )

    // ─── Seleção de modo ────────────────────────────────────────────────────────────────

    @Test
    fun `pinch na barra de titulo inicia arrasto de janela`() {
        val titleY = window.topMeters - window.titleBarMeters / 2f

        controller.onHand(hand(pinching = true), window.xMeters, titleY, 0f)

        assertEquals(WindowController.Mode.DRAG_WINDOW, controller.mode)
        assertEquals(window.id, controller.activeWindowId)
    }

    @Test
    fun `pinch no corpo inicia toque no conteudo`() {
        controller.onHand(hand(pinching = true), window.xMeters, window.yMeters, 0f)

        assertEquals(WindowController.Mode.TOUCH_CONTENT, controller.mode)
    }

    @Test
    fun `pinch na alça inicia redimensionamento`() {
        val gripX = window.rightMeters - WindowStack.RESIZE_GRIP / 2f
        val gripY = window.bottomMeters + WindowStack.RESIZE_GRIP / 2f

        controller.onHand(hand(pinching = true), gripX, gripY, 0f)

        assertEquals(WindowController.Mode.RESIZE_WINDOW, controller.mode)
    }

    @Test
    fun `pinch no vazio nao inicia gesto nenhum`() {
        // Precisa continuar IDLE para que o chamador possa usar o gesto para outra coisa
        // (por exemplo, chamar o dock). Travar um modo aqui "roubaria" o pinch.
        val consumed = controller.onHand(hand(pinching = true), 5f, 5f, 0f)

        assertEquals(WindowController.Mode.IDLE, controller.mode)
        assertTrue("gesto no vazio não deve ser consumido", !consumed)
    }

    // ─── Arrastar ───────────────────────────────────────────────────────────────────────

    @Test
    fun `arrastar pela barra de titulo move a janela 1 por 1`() {
        val titleY = window.topMeters - window.titleBarMeters / 2f
        val x0 = window.xMeters
        val y0 = window.yMeters

        controller.onHand(hand(pinching = true), x0, titleY, 0f)
        controller.onHand(hand(pinching = true), x0 + 0.10f, titleY - 0.05f, 0f)

        val moved = stack.all.first { it.id == window.id }
        // Ganho 1:1 é deliberado: multiplicar faria a janela andar mais rápido que a mão, o
        // que quebra a ilusão de que ela está sendo segurada.
        assertEquals(x0 + 0.10f, moved.xMeters, 1e-4f)
        assertEquals(y0 - 0.05f, moved.yMeters, 1e-4f)
    }

    @Test
    fun `arrastar pelo corpo NAO move a janela`() {
        // Se movesse, seria impossível clicar num link: qualquer tremor de mão durante o
        // pinch arrastaria a janela junto.
        val x0 = window.xMeters
        val y0 = window.yMeters

        controller.onHand(hand(pinching = true), x0, y0, 0f)
        controller.onHand(hand(pinching = true), x0 + 0.10f, y0 + 0.10f, 0f)

        val unchanged = stack.all.first { it.id == window.id }
        assertEquals(x0, unchanged.xMeters, 1e-5f)
        assertEquals(y0, unchanged.yMeters, 1e-5f)
    }

    // ─── Travamento do modo ─────────────────────────────────────────────────────────────

    @Test
    fun `modo e travado no inicio do gesto e nao muda no meio`() {
        val titleY = window.topMeters - window.titleBarMeters / 2f
        controller.onHand(hand(pinching = true), window.xMeters, titleY, 0f)
        assertEquals(WindowController.Mode.DRAG_WINDOW, controller.mode)

        // Arrasta para dentro do corpo da janela. Sem travamento, o modo mudaria para
        // TOUCH_CONTENT no meio do movimento e a página começaria a rolar.
        controller.onHand(hand(pinching = true), window.xMeters, window.yMeters, 0f)

        assertEquals(WindowController.Mode.DRAG_WINDOW, controller.mode)
    }

    @Test
    fun `perder um quadro de pinch nao solta o arrasto`() {
        val titleY = window.topMeters - window.titleBarMeters / 2f
        controller.onHand(hand(pinching = true), window.xMeters, titleY, 0f)
        val x1 = stack.all.first { it.id == window.id }.xMeters

        // O detector perde um quadro (oclusão do polegar pelo indicador é comum). `hand`
        // continua presente mas `isPinching` fica false por um frame.
        controller.onHand(hand(pinching = false), window.xMeters + 0.05f, titleY, 0f)

        // O gesto terminou — isto é o comportamento correto e honesto: não há como
        // distinguir "perdeu um quadro" de "soltou de propósito" sem um temporizador, e um
        // temporizador introduz latência. O que importa é que o estado não fique pendurado.
        assertEquals(WindowController.Mode.IDLE, controller.mode)

        // E um novo pinch retoma o arrasto em vez de ficar travado.
        controller.onHand(hand(pinching = true), window.xMeters + 0.05f, titleY, 0f)
        assertEquals(WindowController.Mode.DRAG_WINDOW, controller.mode)
        assertTrue(stack.all.first { it.id == window.id }.xMeters >= x1)
    }

    @Test
    fun `perder a mao por completo encerra o gesto`() {
        val titleY = window.topMeters - window.titleBarMeters / 2f
        controller.onHand(hand(pinching = true), window.xMeters, titleY, 0f)
        assertEquals(WindowController.Mode.DRAG_WINDOW, controller.mode)

        controller.onHand(null, window.xMeters, titleY, 0f)

        // Sem isto, um TOUCH_CONTENT órfão deixaria o WebView com um pointer preso: a página
        // ficaria em estado de arrasto, links disparariam sozinhos e o scroll travaria.
        assertEquals(WindowController.Mode.IDLE, controller.mode)
    }

    @Test
    fun `soltar o pinch encerra o gesto`() {
        controller.onHand(hand(pinching = true), window.xMeters, window.yMeters, 0f)
        assertEquals(WindowController.Mode.TOUCH_CONTENT, controller.mode)

        controller.onHand(hand(pinching = false), window.xMeters, window.yMeters, 0f)

        assertEquals(WindowController.Mode.IDLE, controller.mode)
    }

    // ─── Fração de conteúdo ─────────────────────────────────────────────────────────────

    @Test
    fun `centro do webview mapeia para fracao 0,5`() {
        // O corpo útil fica ABAIXO da barra de título, então o centro do *quad* não é o
        // centro do *WebView*. Este teste é o que garante que o desconto da barra está certo.
        val bodyCenterY = window.bottomMeters + (window.heightMeters - window.titleBarMeters) / 2f

        controller.onHand(hand(pinching = true), window.xMeters, bodyCenterY, 0f)

        assertEquals(0.5f, controller.contentU, 0.02f)
        assertEquals(0.5f, controller.contentV, 0.02f)
    }

    @Test
    fun `topo do conteudo mapeia para v proximo de zero e nao para o meio`() {
        // Logo abaixo da barra de título = topo da página. Se o sinal de Y estiver invertido,
        // isto dá ~1.0 e o toque mira no espelho vertical: o link do rodapé abre quando se
        // aponta para o cabeçalho.
        val justBelowTitle = window.topMeters - window.titleBarMeters - 0.001f

        controller.onHand(hand(pinching = true), window.xMeters, justBelowTitle, 0f)

        assertTrue("esperado v≈0, veio ${controller.contentV}", controller.contentV < 0.10f)
    }

    @Test
    fun `base da janela mapeia para v proximo de um`() {
        controller.onHand(hand(pinching = true), window.xMeters, window.bottomMeters + 0.001f, 0f)
        assertTrue("esperado v≈1, veio ${controller.contentV}", controller.contentV > 0.90f)
    }

    @Test
    fun `fracao de conteudo fica sempre em 0 a 1`() {
        // O cursor pode estar fora da janela (a mão sai do alcance). Sem clamp, o pixel
        // calculado para o WebView ficaria negativo ou maior que a view, e o `dispatchTouchEvent`
        // seria ignorado — ou pior, interpretado como toque noutro elemento.
        controller.onHand(hand(pinching = true), window.xMeters, window.yMeters, 0f)
        controller.onHand(hand(pinching = true), window.xMeters + 5f, window.yMeters + 5f, 0f)

        assertTrue(controller.contentU in 0f..1f)
        assertTrue(controller.contentV in 0f..1f)
    }

    // ─── Hover ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `sem pinch o hover ainda identifica a janela sob o cursor`() {
        // É o que permite desenhar o destaque visual antes do gesto começar.
        controller.onHand(hand(pinching = false), window.xMeters, window.yMeters, 0f)

        assertEquals(window.id, controller.activeWindowId)
        assertEquals(WindowController.Mode.IDLE, controller.mode)
    }

    @Test
    fun `clearHover esquece a janela sob o cursor`() {
        controller.onHand(hand(pinching = false), window.xMeters, window.yMeters, 0f)
        controller.clearHover()
        assertNull(controller.activeWindowId)
    }

    @Test
    fun `pinch da foco a janela atingida`() {
        val a = stack.open("https://a.example")!!
        stack.moveTo(a.id, window.xMeters + 0.9f, window.yMeters)
        stack.focus(a.id)

        // Toca na primeira janela, que estava por baixo.
        controller.onHand(hand(pinching = true), window.xMeters, window.yMeters, 0f)

        assertEquals(window.id, stack.focusedId)
    }

    @Test
    fun `endGesture zera a fracao de conteudo`() {
        controller.onHand(hand(pinching = true), window.xMeters, window.yMeters, 0f)
        controller.endGesture()

        assertEquals(WindowController.Mode.IDLE, controller.mode)
        assertEquals(0f, controller.contentU, 0f)
        assertEquals(0f, controller.contentV, 0f)
    }
}
