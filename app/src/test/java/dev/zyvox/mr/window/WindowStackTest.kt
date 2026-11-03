package dev.zyvox.mr.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testes da multitarefa de janelas — o requisito "permitir abrir várias janelas
 * simultâneas" e "permanecer independente do Dock".
 */
class WindowStackTest {

    private lateinit var stack: WindowStack

    @Before
    fun setUp() {
        stack = WindowStack(maxWindows = 3)
    }

    // ─── Abertura e limite ──────────────────────────────────────────────────────────────

    @Test
    fun `abrir varias janelas cria ids distintos`() {
        val a = stack.open("https://a.example")
        val b = stack.open("https://b.example")

        assertNotNull(a)
        assertNotNull(b)
        assertFalse("ids devem ser únicos", a!!.id == b!!.id)
        assertEquals(2, stack.count)
    }

    @Test
    fun `janelas em sequencia nao ficam exatamente sobrepostas`() {
        // Sem cascata, a segunda janela cobre a primeira por completo e o usuário conclui
        // que "abrir várias não funciona" — mesmo funcionando.
        val a = stack.open("https://a.example")!!
        val b = stack.open("https://b.example")!!

        assertTrue("esperado deslocamento em x", b.xMeters > a.xMeters)
        assertTrue("esperado deslocamento em y", b.yMeters > a.yMeters)
    }

    @Test
    fun `limite de janelas devolve null em vez de alocar`() {
        // Cada janela é um WebView de verdade (dezenas de MB). Sem teto o app morre por
        // memória muito antes de morrer por GPU — e morre sem aviso.
        repeat(3) { assertNotNull(stack.open("https://example$it.com")) }
        assertTrue(stack.isFull)
        assertNull(stack.open("https://overflow.example"))
        assertEquals(3, stack.count)
    }

    // ─── Foco e ordem ───────────────────────────────────────────────────────────────────

    @Test
    fun `abrir da foco a janela nova`() {
        stack.open("https://a.example")
        val b = stack.open("https://b.example")!!
        assertEquals(b.id, stack.focusedId)
    }

    @Test
    fun `focus move a janela para o topo da pilha de desenho`() {
        val a = stack.open("https://a.example")!!
        stack.open("https://b.example")

        stack.focus(a.id)

        assertEquals(a.id, stack.focusedId)
        assertEquals("a janela focada deve ser a última da lista", a.id, stack.all.last().id)
    }

    @Test
    fun `fechar a janela focada transfere o foco`() {
        stack.open("https://a.example")
        val b = stack.open("https://b.example")!!

        stack.close(b.id)

        assertEquals(1, stack.count)
        assertNotNull("foco não pode ficar pendurado num id inexistente", stack.focusedId)
        assertNull(stack.all.firstOrNull { it.id == b.id })
    }

    @Test
    fun `closeAll esvazia e limpa o foco`() {
        stack.open("https://a.example")
        stack.open("https://b.example")

        stack.closeAll()

        assertEquals(0, stack.count)
        assertNull(stack.focusedId)
        assertFalse(stack.isFull)
    }

    // ─── Minimizar ──────────────────────────────────────────────────────────────────────

    @Test
    fun `minimizar oculta sem remover`() {
        // Destruir o WebView ao minimizar perderia scroll, formulário preenchido e vídeo em
        // reprodução. O requisito é minimizar, não fechar.
        val a = stack.open("https://a.example")!!

        stack.toggleMinimize(a.id)
        assertTrue(stack.all.first { it.id == a.id }.minimized)
        assertEquals("minimizar não deve fechar a janela", 1, stack.count)

        stack.toggleMinimize(a.id)
        assertFalse(stack.all.first { it.id == a.id }.minimized)
    }

    @Test
    fun `janela minimizada nao e atingida por windowAt`() {
        // Caso contrário o gesto "atravessaria" uma janela invisível e arrastaria nada.
        val a = stack.open("https://a.example")!!
        stack.toggleMinimize(a.id)

        assertNull(stack.windowAt(a.xMeters, a.yMeters))
    }

    // ─── Mover e redimensionar ──────────────────────────────────────────────────────────

    @Test
    fun `moveBy desloca a janela`() {
        val a = stack.open("https://a.example")!!
        val x0 = a.xMeters
        val y0 = a.yMeters

        stack.moveBy(a.id, 0.10f, -0.05f)

        val moved = stack.all.first { it.id == a.id }
        assertEquals(x0 + 0.10f, moved.xMeters, 1e-5f)
        assertEquals(y0 - 0.05f, moved.yMeters, 1e-5f)
    }

    @Test
    fun `moveBy nao deixa a janela sair completamente do alcance`() {
        val a = stack.open("https://a.example")!!
        stack.moveBy(a.id, 99f, 99f)

        val moved = stack.all.first { it.id == a.id }
        // Se a janela pudesse ir para metros de distância, o usuário não teria como
        // recuperá-la — e "recentralizar" só existe porque isso é um risco real.
        assertTrue(moved.xMeters <= 1.5f)
        assertTrue(moved.yMeters <= 1.5f)
    }

    @Test
    fun `resizeBy preserva a proporcao do conteudo`() {
        // Crítico: o conteúdo é uma View de tamanho fixo em pixel, e `pxPerUnits` é derivado
        // só da LARGURA. Se a altura variasse de forma independente, `pxPerUnits` daria um
        // valor por eixo, o quad esticaria a página e o mapeamento de toque erraria num dos
        // dois eixos.
        val a = stack.open("https://a.example")!!
        val aspect0 = a.widthMeters / a.heightMeters

        stack.resizeBy(a.id, 0.20f, 0.0f)

        val resized = stack.all.first { it.id == a.id }
        assertEquals("proporção deve ser preservada", aspect0, resized.widthMeters / resized.heightMeters, 1e-4f)
        assertEquals(WindowMetrics.ASPECT, resized.widthMeters / resized.heightMeters, 1e-4f)
        assertTrue("deve ter crescido", resized.widthMeters > a.widthMeters)
    }

    @Test
    fun `resizeBy respeita os limites minimo e maximo`() {
        val a = stack.open("https://a.example")!!

        stack.resizeBy(a.id, -99f, 99f)
        val min = stack.all.first { it.id == a.id }
        assertEquals(MrWindow.MIN_WIDTH, min.widthMeters, 1e-5f)

        stack.resizeBy(a.id, 99f, -99f)
        val max = stack.all.first { it.id == a.id }
        assertEquals(MrWindow.MAX_WIDTH, max.widthMeters, 1e-5f)
    }

    @Test
    fun `pxPerUnits e derivado da largura e consistente entre eixos`() {
        val a = stack.open("https://a.example")!!
        stack.resizeBy(a.id, 0.30f, 0f)
        val resized = stack.all.first { it.id == a.id }

        val fromWidth = stack.pxPerUnitsFor(resized)
        val fromHeight = WindowMetrics.CONTENT_HEIGHT_PX / resized.heightMeters

        // Se estes dois divergirem, o toque cai no lugar errado em janelas redimensionadas.
        assertEquals(fromWidth, fromHeight, fromWidth * 1e-4f)
    }

    // ─── Regiões ────────────────────────────────────────────────────────────────────────

    @Test
    fun `regioes da janela sao classificadas corretamente`() {
        val a = stack.open("https://a.example")!!

        // Barra de título: faixa superior.
        val titleY = a.topMeters - a.titleBarMeters / 2f
        assertEquals(WindowRegion.TITLE_BAR, stack.regionAt(a, a.xMeters, titleY))

        // Corpo: centro.
        assertEquals(WindowRegion.CONTENT, stack.regionAt(a, a.xMeters, a.yMeters))

        // Alça de redimensionar: canto inferior direito, e tem prioridade sobre o corpo
        // porque é pequena — se o usuário mirou nela, é porque quer ela.
        val gripX = a.rightMeters - WindowStack.RESIZE_GRIP / 2f
        val gripY = a.bottomMeters + WindowStack.RESIZE_GRIP / 2f
        assertEquals(WindowRegion.RESIZE_GRIP, stack.regionAt(a, gripX, gripY))
    }

    @Test
    fun `windowAt ignora ponto fora de qualquer janela`() {
        stack.open("https://a.example")
        assertNull(stack.windowAt(5f, 5f))
    }

    @Test
    fun `windowAt devolve a janela do topo quando ha sobreposicao`() {
        // Com janelas sobrepostas o gesto deve atingir a que está por cima — que é a que o
        // usuário vê. Iterar na ordem de desenho pegaria a de baixo.
        val a = stack.open("https://a.example")!!
        val b = stack.open("https://b.example")!!
        // Força sobreposição total.
        stack.moveTo(b.id, a.xMeters, a.yMeters)

        val hit = stack.windowAt(a.xMeters, a.yMeters)
        assertEquals("deve acertar a janela do topo", b.id, hit?.id)
    }

    // ─── Recentralizar ──────────────────────────────────────────────────────────────────

    @Test
    fun `recenter abre as janelas em leque em vez de empilhar no mesmo ponto`() {
        val a = stack.open("https://a.example")!!
        val b = stack.open("https://b.example")!!

        stack.recenter()

        val ra = stack.all.first { it.id == a.id }
        val rb = stack.all.first { it.id == b.id }
        assertFalse("janelas não devem ficar sobrepostas após recentralizar", ra.xMeters == rb.xMeters)
        assertFalse(ra.minimized)
        assertFalse(rb.minimized)
    }

    @Test
    fun `recenter preserva as urls`() {
        // Recentralizar é reposicionar, não reabrir. Reabrir recarregaria cada página e o
        // usuário perderia o estado de todas elas.
        val a = stack.open("https://a.example")!!
        stack.recenter()
        assertEquals("https://a.example", stack.all.first { it.id == a.id }.url)
    }

    @Test
    fun `recenter com uma janela unica a coloca no centro`() {
        val a = stack.open("https://a.example")!!
        stack.recenter()
        val recentred = stack.all.first { it.id == a.id }
        assertEquals(0f, recentred.xMeters, 1e-5f)
        assertEquals(MrWindow.DEFAULT_DEPTH, recentred.depthMeters, 1e-5f)
    }

    // ─── Título ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `titulo em branco e ignorado`() {
        // O `onReceivedTitle` do WebView dispara com "" em alguns redirects. Aceitar isso
        // apagaria o título útil que já estava lá.
        val a = stack.open("https://a.example", title = "Título bom")!!
        stack.setTitle(a.id, "   ")
        assertEquals("Título bom", stack.all.first { it.id == a.id }.title)

        stack.setTitle(a.id, "Novo título")
        assertEquals("Novo título", stack.all.first { it.id == a.id }.title)
    }

    @Test
    fun `operacoes em id inexistente nao lancam`() {
        // Pode acontecer se uma janela for fechada por um caminho enquanto um gesto ainda
        // referencia o id dela. Lançar aqui derrubaria o app no meio de um arrasto.
        stack.close("não-existe")
        stack.focus("não-existe")
        stack.moveBy("não-existe", 1f, 1f)
        stack.resizeBy("não-existe", 1f, 1f)
        stack.toggleMinimize("não-existe")
        stack.setTitle("não-existe", "x")
        assertEquals(0, stack.count)
    }
}
