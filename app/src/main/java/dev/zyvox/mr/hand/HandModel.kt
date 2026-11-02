package dev.zyvox.mr.hand

/**
 * Índices dos 21 landmarks do MediaPipe Hand Landmarker.
 *
 * A numeração é fixa no modelo, não é escolha nossa. Centralizar aqui evita o
 * espalhamento de "magic numbers" como `landmarks[8]` pelo código de gesto — que é
 * exatamente o tipo de coisa que quebra em silêncio quando alguém troca o modelo.
 */
object HandJoint {
    const val WRIST = 0

    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4

    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8

    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_DIP = 11
    const val MIDDLE_TIP = 12

    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_DIP = 15
    const val RING_TIP = 16

    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_DIP = 19
    const val PINKY_TIP = 20

    const val COUNT = 21
}

/**
 * Um landmark em **coordenada de tela normalizada** (`0..1`, origem no canto superior
 * esquerdo da view).
 *
 * O `z` do MediaPipe é profundidade *relativa* ao punho, sem escala métrica. Mantemos o
 * valor porque ele ajuda a distinguir dedos dobrados de estendidos, mas nada no app
 * depende dele para posicionamento — ver [dev.zyvox.mr.spatial.CursorProjector] para como
 * a posição de mundo é obtida de verdade.
 */
data class HandPoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
)

/**
 * Uma mão rastreada, já em espaço de tela e já suavizada.
 *
 * @param slot Índice estável da mão (0 ou 1). Ver [HandSlotAssigner]: **não** é a
 *   lateralidade. O MediaPipe devolve `Left`/`Right` assumindo imagem espelhada
 *   (convenção de câmera frontal); na câmera traseira essa convenção se inverte, então
 *   depender do rótulo faria o cursor trocar de mão aleatoriamente. O slot é atribuído por
 *   posição na tela com histerese temporal, que é o que a interação realmente precisa.
 * @param viewX/viewY Posição do INDEX_TIP em pixels de view — é o que vira o cursor.
 * @param pinchRatio Distância polegar-indicador normalizada pelo tamanho da mão.
 *   Adimensional: funciona perto e longe da câmera.
 * @param isPinching Estado do gesto **com histerese aplicada**.
 * @param handScale Distância punho→MIDDLE_MCP em coordenada normalizada. Serve tanto
 *   como denominador do [pinchRatio] quanto como proxy grosseiro de proximidade.
 */
data class TrackedHand(
    val slot: Int,
    val handednessLabel: String,
    val handednessScore: Float,
    val points: List<HandPoint>,
    val viewX: Float,
    val viewY: Float,
    val pinchRatio: Float,
    val isPinching: Boolean,
    val handScale: Float,
    val trackingScore: Float,
) {
    fun point(index: Int): HandPoint? = points.getOrNull(index)
}

/**
 * Resultado de um quadro de inferência: até duas mãos.
 *
 * É imutável e publicado num `StateFlow`, então a UI pode ler o último valor a qualquer
 * momento sem se acoplar à cadência da inferência (que é menor que a do render, de
 * propósito — ver [HandTrackerPipeline]).
 */
data class HandFrame(
    val hands: List<TrackedHand>,
    /** `SystemClock.elapsedRealtimeNanos()` do quadro de câmera que originou isto. */
    val sourceTimeNs: Long,
    /** Latência ponta a ponta medida: quadro de câmera → resultado disponível. */
    val latencyMs: Float,
) {
    val handCount: Int get() = hands.size
    fun hand(slot: Int): TrackedHand? = hands.firstOrNull { it.slot == slot }

    companion object {
        val EMPTY = HandFrame(emptyList(), 0L, 0f)
    }
}
