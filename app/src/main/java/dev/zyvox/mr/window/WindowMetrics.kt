package dev.zyvox.mr.window

import kotlin.math.roundToInt

/**
 * Traduz entre os três espaços de coordenada que uma janela atravessa:
 * **metros no mundo** ↔ **pixels da view hospedeira** ↔ **fração do quad**.
 *
 * ### De onde vêm as relações
 * Do fonte do `ViewNode` (SceneView 4.65.0):
 *
 * - `updateGeometry(size = viewSize / pxPerUnits)` → o quad mede `viewPx / pxPerUnits`
 *   metros **antes** do `scale` do nó; o `scale` multiplica por cima.
 * - `viewTouchPixels` usa `u = (local.x - center.x)/size.x + 0.5` e
 *   `v = 0.5 - (local.y - center.y)/size.y`, com `size` = `viewSize/pxPerUnits` (sem scale).
 *   Ou seja: **v cresce para baixo** (como pixel de view) e o espaço local é dividido pelo
 *   `scale` do nó.
 *
 * Centralizar isso aqui é o que impede que o toque caia no lugar errado — o bug mais
 * provável deste app é um fator de escala esquecido, e ele se manifesta como "o cursor
 * aciona o botão ao lado", que é difícil de diagnosticar olhando código espalhado.
 *
 * ### Por que pixel fixo e não `wrap_content`
 * O `ViewNode.WindowManager` mede o conteúdo com `AT_MOST(display)`, então `fillMaxWidth()`
 * dentro dele resolve para a largura inteira do display e produz um quad de metros de largura.
 * E um `WindowManager` compartilhado se dimensiona pelo **maior** filho, arrastando todos os
 * outros painéis. Daí: tamanho em pixel explícito e fixo, um `WindowManager` por janela.
 */
object WindowMetrics {

    /** Largura do conteúdo em pixels. Fixa: é o que define a nitidez do texto na textura. */
    const val CONTENT_WIDTH_PX = 1280

    /** Altura do conteúdo em pixels, incluindo a barra de título. */
    const val CONTENT_HEIGHT_PX = 800

    /** Altura da barra de título em pixels. */
    const val TITLE_BAR_PX = 88

    /** Altura útil do WebView em pixels. */
    const val WEB_HEIGHT_PX = CONTENT_HEIGHT_PX - TITLE_BAR_PX

    /**
     * Largura da janela no mundo, em metros.
     *
     * Aponta para [MrWindow.DEFAULT_WIDTH] em vez de repetir o número. Eram dois `0.64f`
     * independentes em dois arquivos; quando a largura padrão subiu para 0,82 os dois teriam
     * divergido em silêncio, e [PX_PER_UNITS] — derivado daqui — passaria a descrever uma janela
     * que não existe mais. É o mesmo acoplamento que fez o dock encolher.
     */
    const val WINDOW_WIDTH_M = MrWindow.DEFAULT_WIDTH

    /** Altura da janela no mundo, em metros. Derivada, nunca um literal. */
    const val WINDOW_HEIGHT_M = MrWindow.DEFAULT_HEIGHT

    /**
     * `pxPerUnits` que faz o quad medir [WINDOW_WIDTH_M] × [WINDOW_HEIGHT_M] com
     * `scale = 1`.
     *
     * `pxPerUnits = viewPx / metros`. Os dois eixos dão o mesmo valor porque a proporção do
     * conteúdo (1280:800 = 16:10) é a mesma da janela (0.64:0.40 = 16:10) — de propósito,
     * para não haver distorção.
     */
    val PX_PER_UNITS: Float = CONTENT_WIDTH_PX / WINDOW_WIDTH_M

    /** Proporção do quad (largura/altura), usada para validar redimensionamento. */
    const val ASPECT: Float = CONTENT_WIDTH_PX.toFloat() / CONTENT_HEIGHT_PX

    /** Metros por pixel da view hospedeira. */
    const val METERS_PER_PX: Float = WINDOW_WIDTH_M / CONTENT_WIDTH_PX

    /**
     * Pixel `x` da view hospedeira, a partir de um deslocamento em metros desde o **centro**
     * da janela (eixo do mundo, +x para a direita).
     */
    fun worldOffsetXToPx(dxMeters: Float): Float =
        CONTENT_WIDTH_PX / 2f + dxMeters / METERS_PER_PX

    /**
     * Pixel `y` da view hospedeira, a partir de um deslocamento em metros desde o centro.
     *
     * O sinal é invertido: no mundo +y é para cima, em pixel de view +y é para baixo.
     */
    fun worldOffsetYToPx(dyMeters: Float): Float =
        CONTENT_HEIGHT_PX / 2f - dyMeters / METERS_PER_PX

    /**
     * Pixel **dentro do WebView**, a partir do pixel da view hospedeira.
     *
     * O WebView ocupa da barra de título para baixo, então só o y desloca.
     */
    fun hostToWebPx(hostX: Float, hostY: Float): Pair<Float, Float> =
        hostX to (hostY - TITLE_BAR_PX)

    /** `true` se o ponto (em pixel da view hospedeira) cai na barra de título. */
    fun isInTitleBar(hostX: Float, hostY: Float): Boolean =
        hostY < TITLE_BAR_PX && hostX >= 0f && hostX <= CONTENT_WIDTH_PX

    /** `true` se o ponto cai na alça de redimensionar (canto inferior direito). */
    fun isInResizeGrip(hostX: Float, hostY: Float): Boolean {
        val grip = GRIP_PX
        return hostX > CONTENT_WIDTH_PX - grip && hostY > CONTENT_HEIGHT_PX - grip
    }

    /** Lado da alça de redimensionar, em pixels. */
    const val GRIP_PX = 110

    /**
     * Largura em metros correspondente a uma janela redimensionada.
     *
     * Como o conteúdo em pixel é fixo, redimensionar muda `pxPerUnits` (e portanto quantos
     * metros cada pixel ocupa), não a contagem de pixels. Isso mantém o texto nítido em vez
     * de esticá-lo.
     */
    fun pxPerUnitsForWidth(widthMeters: Float): Float = CONTENT_WIDTH_PX / widthMeters

    /** Altura em metros implícita por uma largura, preservando a proporção. */
    fun heightForWidth(widthMeters: Float): Float = widthMeters / ASPECT

    /** Converte metros para pixel arredondado, para layout de View. */
    fun metersToPx(meters: Float): Int = (meters / METERS_PER_PX).roundToInt()
}
