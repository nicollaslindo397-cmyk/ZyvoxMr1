package dev.zyvox.mr.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do mapeamento imagem→tela.
 *
 * Esta é a função com maior probabilidade de estar sutilmente errada em todo o app, e o
 * sintoma de errar é horrível de diagnosticar: **o cursor aparece, funciona, e não está onde
 * está o dedo**. Nada lança, nada trava — só desalinha. Daí o teste cobrir as proporções
 * reais em vez de um caso feliz quadrado.
 */
class ScreenMapperTest {

    // Proporção típica: análise em 480×640 upright (retrato) e tela de celular 1080×2400.
    private val imgW = 480
    private val imgH = 640
    private val viewW = 1080
    private val viewH = 2400

    @Test
    fun `centro da imagem mapeia para o centro da view em qualquer proporcao`() {
        // O centro é o único ponto que o aspect-fill nunca desloca. Se isto falhar, a
        // fórmula inteira está errada.
        val (x, y) = ScreenMapper.imageToView(0.5f, 0.5f, imgW, imgH, viewW, viewH)
        assertEquals(viewW / 2f, x, 0.5f)
        assertEquals(viewH / 2f, y, 0.5f)
    }

    @Test
    fun `eixo nao cortado mapeia de ponta a ponta`() {
        // aspect-fill: scale = max(1080/480, 2400/640) = max(2.25, 3.75) = 3.75.
        // Altura escala para 640*3.75 = 2400 = altura da view → Y NÃO é cortado.
        // Logo y=0 → 0 e y=1 → viewH.
        val (_, yTop) = ScreenMapper.imageToView(0.5f, 0f, imgW, imgH, viewW, viewH)
        val (_, yBottom) = ScreenMapper.imageToView(0.5f, 1f, imgW, imgH, viewW, viewH)
        assertEquals(0f, yTop, 0.5f)
        assertEquals(viewH.toFloat(), yBottom, 0.5f)
    }

    @Test
    fun `eixo cortado mapeia para alem dos limites da view`() {
        // No caso acima a largura escala para 480*3.75 = 1800 px numa view de 1080: sobram
        // 720 px, cortados 360 de cada lado. Então x=0 da imagem está FORA da tela, à
        // esquerda.
        //
        // Isso é desejável, não um bug: a mão pode estar na parte cortada do sensor, e o
        // cursor deve seguir para fora da tela em vez de grudar na borda. Grudar na borda
        // faria o usuário perder o controle exatamente ao alcançar o limite do quadro.
        val (xLeft, _) = ScreenMapper.imageToView(0f, 0.5f, imgW, imgH, viewW, viewH)
        assertTrue("esperado negativo, veio $xLeft", xLeft < 0f)

        val (xRight, _) = ScreenMapper.imageToView(1f, 0.5f, imgW, imgH, viewW, viewH)
        assertTrue("esperado > viewW, veio $xRight", xRight > viewW)
    }

    @Test
    fun `fracao visivel do eixo cortado bate com a geometria`() {
        val (visibleX, visibleY) = ScreenMapper.visibleFractions(imgW, imgH, viewW, viewH)
        // 1080 / 1800 = 0.6 — só 60% da largura do sensor aparece na tela.
        assertEquals(0.6f, visibleX, 1e-3f)
        // O eixo que não foi cortado mostra tudo.
        assertEquals(1f, visibleY, 1e-3f)
    }

    @Test
    fun `ida e volta e consistente`() {
        // Se imageToView e viewToImage não forem inversas exatas, o toque injetado no
        // WebView cai num pixel diferente do que o cursor mostra — e o usuário clica em A
        // enquanto o cursor está sobre B.
        val originalNx = 0.42f
        val originalNy = 0.63f

        val (vx, vy) = ScreenMapper.imageToView(originalNx, originalNy, imgW, imgH, viewW, viewH)
        val (backNx, backNy) = ScreenMapper.viewToImage(vx, vy, imgW, imgH, viewW, viewH)

        assertEquals(originalNx, backNx, 1e-4f)
        assertEquals(originalNy, backNy, 1e-4f)
    }

    @Test
    fun `proporcao igual entre imagem e view nao corta nada`() {
        // Quando as proporções batem, aspect-fill não corta e o mapeamento degenera para
        // identidade. É o caso de um tablet ou de um preview letterboxed.
        val (x, y) = ScreenMapper.imageToView(0.25f, 0.75f, 800, 600, 1600, 1200)
        assertEquals(1600 * 0.25f, x, 0.5f)
        assertEquals(1200 * 0.75f, y, 0.5f)
    }

    @Test
    fun `dimensoes invalidas nao lancam`() {
        // Antes do primeiro layout, width/height são 0. Lançar aqui crasharia o app na
        // partida, antes de qualquer frame de câmera existir.
        val (x, y) = ScreenMapper.imageToView(0.5f, 0.5f, 0, 0, viewW, viewH)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)

        val (x2, y2) = ScreenMapper.imageToView(0.5f, 0.5f, imgW, imgH, 0, 0)
        assertEquals(0f, x2, 0f)
        assertEquals(0f, y2, 0f)
    }

    @Test
    fun `paisagem inverte qual eixo e cortado`() {
        // Girar o aparelho troca quais dimensões são "largura" e "altura" da imagem upright.
        // O mapeamento tem de acompanhar, senão o cursor fica desalinhado só em paisagem —
        // um bug que só aparece quando o usuário vira o telefone.
        val (visibleX, visibleY) = ScreenMapper.visibleFractions(640, 480, 2400, 1080)
        // scale = max(2400/640, 1080/480) = max(3.75, 2.25) = 3.75.
        // largura: 640*3.75 = 2400 = view → não cortada. altura: 480*3.75 = 1800 > 1080 → cortada.
        assertEquals(1f, visibleX, 1e-3f)
        assertEquals(1080f / 1800f, visibleY, 1e-3f)
    }
}
