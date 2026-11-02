package dev.zyvox.mr.hand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da lógica de gesto — a parte do app com maior chance de precisar de ajuste fino em
 * aparelho real, e a única que dá para verificar sem um celular na mão.
 *
 * Tudo aqui é Kotlin puro: nenhum `Context`, nenhum `Frame`, nenhum Robolectric.
 */
class GestureLogicTest {

    /**
     * Mão sintética com geometria controlável. Os índices seguem [HandJoint].
     *
     * @param pinch distância polegar↔indicador, em coordenada normalizada.
     * @param palm distância punho↔MIDDLE_MCP — o denominador da razão de pinch.
     */
    private fun hand(pinch: Float, palm: Float = 0.2f): List<HandPoint> {
        val points = MutableList(HandJoint.COUNT) { HandPoint(0.5f, 0.5f) }
        points[HandJoint.WRIST] = HandPoint(0.4f, 0.6f)
        points[HandJoint.MIDDLE_MCP] = HandPoint(0.4f + palm, 0.6f)
        points[HandJoint.THUMB_TIP] = HandPoint(0.5f, 0.5f)
        points[HandJoint.INDEX_TIP] = HandPoint(0.5f + pinch, 0.5f)
        return points
    }

    // ─── pinchRatio ─────────────────────────────────────────────────────────────────────

    @Test
    fun `razao de pinch e adimensional - mesma geometria em escalas diferentes da o mesmo valor`() {
        // É isto que faz o gesto funcionar com a mão perto e longe da câmera: se a razão
        // dependesse da distância absoluta, afastar a mão desfaria o pinch sozinho.
        val close = hand(pinch = 0.04f, palm = 0.20f)
        val far = hand(pinch = 0.02f, palm = 0.10f)

        val ratioClose = pinchRatio(close)!!
        val ratioFar = pinchRatio(far)!!

        assertEquals(ratioClose, ratioFar, 1e-4f)
        assertEquals(0.2f, ratioClose, 1e-4f)
    }

    @Test
    fun `palma pequena demais devolve null em vez de razao explosiva`() {
        // Dividir por uma palma ~zero produziria uma razão enorme ou NaN, o que o detector
        // interpretaria como "mão aberta" e soltaria o objeto que o usuário está arrastando.
        val tiny = hand(pinch = 0.001f, palm = 0.001f)
        assertEquals(null, pinchRatio(tiny))
    }

    @Test
    fun `landmark ausente devolve null em vez de lancar`() {
        // O modelo pode devolver menos de 21 pontos se a mão está parcialmente fora do
        // quadro. Lançar aqui derrubaria o pipeline inteiro por um quadro ruim.
        val partial = List(HandJoint.COUNT - 5) { HandPoint(0.5f, 0.5f) }
        assertEquals(null, pinchRatio(partial))
    }

    // ─── PinchDetector ──────────────────────────────────────────────────────────────────

    @Test
    fun `ativa abaixo do limiar e permanece ativa entre os dois limiares`() {
        val detector = PinchDetector(activateBelow = 0.45f, deactivateAbove = 0.68f, activateFrames = 1)

        assertFalse(detector.isPinching)
        assertTrue(detector.update(0.30f))
        assertTrue(detector.isPinching)

        // Histerese: 0.55 está acima do limiar de ativação mas abaixo do de desativação.
        // Um limiar único faria o estado tremer aqui, e na UI isso aparece como o objeto
        // sendo solto e re-pegado várias vezes por segundo.
        assertTrue(detector.update(0.55f))
        assertTrue(detector.isPinching)

        assertFalse(detector.update(0.80f))
        assertFalse(detector.isPinching)

        // E simetricamente: 0.55 agora não reativa, porque está acima de activateBelow.
        assertFalse(detector.update(0.55f))
        assertFalse(detector.isPinching)
    }

    @Test
    fun `exige N quadros consecutivos para ativar - pico unico nao dispara`() {
        val detector = PinchDetector(activateBelow = 0.45f, deactivateAbove = 0.68f, activateFrames = 2)

        // Um único quadro ruidoso abaixo do limiar não deve selecionar nada.
        assertFalse(detector.update(0.30f))
        assertFalse(detector.isPinching)

        // Interrompido por um quadro acima, o contador zera.
        assertFalse(detector.update(0.90f))

        // E um quadro abaixo depois da interrupção NÃO ativa: o contador recomeçou do zero,
        // então falta um quadro para chegar em activateFrames = 2. É isto que o teste promete no
        // nome — "pico único não dispara" — e é o que filtra o frame ruidoso que causa
        // "selecionou sozinho".
        assertFalse(detector.update(0.30f))
        assertFalse(detector.isPinching)

        // Dois consecutivos ativam.
        assertTrue(detector.update(0.30f))
        assertTrue(detector.isPinching)
    }

    @Test
    fun `null nao cancela um gesto em andamento`() {
        val detector = PinchDetector(activateFrames = 1)
        assertTrue(detector.update(0.30f))

        // Perder um quadro de detecção (oclusão momentânea do polegar pelo indicador) é
        // comum. Soltar o objeto arrastado por causa disso é pior que segurar a decisão.
        assertTrue(detector.update(null))
        assertTrue(detector.isPinching)
    }

    // ─── LandmarkSmoother ───────────────────────────────────────────────────────────────

    @Test
    fun `primeiro quadro adota a posicao em vez de suavizar desde a origem`() {
        val smoother = LandmarkSmoother()
        val (x, y) = smoother.update(0.7f, 0.3f)

        // Sem isto o cursor atravessaria a tela inteira na primeira detecção, partindo de
        // (0,0) — visível como um risco atravessando o passthrough.
        assertEquals(0.7f, x, 1e-5f)
        assertEquals(0.3f, y, 1e-5f)
    }

    @Test
    fun `movimento rapido passa quase sem filtro`() {
        val smoother = LandmarkSmoother(minAlpha = 0.18f, maxAlpha = 0.85f, fullSpeedAt = 0.035f)
        smoother.update(0.10f, 0.10f)
        // Salto de 0.30 = muito acima de fullSpeedAt, então alpha ≈ maxAlpha.
        val (x, _) = smoother.update(0.40f, 0.10f)

        // Com alpha 0.85: 0.10 + (0.40-0.10)*0.85 = 0.355. Um filtro fixo e lento deixaria
        // o cursor visivelmente atrás da mão — é isso que se sente como lag.
        assertTrue("esperado perto de 0.355, veio $x", x > 0.34f && x < 0.37f)
    }

    @Test
    fun `movimento lento e filtrado com forca`() {
        val smoother = LandmarkSmoother(minAlpha = 0.18f, maxAlpha = 0.85f, fullSpeedAt = 0.035f)
        smoother.update(0.500f, 0.500f)
        // Jitter subpixel: 0.002 de deslocamento, muito abaixo de fullSpeedAt.
        val (x, _) = smoother.update(0.502f, 0.500f)

        // Com alpha ≈ minAlpha (0.18): 0.500 + 0.002*0.18 = 0.50036. O cursor quase não se
        // move, que é o desejado quando o usuário está parado mirando em algo pequeno.
        assertTrue("esperado ~0.5004, veio $x", x < 0.5010f)
    }

    // ─── isHandUsable ───────────────────────────────────────────────────────────────────

    @Test
    fun `mao com landmarks extrapolados para fora do quadro e rejeitada`() {
        val points = hand(pinch = 0.05f)
        // O modelo extrapola quando a mão sai parcialmente do quadro; esses pontos são
        // instáveis e fariam o cursor voar pela tela.
        val offscreen = points.toMutableList().apply {
            this[HandJoint.PINKY_TIP] = HandPoint(1.9f, -0.8f)
        }
        assertFalse(isHandUsable(offscreen))
        assertTrue(isHandUsable(points))
    }

    // ─── HAND_BONES ─────────────────────────────────────────────────────────────────────

    @Test
    fun `topologia de ossos so referencia indices validos`() {
        // Um índice fora de faixa aqui seria um IndexOutOfBounds no desenho do esqueleto,
        // ou seja, um crash intermitente que só aparece quando o debug visual está ligado.
        HAND_BONES.forEach { (a, b) ->
            assertTrue("índice $a fora de faixa", a in 0 until HandJoint.COUNT)
            assertTrue("índice $b fora de faixa", b in 0 until HandJoint.COUNT)
        }
        assertTrue("espera-se uma mão articulada", HAND_BONES.size >= 19)
    }
}
