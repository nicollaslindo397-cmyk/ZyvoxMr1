package dev.zyvox.mr.spatial

import dev.zyvox.mr.settings.InterfaceLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Posição, orientação e escala de um elemento da interface no mundo, já em números prontos.
 *
 * ### Por que Floats soltos e não `Position`/`Quaternion`
 * `Position` e `Quaternion` vêm do SceneView (via kotlin-math), que é um AAR Android. Este cálculo
 * é a parte mais fácil de errar de forma invisível da interface regulável — um sinal trocado aqui
 * faz "aumentar altura" baixar a interface, e nada lança exceção. Manter a saída em tipos do JDK
 * deixa o cálculo testável em JVM, sem Robolectric e sem depender de classe de AAR carregar.
 *
 * A conversão para os tipos do SceneView acontece uma vez, na borda, em `DockPanelHost`.
 */
data class SpatialPlacement(
    val xM: Float,
    val yM: Float,
    val zM: Float,
    val quaternionX: Float,
    val quaternionY: Float,
    val quaternionZ: Float,
    val quaternionW: Float,
    val scale: Float,
)

/**
 * Converte [InterfaceLayout] em [SpatialPlacement].
 *
 * ### Convenção de coordenadas
 * A mesma do resto do app e do Filament: a câmera olha para **-Z**, **+Y** é para cima, **+X** é
 * para a direita de quem olha. Não é escolha arbitrária — `WindowPanelHost` e `DockPanelHost` já
 * posicionam com `-depthMeters` em Z, e divergir aqui faria a interface aparecer atrás da câmera,
 * onde `worldToScreen` devolve `null` e o painel simplesmente não é desenhado.
 *
 * ### Ordem do quaternion
 * kotlin-math usa `(x, y, z, w)`, enquanto `SensorManager.getQuaternionFromVector` devolve
 * `[w, x, y, z]`. Essa diferença já causou um erro neste projeto (ver `HeadPoseTracker`), então os
 * campos aqui têm o eixo no nome: `quaternionW` é `w`. Não há posição para trocar.
 */
object InterfaceTransform {

    /**
     * Colocação para uma interface que deve ficar de frente para a câmera, na distância pedida.
     *
     * @param layout configuração do usuário. **Não é sanitizada aqui** — quem chama sanitiza uma
     *   vez na leitura das preferências. Sanitizar a cada quadro custaria oito `coerceIn` por frame
     *   para proteger de um estado que já não pode estar inválido.
     */
    fun placementFor(layout: InterfaceLayout): SpatialPlacement {
        val tiltRad = Math.toRadians(layout.tiltDeg.toDouble()).toFloat()
        // Rotação pura em torno de X. Meio-ângulo, que é o que um quaternion representa: girar θ
        // é (sin(θ/2), cos(θ/2)). Usar θ inteiro aqui produziria o dobro da inclinação pedida — um
        // erro que o usuário lê como "o slider de inclinação é exagerado".
        val half = tiltRad / 2f
        return SpatialPlacement(
            xM = layout.offsetXM,
            yM = layout.heightM,
            zM = -layout.distanceM,
            quaternionX = sin(half),
            quaternionY = 0f,
            quaternionZ = 0f,
            quaternionW = cos(half),
            scale = layout.scale,
        )
    }

    /**
     * Separação lateral de cada olho em modo VR Box, em metros.
     *
     * Retorna o deslocamento de UM olho (metade do IPD), com sinal: esquerdo negativo, direito
     * positivo. Quem chama soma ao `xM` de cada elemento para obter a câmera daquele olho.
     *
     * ### Por que mover as câmeras e não duplicar a imagem
     * Duplicar a mesma imagem nos dois olhos produz estéreo zero — o cérebro funde duas vistas
     * idênticas e o resultado é plano, além de poder causar desconforto. O estéreo real vem de
     * duas câmeras separadas pelo IPD olhando para a mesma cena: cada olho vê os objetos com
     * disparidade proporcional à distância, que é exatamente o sinal de profundidade que o sistema
     * visual usa. É por isso que este modo só é honesto com cena **virtual**, onde há duas
     * projeções de verdade para calcular.
     */
    fun eyeOffsetM(eyeSeparationM: Float, isRightEye: Boolean): Float {
        if (!eyeSeparationM.isFinite() || eyeSeparationM <= 0f) return 0f
        val half = eyeSeparationM / 2f
        return if (isRightEye) half else -half
    }

    /**
     * Deslocamento do centro da lente, convertido de milímetros na carcaça para fração da
     * metade da tela.
     *
     * A conversão precisa da largura da metade em milímetros, que depende do aparelho: sem ela,
     * "4 mm de deslocamento de lente" não tem significado nenhum para o renderizador, que trabalha
     * em coordenadas normalizadas.
     *
     * @param halfScreenWidthMm largura física de metade da tela, em mm.
     * @return fração de `halfScreenWidthMm`, positiva para a direita.
     */
    fun lensCenterFraction(lensCenterOffsetMm: Float, halfScreenWidthMm: Float): Float {
        if (!halfScreenWidthMm.isFinite() || halfScreenWidthMm <= 0f) return 0f
        if (!lensCenterOffsetMm.isFinite()) return 0f
        return lensCenterOffsetMm / halfScreenWidthMm
    }

    /**
     * Raio de correção em barril para um ponto em coordenadas normalizadas de olho.
     *
     * Modelo simples e deliberadamente limitado a um coeficiente: `r' = r * (1 + k * r²)`. Um
     * modelo completo de Brown-Conrady (k1, k2, p1, p2, k3) exigiria calibrar a lente de cada
     * carcaça, e VR Box genérico não vem com esses dados — cinco parâmetros que o usuário teria de
     * chutar são piores que um parâmetro que ele consegue ajustar olhando.
     *
     * @param r distância normalizada do centro do olho, 0 no centro a ~0.707 no canto.
     * @param k intensidade, 0 desliga a correção.
     */
    fun distortionRadius(r: Float, k: Float): Float {
        if (!r.isFinite() || !k.isFinite()) return r
        val clampedR = abs(r).coerceAtMost(1.5f)
        return clampedR * (1f + k * clampedR * clampedR)
    }

    /**
     * Ângulo visual subtendido pela interface, em graus.
     *
     * Existe porque "a interface está grande?" não tem resposta sem a distância: 40 cm de largura a
     * 0,4 m preenchem muito mais visão que os mesmos 40 cm a 2 m. Este número é o que permite
     * comparar presets de forma objetiva e é a base dos testes que verificam se um preset distante
     * não ficou pequeno demais para ler.
     *
     * @param widthM largura física da interface no mundo.
     * @param distanceM distância até a câmera.
     */
    fun angularWidthDeg(widthM: Float, distanceM: Float): Float {
        if (!widthM.isFinite() || !distanceM.isFinite()) return 0f
        if (widthM <= 0f || distanceM <= 0f) return 0f
        // Ângulo total, não meio-ângulo: `2 * atan(w / 2d)`. Usar `atan(w/d)` aqui superestimaria
        // o campo em ~15% para as distâncias típicas, o que não quebraria nada visível mas faria
        // os limites dos testes não corresponderem à realidade.
        val halfAngle = Math.toDegrees(Math.atan((widthM / 2.0) / distanceM.toDouble()))
        return (2.0 * halfAngle).toFloat()
    }
}
