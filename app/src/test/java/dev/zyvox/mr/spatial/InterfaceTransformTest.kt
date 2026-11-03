package dev.zyvox.mr.spatial

import dev.zyvox.mr.settings.InterfaceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Testes do cálculo de posicionamento da interface.
 *
 * É a parte do requisito "interface regulável" mais fácil de errar sem que nada avise: um sinal
 * trocado aqui faz "aumentar altura" baixar a interface, e "distância" colocar o painel atrás da
 * câmera — onde ele simplesmente não é desenhado. Nenhum dos dois lança exceção.
 */
class InterfaceTransformTest {

    @Test
    fun `distancia coloca a interface a frente da camera em Z negativo`() {
        // A câmera olha para -Z. Se isto sair positivo, o painel fica atrás do olho e
        // `worldToScreen` devolve null — a interface some sem erro nenhum no log.
        val placement = InterfaceTransform.placementFor(InterfaceLayout(distanceM = 1.2f))

        assertEquals(-1.2f, placement.zM, 1e-6f)
    }

    @Test
    fun `altura e deslocamento horizontal mapeiam direto para Y e X`() {
        val placement = InterfaceTransform.placementFor(
            InterfaceLayout(heightM = 0.25f, offsetXM = -0.4f)
        )

        assertEquals(0.25f, placement.yM, 1e-6f)
        assertEquals(-0.4f, placement.xM, 1e-6f)
    }

    @Test
    fun `inclinacao zero produz quaternion identidade`() {
        val placement = InterfaceTransform.placementFor(InterfaceLayout(tiltDeg = 0f))

        assertEquals(0f, placement.quaternionX, 1e-6f)
        assertEquals(0f, placement.quaternionY, 1e-6f)
        assertEquals(0f, placement.quaternionZ, 1e-6f)
        assertEquals(1f, placement.quaternionW, 1e-6f)
    }

    @Test
    fun `inclinacao usa meio-angulo e nao o angulo inteiro`() {
        // Um quaternion representa a rotação pelo MEIO ângulo. Passar o ângulo cheio dobraria a
        // inclinação, e o usuário leria isso como "o slider de inclinação é exagerado demais".
        val degrees = 40f
        val half = Math.toRadians(degrees.toDouble()).toFloat() / 2f

        val placement = InterfaceTransform.placementFor(InterfaceLayout(tiltDeg = degrees))

        assertEquals(sin(half), placement.quaternionX, 1e-6f)
        assertEquals(cos(half), placement.quaternionW, 1e-6f)
        // Eixo puro de X: y e z ficam em zero, senão a interface também rodaria de lado.
        assertEquals(0f, placement.quaternionY, 1e-6f)
        assertEquals(0f, placement.quaternionZ, 1e-6f)
    }

    @Test
    fun `quaternion de saida esta normalizado em qualquer inclinacao`() {
        // Um quaternion não-normalizado entra na matriz de transformação e produz escala espúria:
        // a interface inclinada apareceria também maior ou menor, misturando dois controles.
        for (deg in -45..45 step 5) {
            val p = InterfaceTransform.placementFor(InterfaceLayout(tiltDeg = deg.toFloat()))
            val norm = p.quaternionX * p.quaternionX +
                p.quaternionY * p.quaternionY +
                p.quaternionZ * p.quaternionZ +
                p.quaternionW * p.quaternionW
            assertEquals("inclinação $deg", 1.0f, norm, 1e-5f)
        }
    }

    @Test
    fun `escala passa adiante sem alteracao`() {
        val placement = InterfaceTransform.placementFor(InterfaceLayout(scale = 1.75f))

        assertEquals(1.75f, placement.scale, 1e-6f)
    }

    // ─── Estéreo VR Box ─────────────────────────────────────────────────────────────────

    @Test
    fun `olhos ficam simetricos em torno do centro e separados pelo IPD`() {
        val ipdM = 0.064f
        val left = InterfaceTransform.eyeOffsetM(ipdM, isRightEye = false)
        val right = InterfaceTransform.eyeOffsetM(ipdM, isRightEye = true)

        assertEquals(-ipdM / 2f, left, 1e-6f)
        assertEquals(ipdM / 2f, right, 1e-6f)
        // A distância ENTRE as duas câmeras tem de ser o IPD, que é o que torna a disparidade
        // fisicamente correta. Metade para cada lado somando o IPD inteiro.
        assertEquals(ipdM, right - left, 1e-6f)
    }

    @Test
    fun `separacao invalida devolve zero em vez de NaN`() {
        // IPD zerado ou negativo viria de preferência corrompida; devolver NaN aqui propagaria
        // para a posição de todos os painéis e a cena inteira sumiria.
        assertEquals(0f, InterfaceTransform.eyeOffsetM(0f, isRightEye = true), 0f)
        assertEquals(0f, InterfaceTransform.eyeOffsetM(-0.06f, isRightEye = true), 0f)
        assertEquals(0f, InterfaceTransform.eyeOffsetM(Float.NaN, isRightEye = false), 0f)
    }

    @Test
    fun `centro da lente converte de milimetros para fracao da meia tela`() {
        // Sem a largura física da metade da tela, "4 mm de deslocamento" não significa nada para
        // um renderizador que trabalha em coordenadas normalizadas.
        val fraction = InterfaceTransform.lensCenterFraction(4f, 40f)

        assertEquals(0.1f, fraction, 1e-6f)
        assertEquals(0f, InterfaceTransform.lensCenterFraction(4f, 0f), 0f)
        assertEquals(0f, InterfaceTransform.lensCenterFraction(Float.NaN, 40f), 0f)
    }

    // ─── Distorção ──────────────────────────────────────────────────────────────────────

    @Test
    fun `correcao desligada devolve o proprio raio`() {
        for (r in floatArrayOf(0f, 0.1f, 0.5f, 0.707f)) {
            assertEquals("r=$r", r, InterfaceTransform.distortionRadius(r, 0f), 1e-6f)
        }
    }

    @Test
    fun `correcao aumenta o raio e cresce mais rapido na borda`() {
        // É o comportamento de barril: quase nada no centro, muito na periferia. Se fosse linear,
        // a imagem ficaria apenas ampliada e a distorção da lente continuaria visível.
        val center = InterfaceTransform.distortionRadius(0.1f, 0.5f)
        val edge = InterfaceTransform.distortionRadius(0.7f, 0.5f)

        assertTrue("centro deveria quase não mudar", abs(center - 0.1f) < 0.01f)
        assertTrue("borda deveria crescer bastante", edge > 0.7f * 1.1f)
    }

    @Test
    fun `raio absurdo e limitado em vez de explodir`() {
        val huge = InterfaceTransform.distortionRadius(1e6f, 0.5f)

        assertTrue(huge.isFinite())
        assertTrue(huge < 10f)
    }

    // ─── Ângulo visual ──────────────────────────────────────────────────────────────────

    @Test
    fun `angulo visual diminui com a distancia`() {
        // É por isto que os presets ajustam escala junto com distância: "está grande?" não tem
        // resposta sem saber a distância.
        val near = InterfaceTransform.angularWidthDeg(0.4f, 0.5f)
        val far = InterfaceTransform.angularWidthDeg(0.4f, 2.0f)

        assertTrue(near > far)
        assertTrue(near > 0f && far > 0f)
    }

    @Test
    fun `angulo visual bate com a trigonometria esperada`() {
        // 40 cm de largura a 80 cm: 2 * atan(0.2 / 0.8) ≈ 28.07°.
        val degrees = InterfaceTransform.angularWidthDeg(0.4f, 0.8f)

        assertEquals(28.07f, degrees, 0.05f)
    }

    @Test
    fun `angulo visual devolve zero para entrada degenerada`() {
        assertEquals(0f, InterfaceTransform.angularWidthDeg(0f, 1f), 0f)
        assertEquals(0f, InterfaceTransform.angularWidthDeg(1f, 0f), 0f)
        assertEquals(0f, InterfaceTransform.angularWidthDeg(Float.NaN, 1f), 0f)
    }
}
