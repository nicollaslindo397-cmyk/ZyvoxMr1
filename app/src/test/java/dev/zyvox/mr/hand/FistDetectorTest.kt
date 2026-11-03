package dev.zyvox.mr.hand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de [fistRatio] e [FistDetector].
 *
 * ### Por que isto merece teste próprio
 * O gesto de mão fechada troca o fundo da experiência. Um falso positivo aqui não é um
 * desconforto, é o usuário perdendo o passthrough no meio do uso — e a causa mais provável de um
 * falso positivo é exatamente o caso que o terceiro teste abaixo cobre: fechar a mão para um
 * *pinch* também aproxima o polegar do indicador, então se o indicador entrasse no cálculo da
 * mão fechada, os dois gestos disparariam juntos.
 *
 * Kotlin puro, sem `Context`: roda em JVM, que é o único lugar onde dá para verificar gesto sem
 * um aparelho na mão.
 */
class FistDetectorTest {

    /**
     * Mão sintética com os dedos dobráveis, definida inteira em **frações da palma**.
     *
     * Não é preciosismo: `fistRatio` é uma razão de distâncias, então ela só é adimensional se
     * o fixture for geometricamente similar em qualquer escala. A primeira versão desta função
     * tinha posições-x absolutas (0.39, 0.38) e offsets-y fixos que não escalavam junto com a
     * palma — o resultado era uma razão de 2.159 com palma 0.20 e 2.109 com palma 0.10, e o
     * teste de invariância falhava por um defeito do fixture, não do produto.
     *
     * @param extend distância da ponta dos três dedos ao pulso, em múltiplos da palma.
     *   ~2.2 = mão aberta, ~1.1 = punho. São os valores típicos do modelo: num punho as pontas
     *   ficam pouco além dos nós dos dedos (~1.0× palma), e abertas chegam a ~2.2×.
     * @param indexOffset o mesmo para o indicador, que [fistRatio] não lê — serve para provar
     *   isso no teste de isolamento.
     */
    private fun hand(
        extend: Float,
        indexOffset: Float = extend,
        palm: Float = 0.2f,
    ): List<HandPoint> {
        // Pulso na origem local; tudo o mais é fração de `palm`. Assim, dobrar a palma dobra
        // todas as distâncias e a razão não muda.
        val wristX = 0.40f
        val wristY = 0.60f
        // Abertura lateral dos dedos, também em frações: 0.00 / -0.05 / -0.10 da palma. Numa
        // mão real as pontas não ficam alinhadas, e se ficassem todas exatamente à mesma
        // distância um erro de índice no `FIST_TIPS` passaria despercebido.
        val middleSpread = 0.00f
        val ringSpread = -0.05f
        val pinkySpread = -0.10f
        // Flexão palmar: as pontas do anelar e do mindinho ficam um pouco "abaixo" do eixo,
        // igualmente em fração da palma.
        val ringDrop = 0.15f
        val pinkyDrop = 0.30f

        val points = MutableList(HandJoint.COUNT) { HandPoint(0.5f, 0.5f) }
        points[HandJoint.WRIST] = HandPoint(wristX, wristY)
        points[HandJoint.MIDDLE_MCP] = HandPoint(wristX + palm, wristY)
        points[HandJoint.MIDDLE_TIP] = HandPoint(wristX + extend * palm, wristY + middleSpread * palm)
        points[HandJoint.RING_TIP] = HandPoint(
            wristX + extend * palm + ringSpread * palm,
            wristY + ringDrop * palm,
        )
        points[HandJoint.PINKY_TIP] = HandPoint(
            wristX + extend * palm + pinkySpread * palm,
            wristY + pinkyDrop * palm,
        )
        points[HandJoint.INDEX_TIP] = HandPoint(
            wristX + indexOffset * palm,
            wristY - 0.15f * palm,
        )
        return points
    }

    private val openRatio: Float
        get() = fistRatio(hand(extend = OPEN_EXTEND))!!

    private val fistRatioValue: Float
        get() = fistRatio(hand(extend = FIST_EXTEND))!!

    private companion object {
        /**
         * Extensão da ponta dos dedos em múltiplos da palma, para mão aberta e punho.
         *
         * Calibrados contra os limiares de produção, não escolhidos para passar no teste:
         * [OPEN_EXTEND] dá razão 2.159 e [FIST_EXTEND] dá 1.068, ambos do lado certo de
         * `FIST_OPEN_ABOVE = 1.80` e `FIST_CLOSE_BELOW = 1.55` com folga. Se alguém mexer nos
         * limiares sem conferir contra uma mão de verdade, o teste de limiares abaixo falha.
         */
        const val OPEN_EXTEND = 2.2f
        const val FIST_EXTEND = 1.1f
    }

    // ─── fistRatio ──────────────────────────────────────────────────────────────────────

    @Test
    fun `razao de punho e adimensional - mesma geometria em escalas diferentes da o mesmo valor`() {
        // É o que faz o gesto funcionar com a mão perto e longe da câmera. Se dependesse de
        // distância absoluta, afastar o braço abriria a mão fechada sozinha.
        // `extend` é fração da palma, então a MESMA forma em duas escalas é o mesmo `extend` com
        // palmas diferentes — não um `extend` numericamente menor. Foi assim que a primeira
        // versão deste teste falhou: ela passava 0.15f absoluto achando que era "a mão de longe".
        val close = fistRatio(hand(extend = OPEN_EXTEND, palm = 0.20f))!!
        val far = fistRatio(hand(extend = OPEN_EXTEND, palm = 0.10f))!!

        assertEquals(close, far, 1e-4f)
    }

    @Test
    fun `mao aberta e punho ficam em lados opostos dos limiares`() {
        // Este é o teste que amarra os limiares à geometria. Se alguém ajustar
        // FIST_CLOSE_BELOW ou FIST_OPEN_ABOVE sem conferir contra uma mão de verdade,
        // este teste falha em vez do gesto falhar em silêncio no aparelho.
        assertTrue("aberta deveria exceder o limiar de abrir", openRatio > FIST_OPEN_ABOVE)
        assertTrue("punho deveria ficar abaixo do limiar de fechar", fistRatioValue < FIST_CLOSE_BELOW)
        assertTrue("os limiares precisam estar ordenados", FIST_CLOSE_BELOW < FIST_OPEN_ABOVE)
    }

    @Test
    fun `indicador nao participa do calculo - senao o pinch dispararia o fundo junto`() {
        // Numa mão fechada de verdade o polegar dobra por cima do indicador, então o indicador
        // também se aproxima do pulso. Incluí-lo faria `fistRatio` cair durante um pinch e os
        // dois gestos disparariam ao mesmo tempo. Excluí-lo de propósito é a decisão de projeto;
        // este teste é o que impede alguém de "corrigir" a lista depois.
        val withIndexExtended = fistRatio(hand(extend = FIST_EXTEND, indexOffset = OPEN_EXTEND))!!
        val withIndexFolded = fistRatio(hand(extend = FIST_EXTEND, indexOffset = FIST_EXTEND))!!

        assertEquals(withIndexExtended, withIndexFolded, 1e-5f)
        // E o resultado continua sendo punho: isolar o indicador não pode ter transformado a
        // mão fechada em mão aberta.
        assertTrue(withIndexExtended < FIST_CLOSE_BELOW)
    }

    @Test
    fun `palma pequena demais devolve null em vez de uma razao sem sentido`() {
        // Com o pulso quase sobre o nó do dedo o denominador vai a zero e a razão explode. É o
        // mesmo contrato de `pinchRatio`: mão longe demais ou landmark degenerado → `null`, e o
        // detector preserva o estado em vez de decidir com lixo.
        val degenerate = hand(extend = OPEN_EXTEND, palm = MIN_PALM / 2f)

        assertNull(fistRatio(degenerate))
    }

    // ─── FistDetector ───────────────────────────────────────────────────────────────────

    @Test
    fun `exige quadros consecutivos para fechar - um quadro ruidoso nao troca o fundo`() {
        val detector = FistDetector(closeFrames = 2)

        assertFalse(detector.update(FIST_CLOSE_BELOW - 0.5f))
        assertTrue("o segundo quadro consecutivo deve fechar", detector.update(FIST_CLOSE_BELOW - 0.5f))
    }

    @Test
    fun `quadro ruidoso no meio zera a contagem`() {
        val detector = FistDetector(closeFrames = 2)

        assertFalse(detector.update(FIST_CLOSE_BELOW - 0.5f))
        // Um único quadro acima do limiar (ruído de landmark) tem de reiniciar a contagem, senão
        // dois quadros fechados não-consecutivos ao longo de um minuto fechariam a mão.
        assertFalse(detector.update(FIST_OPEN_ABOVE + 0.5f))
        assertFalse("a contagem deveria ter zerado", detector.update(FIST_CLOSE_BELOW - 0.5f))
    }

    @Test
    fun `histerese impede oscilacao entre os dois limiares`() {
        val detector = FistDetector()

        repeat(3) { detector.update(FIST_CLOSE_BELOW - 0.5f) }
        assertTrue(detector.isFist)

        // Entre os limiares o estado não muda em nenhuma direção. Com um limiar único, uma mão
        // relaxada oscilando em volta do corte faria o fundo piscar várias vezes por segundo.
        repeat(10) {
            detector.update((FIST_CLOSE_BELOW + FIST_OPEN_ABOVE) / 2f)
            assertTrue("o estado deveria se manter no meio da histerese", detector.isFist)
        }

        detector.update(FIST_OPEN_ABOVE + 0.5f)
        assertFalse("abrir acima do limiar superior deve soltar", detector.isFist)
    }

    @Test
    fun `null preserva o estado em vez de decidir com um quadro inutil`() {
        val detector = FistDetector(closeFrames = 1)

        assertTrue(detector.update(FIST_CLOSE_BELOW - 0.5f))
        // A mão saiu do quadro por um instante. Se `null` abrisse a mão, piscar o tracking
        // desfaria o gesto; se fechasse, inventaria um punho do nada.
        assertTrue("null deve preservar o estado fechado", detector.update(null))

        val fresh = FistDetector(closeFrames = 1)
        assertFalse("null deve preservar o estado aberto", fresh.update(null))
    }

    @Test
    fun `reset limpa o estado e a contagem pendente`() {
        val detector = FistDetector(closeFrames = 3)

        detector.update(FIST_CLOSE_BELOW - 0.5f)
        detector.update(FIST_CLOSE_BELOW - 0.5f)
        detector.reset()

        // Se o reset limpasse só `closed` e não `pending`, o próximo quadro fecharia a mão com
        // uma contagem herdada de antes — e o `SpatialInputRouter.reset()` é chamado ao sair da
        // cena, então esse quadro herdado viria já na cena nova.
        assertFalse(detector.update(FIST_CLOSE_BELOW - 0.5f))
        assertFalse(detector.isFist)
    }
}
