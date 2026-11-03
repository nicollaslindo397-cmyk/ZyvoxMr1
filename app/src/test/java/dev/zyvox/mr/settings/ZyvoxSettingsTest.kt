package dev.zyvox.mr.settings

import dev.zyvox.mr.spatial.InterfaceTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da configuração persistível.
 *
 * O que está em jogo aqui não é cosmética. Um valor fora do intervalo produce uma interface que o
 * usuário não consegue ver nem recuperar pelos próprios controles — o app parece quebrado sem
 * lançar exceção. E os presets precisam mudar distância **e** escala, senão "Interface distante"
 * é só um zoom com outro nome.
 */
class ZyvoxSettingsTest {

    // ─── Sanitização ────────────────────────────────────────────────────────────────────

    @Test
    fun `valores alem dos limites sao trazidos para dentro`() {
        val absurd = InterfaceLayout(
            distanceM = 900f,
            heightM = -900f,
            offsetXM = 900f,
            tiltDeg = 900f,
            scale = 900f,
        ).sanitized()

        assertEquals(InterfaceLayout.MAX_DISTANCE_M, absurd.distanceM, 0f)
        assertEquals(InterfaceLayout.MIN_HEIGHT_M, absurd.heightM, 0f)
        assertEquals(InterfaceLayout.MAX_OFFSET_X_M, absurd.offsetXM, 0f)
        assertEquals(InterfaceLayout.MAX_TILT_DEG, absurd.tiltDeg, 0f)
        assertEquals(InterfaceLayout.MAX_SCALE, absurd.scale, 0f)
    }

    @Test
    fun `NaN cai no padrao porque coerceIn nao trata NaN`() {
        // Este é o caso que um clamp simples deixa passar: `NaN.coerceIn(a, b)` devolve NaN.
        // Um NaN em `distanceM` coloca o painel numa posição que nenhuma matriz resolve, e a
        // interface inteira desaparece sem erro no log.
        val corrupt = InterfaceLayout(
            distanceM = Float.NaN,
            heightM = Float.NaN,
            offsetXM = Float.NaN,
            tiltDeg = Float.NaN,
            scale = Float.NaN,
        ).sanitized()

        assertEquals(InterfaceLayout.DEFAULT_DISTANCE_M, corrupt.distanceM, 0f)
        assertEquals(0f, corrupt.heightM, 0f)
        assertEquals(0f, corrupt.offsetXM, 0f)
        assertEquals(0f, corrupt.tiltDeg, 0f)
        assertEquals(1f, corrupt.scale, 0f)
    }

    @Test
    fun `infinito tambem cai no padrao`() {
        val infinite = InterfaceLayout(
            distanceM = Float.POSITIVE_INFINITY,
            scale = Float.NEGATIVE_INFINITY,
        ).sanitized()

        assertEquals(InterfaceLayout.DEFAULT_DISTANCE_M, infinite.distanceM, 0f)
        assertEquals(1f, infinite.scale, 0f)
    }

    @Test
    fun `o padrao de fabrica ja e valido`() {
        // Se o default precisasse de sanitização, todo app recém-instalado começaria num estado
        // inválido até o primeiro `load()`.
        val defaults = ZyvoxSettings()

        assertEquals(defaults, defaults.sanitized())
    }

    // ─── Recentralizar ──────────────────────────────────────────────────────────────────

    @Test
    fun `recentralizar zera posicao mas preserva distancia e tamanho`() {
        // "Recentralizar" não pode significar "restaurar tudo": quem ajustou a distância para o
        // próprio conforto perderia o ajuste, e passaria a evitar o botão.
        val adjusted = InterfaceLayout(
            distanceM = 1.4f,
            heightM = 0.3f,
            offsetXM = -0.5f,
            tiltDeg = 20f,
            scale = 1.6f,
            pinned = true,
        )

        val recentred = adjusted.recentered()

        assertEquals(0f, recentred.heightM, 0f)
        assertEquals(0f, recentred.offsetXM, 0f)
        assertEquals(0f, recentred.tiltDeg, 0f)
        assertEquals(1.4f, recentred.distanceM, 0f)
        assertEquals(1.6f, recentred.scale, 0f)
        assertTrue("fixação não é posição, não deveria ser desfeita", recentred.pinned)
    }

    // ─── Presets ────────────────────────────────────────────────────────────────────────

    @Test
    fun `presets ordenam a distancia e preservam a escala do usuario`() {
        // Escala preservada de propósito: ver `withPreset`. Com a largura do dock ancorada no
        // campo de visão, o tamanho aparente já não depende da distância, então um preset que
        // também mexesse na escala contaria o efeito duas vezes — e no preset distante faria a
        // interface transbordar a tela.
        val enlarged = ZyvoxSettings().withLayout(InterfaceLayout(scale = 1.3f))

        val near = enlarged.withPreset(InterfacePreset.NEAR).layout
        val medium = enlarged.withPreset(InterfacePreset.MEDIUM).layout
        val far = enlarged.withPreset(InterfacePreset.FAR).layout

        assertTrue("perto deveria ser a menor distância", near.distanceM < medium.distanceM)
        assertTrue("longe deveria ser a maior distância", far.distanceM > medium.distanceM)
        assertEquals("perto não deveria mudar o tamanho", 1.3f, near.scale, 1e-6f)
        assertEquals("médio não deveria mudar o tamanho", 1.3f, medium.scale, 1e-6f)
        assertEquals("longe não deveria mudar o tamanho", 1.3f, far.scale, 1e-6f)
    }

    @Test
    fun `a largura ancorada no campo de visao da o mesmo tamanho angular em qualquer preset`() {
        // Esta é a propriedade em que o sistema inteiro de "aproximar/afastar" se apoia, e é o
        // que torna os presets seguros: a largura física cresce com a distância na mesma
        // proporção, então o ângulo visual não muda.
        //
        // Sem ela, "Interface distante" seria uma interface minúscula — o requisito diz
        // explicitamente que os elementos precisam de tamanho confortável dentro do VR Box.
        val dockFraction = 0.92f
        val base = ZyvoxSettings()

        for (hfovDeg in listOf(50f, 60f, 70f)) {
            val halfFovTan = Math.tan(Math.toRadians((hfovDeg / 2f).toDouble())).toFloat()
            val angles = mutableListOf<Float>()

            for (preset in listOf(InterfacePreset.NEAR, InterfacePreset.MEDIUM, InterfacePreset.FAR)) {
                val distance = base.withPreset(preset).layout.distanceM
                // Largura visível do mundo àquela profundidade, vezes a fração que o dock ocupa.
                val visibleWidth = 2f * distance * halfFovTan
                val dockWidth = visibleWidth * dockFraction
                angles += InterfaceTransform.angularWidthDeg(dockWidth, distance)
            }

            // Invariância: os três presets têm de dar o mesmo ângulo.
            assertEquals("hfov $hfovDeg near×medium", angles[0], angles[1], 0.01f)
            assertEquals("hfov $hfovDeg medium×far", angles[1], angles[2], 0.01f)

            // E o ângulo tem de estar numa faixa confortável: grande o bastante para ler dentro
            // de um VR Box, pequeno o bastante para não exigir virar a cabeça.
            for (angle in angles) {
                assertTrue("hfov $hfovDeg deu ângulo $angle", angle in 40f..75f)
            }
        }
    }

    @Test
    fun `preset personalizado nao sobrescreve o ajuste manual`() {
        val manual = ZyvoxSettings().withLayout(
            InterfaceLayout(distanceM = 1.13f, heightM = 0.22f, scale = 1.31f)
        )

        val custom = manual.withPreset(InterfacePreset.CUSTOM)

        assertEquals(1.13f, custom.layout.distanceM, 1e-6f)
        assertEquals(0.22f, custom.layout.heightM, 1e-6f)
        assertEquals(1.31f, custom.layout.scale, 1e-6f)
        assertEquals(InterfacePreset.CUSTOM, custom.preset)
    }

    @Test
    fun `mexer em qualquer controle marca o preset como personalizado`() {
        // Sem isto a UI continuaria mostrando "Média" selecionado depois de o usuário ter movido
        // a interface — um rótulo que mente sobre o estado.
        val afterManualMove = ZyvoxSettings(preset = InterfacePreset.MEDIUM)
            .withLayout(InterfaceLayout(distanceM = 0.9f))

        assertEquals(InterfacePreset.CUSTOM, afterManualMove.preset)
    }

    @Test
    fun `presets produzem valores dentro dos limites`() {
        for (preset in InterfacePreset.values()) {
            val layout = ZyvoxSettings().withPreset(preset).layout

            assertEquals("$preset distância", layout, layout.sanitized())
        }
    }

    // ─── Restaurar ──────────────────────────────────────────────────────────────────────

    @Test
    fun `restaurar devolve exatamente o padrao de fabrica`() {
        val messed = ZyvoxSettings()
            .withPreset(InterfacePreset.FAR)
            .copy(
                displayMode = DisplayMode.VR_BOX,
                trackingMode = TrackingMode.SPATIAL_6DOF,
                graphicsQuality = GraphicsQuality.LOW,
                hand = HandSettings(enabled = false, sensitivity = 1f),
                passthrough = PassthroughSettings(enabled = false, brightness = 0.3f),
            )

        assertEquals(ZyvoxSettings(), messed.resetAll())
    }

    // ─── Sensibilidade da mão ───────────────────────────────────────────────────────────

    @Test
    fun `mais sensibilidade significa limiar de confianca MENOR`() {
        // Eixo invertido, e é o ponto inteiro deste teste. O usuário pede "mais sensível"
        // esperando detectar MAIS mãos; no MediaPipe isso é baixar o limiar de confiança. Se o
        // mapeamento saísse direto, o slider faria o oposto do que o rótulo promete.
        val conservative = HandSettings(sensitivity = 0f).detectionConfidence
        val aggressive = HandSettings(sensitivity = 1f).detectionConfidence

        assertTrue(conservative > aggressive)
        assertEquals(HandSettings.DETECTION_CONFIDENCE_HIGH, conservative, 1e-6f)
        assertEquals(HandSettings.DETECTION_CONFIDENCE_LOW, aggressive, 1e-6f)
    }

    @Test
    fun `sensibilidade intermediaria fica entre os extremos`() {
        val mid = HandSettings(sensitivity = 0.5f).detectionConfidence

        assertTrue(mid > HandSettings.DETECTION_CONFIDENCE_LOW)
        assertTrue(mid < HandSettings.DETECTION_CONFIDENCE_HIGH)
    }

    @Test
    fun `sensibilidade fora da faixa nao produz limiar inutil`() {
        // Abaixo de ~0.2 o MediaPipe aceita ruído como mão e o cursor aparece sem mão nenhuma;
        // acima de ~0.6 descarta detecção legítima a distância de braço estendido.
        for (raw in floatArrayOf(-5f, 0f, 0.5f, 1f, 5f, Float.NaN)) {
            val confidence = HandSettings(sensitivity = raw).sanitized().detectionConfidence

            assertTrue(
                "sensibilidade $raw gerou limiar $confidence",
                confidence in HandSettings.DETECTION_CONFIDENCE_LOW..HandSettings.DETECTION_CONFIDENCE_HIGH,
            )
        }
    }

    @Test
    fun `limiar de deteccao nao sai da faixa nos extremos por arredondamento`() {
        // Regressão de float32, não de lógica: 0.60f - 1f * (0.60f - 0.20f) dá
        // 0.19999998807907104, que é MENOR que DETECTION_CONFIDENCE_LOW (0.20000000298023224).
        // A fórmula sozinha saía da faixa que a documentação dela promete, exatamente no extremo
        // que o slider alcança. Aqui o HandSettings NÃO é sanitizado de propósito: a garantia tem de
        // valer para quem lê a propriedade diretamente, não só depois de passar pelo sanitized().
        for (sensitivity in floatArrayOf(
            HandSettings.MIN_SENSITIVITY,
            HandSettings.MAX_SENSITIVITY,
            0f,
            1f,
        )) {
            val confidence = HandSettings(sensitivity = sensitivity).detectionConfidence

            assertTrue(
                "sensibilidade $sensitivity gerou limiar $confidence, fora de " +
                    "${HandSettings.DETECTION_CONFIDENCE_LOW}..${HandSettings.DETECTION_CONFIDENCE_HIGH}",
                confidence >= HandSettings.DETECTION_CONFIDENCE_LOW &&
                    confidence <= HandSettings.DETECTION_CONFIDENCE_HIGH,
            )
        }
    }

    @Test
    fun `sensibilidade NaN sem sanitizar cai no padrao em vez de virar NaN`() {
        // NaN.coerceIn(a, b) devolve NaN. Se isto chegasse ao MediaPipe como limiar de confiança,
        // toda detecção seria rejeitada e o tracking morreria sem exceção nenhuma no log.
        val confidence = HandSettings(sensitivity = Float.NaN).detectionConfidence

        assertTrue(confidence.isFinite())
        assertEquals(
            HandSettings(sensitivity = HandSettings.DEFAULT_SENSITIVITY).detectionConfidence,
            confidence,
            1e-6f,
        )
    }

    @Test
    fun `calibracao de cursor e limitada e sobrevive a NaN`() {
        val wild = HandSettings(
            calibrationX = 40f,
            calibrationY = Float.NaN,
        ).sanitized()

        assertEquals(HandSettings.MAX_CALIBRATION, wild.calibrationX, 0f)
        assertEquals(0f, wild.calibrationY, 0f)
    }

    // ─── VR Box ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `separacao dos olhos vem do IPD em metros`() {
        val vr = VrBoxSettings(ipdMm = 68f)

        assertEquals(0.068f, vr.eyeSeparationM, 1e-6f)
    }

    @Test
    fun `ipd fora da faixa humana e limitado`() {
        // Um IPD absurdo separaria as duas câmeras tanto que a cena não fundiria, causando
        // desconforto — e o usuário não saberia que a causa foi um valor gravado errado.
        assertEquals(VrBoxSettings.MIN_IPD_MM, VrBoxSettings(ipdMm = 1f).sanitized().ipdMm, 0f)
        assertEquals(VrBoxSettings.MAX_IPD_MM, VrBoxSettings(ipdMm = 999f).sanitized().ipdMm, 0f)
    }

    @Test
    fun `correcao de distorcao pode ser desligada e nunca passa de um`() {
        assertEquals(0f, VrBoxSettings(distortionK = -3f).sanitized().distortionK, 0f)
        assertEquals(1f, VrBoxSettings(distortionK = 3f).sanitized().distortionK, 0f)
        assertEquals(0f, VrBoxSettings(distortionK = 0f).sanitized().distortionK, 0f)
    }

    // ─── Passthrough ────────────────────────────────────────────────────────────────────

    @Test
    fun `compensacao de exposicao nula continua nula apos sanitizar`() {
        // `null` significa "não mexer na exposição do sensor". Trocar por 0 silenciosamente
        // forçaria uma exposição específica em aparelhos cuja faixa nem contém o zero.
        val untouched = PassthroughSettings(exposureCompensation = null).sanitized()

        assertNull(untouched.exposureCompensation)
        assertEquals(-3, PassthroughSettings(exposureCompensation = -3).sanitized().exposureCompensation)
    }

    @Test
    fun `brilho e limitado a uma faixa que ainda mostra imagem`() {
        // Brilho zero apagaria o passthrough inteiro, o que dentro de um VR Box é indistinguível
        // de "a câmera parou de funcionar".
        assertTrue(PassthroughSettings(brightness = 0f).sanitized().brightness > 0f)
        assertEquals(PassthroughSettings.MAX_BRIGHTNESS, PassthroughSettings(brightness = 99f).sanitized().brightness, 0f)
    }

    @Test
    fun `desligar o passthrough nao desliga o rastreamento de maos`() {
        // São controles independentes: o requisito pede para poder ativar e desativar o
        // passthrough, e também pede hand tracking. Um não pode implicar o outro.
        val settings = ZyvoxSettings().copy(
            passthrough = PassthroughSettings(enabled = false),
            hand = HandSettings(enabled = true),
        )

        assertFalse(settings.passthrough.enabled)
        assertTrue(settings.hand.enabled)
    }

    @Test
    fun `sanitizar a raiz sanitiza todos os sub-objetos`() {
        val corrupt = ZyvoxSettings(
            layout = InterfaceLayout(distanceM = Float.NaN, scale = 99f),
            vrBox = VrBoxSettings(ipdMm = 999f),
            hand = HandSettings(sensitivity = 99f),
            passthrough = PassthroughSettings(brightness = 99f),
        ).sanitized()

        assertEquals(InterfaceLayout.DEFAULT_DISTANCE_M, corrupt.layout.distanceM, 0f)
        assertEquals(InterfaceLayout.MAX_SCALE, corrupt.layout.scale, 0f)
        assertEquals(VrBoxSettings.MAX_IPD_MM, corrupt.vrBox.ipdMm, 0f)
        assertTrue(corrupt.hand.detectionConfidence >= HandSettings.DETECTION_CONFIDENCE_LOW)
        assertEquals(PassthroughSettings.MAX_BRIGHTNESS, corrupt.passthrough.brightness, 0f)
    }
}
