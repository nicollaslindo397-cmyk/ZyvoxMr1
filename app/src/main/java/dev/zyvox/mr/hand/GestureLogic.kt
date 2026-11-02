package dev.zyvox.mr.hand

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lógica de gesto e suavização — **Kotlin puro, sem Android**, para ser testável em JVM.
 *
 * Tudo aqui é função determinística sobre landmarks. Nenhum `Context`, nenhum `Frame`.
 * Isso é deliberado: é a parte do app com mais chance de precisar de ajuste fino em
 * aparelho real, e poder rodar os números num teste unitário é o que torna esse ajuste
 * viável sem um celular na mão.
 */

/**
 * Distância polegar↔indicador normalizada pelo tamanho da mão.
 *
 * Normalizar é o que faz o gesto funcionar tanto com a mão a 20 cm quanto a 80 cm da
 * câmera: sem isso, afastar a mão aumentaria a distância absoluta dos dedos e o pinch se
 * desfaria sozinho. O denominador é punho→MIDDLE_MCP (a "palma"), que é a medida mais
 * estável da mão — os dedos se movem, o comprimento da palma não.
 *
 * @return razão adimensional; `null` quando a palma é pequena demais para confiar
 *   (mão longe ou parcialmente fora de quadro), o que evita divisão por ~zero disparar
 *   pinch falso.
 */
fun pinchRatio(points: List<HandPoint>): Float? {
    val thumbTip = points.getOrNull(HandJoint.THUMB_TIP) ?: return null
    val indexTip = points.getOrNull(HandJoint.INDEX_TIP) ?: return null
    val wrist = points.getOrNull(HandJoint.WRIST) ?: return null
    val middleMcp = points.getOrNull(HandJoint.MIDDLE_MCP) ?: return null

    val palm = hypot(middleMcp.x - wrist.x, middleMcp.y - wrist.y)
    if (palm < MIN_PALM) return null

    val pinch = hypot(indexTip.x - thumbTip.x, indexTip.y - thumbTip.y)
    return (pinch / palm).coerceIn(0f, 4f)
}

/** Palma mínima, em coordenada normalizada, para o gesto ser considerado confiável. */
const val MIN_PALM = 0.012f

/**
 * Histerese de pinch: dois limiares em vez de um.
 *
 * Um limiar único faz o gesto "tremer" — a razão oscila em torno dele e o estado liga e
 * desliga várias vezes por segundo, o que na UI aparece como seleção fantasma e objetos
 * sendo soltos sem querer. Com dois limiares, ativar exige fechar bem o gesto e soltar
 * exige abrir bem; entre eles o estado anterior é mantido.
 *
 * Os valores vêm da distribuição típica da razão: mão aberta fica em ~0.9–1.3, pinch
 * fechado em ~0.2–0.4.
 */
class PinchDetector(
    private val activateBelow: Float = 0.45f,
    private val deactivateAbove: Float = 0.68f,
    private val activateFrames: Int = 2,
) {
    private var pinching = false
    private var pendingFrames = 0

    /** Estado atual, para a UI desenhar o cursor sem esperar o próximo quadro. */
    val isPinching: Boolean get() = pinching

    /**
     * @param ratio Razão de pinch, ou `null` quando não é confiável neste quadro. Um `null`
     *   **não** cancela o gesto: perder um quadro de detecção é comum (oclusão momentânea)
     *   e soltar o objeto que o usuário está arrastando por causa disso é pior que segurar
     *   a decisão por 30 ms.
     */
    fun update(ratio: Float?): Boolean {
        if (ratio == null) return pinching
        if (pinching) {
            if (ratio > deactivateAbove) {
                pinching = false
                pendingFrames = 0
            }
        } else {
            // Exigir N quadros consecutivos filtra o pico de um frame ruidoso, que é o que
            // mais causa "selecionou sozinho".
            pendingFrames = if (ratio < activateBelow) pendingFrames + 1 else 0
            if (pendingFrames >= activateFrames) {
                pinching = true
                pendingFrames = 0
            }
        }
        return pinching
    }

    fun reset() {
        pinching = false
        pendingFrames = 0
    }
}

/**
 * Suavização exponencial com alpha **adaptativo**.
 *
 * Um filtro de alpha fixo obriga a escolher entre dois males: alpha baixo remove o
 * tremor mas atrasa o cursor (a mão já chegou e o cursor ainda está no caminho — isso se
 * sente como lag), alpha alto responde rápido mas repassa o jitter do modelo.
 *
 * Aqui o alpha cresce com a velocidade: quando a mão se move rápido, o filtro quase some e
 * o cursor acompanha; quando a mão está quase parada (que é quando o jitter aparece e
 * quando o usuário está tentando mirar em algo pequeno), o filtro atua com força.
 *
 * Estado por mão, mantido pelo dono — ver [HandSlotAssigner].
 */
class LandmarkSmoother(
    private val minAlpha: Float = 0.18f,
    private val maxAlpha: Float = 0.85f,
    /** Deslocamento (em fração da tela) por quadro a partir do qual o filtro sai do caminho. */
    private val fullSpeedAt: Float = 0.035f,
) {
    private var x = Float.NaN
    private var y = Float.NaN
    private var initialized = false

    /** Posição suavizada atual, ou `null` antes do primeiro quadro. */
    val current: Pair<Float, Float>? get() = if (initialized) x to y else null

    fun update(rawX: Float, rawY: Float): Pair<Float, Float> {
        if (!initialized || x.isNaN()) {
            // Primeiro quadro: adota a posição em vez de suavizar a partir de (0,0), senão
            // o cursor atravessa a tela inteira na primeira detecção.
            x = rawX
            y = rawY
            initialized = true
            return x to y
        }
        val delta = hypot(rawX - x, rawY - y)
        val t = min(delta / fullSpeedAt, 1f)
        val alpha = minAlpha + (maxAlpha - minAlpha) * t
        x += (rawX - x) * alpha
        y += (rawY - y) * alpha
        return x to y
    }

    fun reset() {
        initialized = false
        x = Float.NaN
        y = Float.NaN
    }
}

/**
 * Centro da mão em coordenada normalizada — usado para desenhar o esqueleto e para decidir
 * qual mão está mais próxima de uma janela.
 */
fun palmCenter(points: List<HandPoint>): HandPoint? {
    val indices = intArrayOf(
        HandJoint.WRIST,
        HandJoint.INDEX_MCP,
        HandJoint.MIDDLE_MCP,
        HandJoint.RING_MCP,
        HandJoint.PINKY_MCP,
    )
    var sx = 0f
    var sy = 0f
    var n = 0
    for (i in indices) {
        val p = points.getOrNull(i) ?: continue
        sx += p.x
        sy += p.y
        n++
    }
    return if (n == 0) null else HandPoint(sx / n, sy / n)
}

/** Topologia de ossos da mão, para desenhar o esqueleto de debug/feedback visual. */
val HAND_BONES: List<Pair<Int, Int>> = listOf(
    HandJoint.WRIST to HandJoint.THUMB_CMC,
    HandJoint.THUMB_CMC to HandJoint.THUMB_MCP,
    HandJoint.THUMB_MCP to HandJoint.THUMB_IP,
    HandJoint.THUMB_IP to HandJoint.THUMB_TIP,

    HandJoint.WRIST to HandJoint.INDEX_MCP,
    HandJoint.INDEX_MCP to HandJoint.INDEX_PIP,
    HandJoint.INDEX_PIP to HandJoint.INDEX_DIP,
    HandJoint.INDEX_DIP to HandJoint.INDEX_TIP,

    HandJoint.INDEX_MCP to HandJoint.MIDDLE_MCP,
    HandJoint.MIDDLE_MCP to HandJoint.MIDDLE_PIP,
    HandJoint.MIDDLE_PIP to HandJoint.MIDDLE_DIP,
    HandJoint.MIDDLE_DIP to HandJoint.MIDDLE_TIP,

    HandJoint.MIDDLE_MCP to HandJoint.RING_MCP,
    HandJoint.RING_MCP to HandJoint.RING_PIP,
    HandJoint.RING_PIP to HandJoint.RING_DIP,
    HandJoint.RING_DIP to HandJoint.RING_TIP,

    HandJoint.RING_MCP to HandJoint.PINKY_MCP,
    HandJoint.PINKY_MCP to HandJoint.PINKY_PIP,
    HandJoint.PINKY_PIP to HandJoint.PINKY_DIP,
    HandJoint.PINKY_DIP to HandJoint.PINKY_TIP,

    HandJoint.WRIST to HandJoint.PINKY_MCP,
)

/**
 * Ângulo de dobra de um dedo, em graus. 180° = estendido, ~30° = dobrado.
 * Usado para detectar "mão aberta" (mostrar o dock) vs "mão fechada".
 */
fun fingerExtension(points: List<HandPoint>, mcp: Int, pip: Int, tip: Int): Float {
    val a = points.getOrNull(mcp) ?: return 180f
    val b = points.getOrNull(pip) ?: return 180f
    val c = points.getOrNull(tip) ?: return 180f
    val v1x = b.x - a.x
    val v1y = b.y - a.y
    val v2x = c.x - b.x
    val v2y = c.y - b.y
    val n1 = hypot(v1x, v1y)
    val n2 = hypot(v2x, v2y)
    if (n1 < 1e-6f || n2 < 1e-6f) return 180f
    val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
    return Math.toDegrees(Math.acos(cos.toDouble())).toFloat()
}

/** `true` quando a palma está acima do mínimo e a mão ocupa área razoável do quadro. */
fun isHandUsable(points: List<HandPoint>): Boolean {
    if (points.size < HandJoint.COUNT) return false
    val wrist = points[HandJoint.WRIST]
    val middle = points[HandJoint.MIDDLE_MCP]
    val palm = hypot(middle.x - wrist.x, middle.y - wrist.y)
    if (palm < MIN_PALM) return false
    // Rejeita mão quase totalmente fora do quadro: landmarks extrapolados pelo modelo
    // nesses casos são instáveis e fariam o cursor voar.
    return points.all { it.x > -0.25f && it.x < 1.25f && it.y > -0.25f && it.y < 1.25f }
}

/** Utilitário de clamp para não repetir `max/min` aninhado. */
fun clampF(value: Float, low: Float, high: Float): Float = max(low, min(high, value))

/** Distância absoluta entre dois pontos — usada nos testes e na atribuição de slot. */
fun distance(a: HandPoint, b: HandPoint): Float = hypot(a.x - b.x, a.y - b.y)
