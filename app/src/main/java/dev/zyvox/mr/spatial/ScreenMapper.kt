package dev.zyvox.mr.spatial

import kotlin.math.max

/**
 * Converte coordenada normalizada da **imagem upright da câmera** em pixel da **view**.
 *
 * ### O problema que isto resolve
 * O MediaPipe devolve landmarks normalizados na imagem que entregamos a ele. Mas o
 * `PreviewView` mostra essa imagem em `ScaleType.FILL_CENTER` (aspect-fill): ele amplia até
 * cobrir a view e **corta** o que sobra. Então `x = 0.5` na imagem não é `x = 0.5` na tela —
 * e ignorar isso desloca o cursor para longe do dedo, com o erro crescendo quanto mais a
 * proporção da câmera difere da proporção da tela.
 *
 * ### O mapeamento
 * Para cada eixo:
 * - `scale = max(viewW / imgW, viewH / imgH)` — o aspect-fill escolhe o maior.
 * - O eixo que "sobra" é o que foi cortado. Nele, a fração visível da imagem é
 *   `view / (img * scale)`, centrada.
 * - `viewNorm = (imgNorm - (1 - visibleFraction) / 2) / visibleFraction`
 *
 * No eixo que não foi cortado, `visibleFraction = 1` e a fórmula degenera para identidade —
 * o que é o comportamento correto, não um caso especial.
 *
 * Sem dependência de Android: testável em JVM, e é a função com maior chance de estar
 * sutilmente errada em todo o app. Ver `ScreenMapperTest`.
 */
object ScreenMapper {

    /**
     * @param nx coordenada x normalizada na imagem upright (`0..1`).
     * @param ny coordenada y normalizada na imagem upright (`0..1`).
     * @param imageWidth/imageHeight dimensões da imagem **após** a rotação para upright —
     *   ou seja, o `(w, h)` devolvido por [dev.zyvox.mr.hand.YuvRgbConverter.convert].
     * @param viewWidth/viewHeight dimensões da view em pixels.
     * @return `Pair(x, y)` em pixels de view. Pode sair de `0..view` se a mão está na parte
     *   cortada da imagem — e isso é desejável: o cursor deve continuar "fora da tela"
     *   em vez de grudar na borda.
     */
    fun imageToView(
        nx: Float,
        ny: Float,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Pair<Float, Float> {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return 0f to 0f
        }
        val scale = max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val scaledW = imageWidth * scale
        val scaledH = imageHeight * scale
        // Fração da imagem visível em cada eixo; 1.0 no eixo que não foi cortado.
        val visibleX = (viewWidth / scaledW).coerceIn(0f, 1f)
        val visibleY = (viewHeight / scaledH).coerceIn(0f, 1f)

        val viewNx = (nx - (1f - visibleX) / 2f) / max(visibleX, 1e-6f)
        val viewNy = (ny - (1f - visibleY) / 2f) / max(visibleY, 1e-6f)
        return viewNx * viewWidth to viewNy * viewHeight
    }

    /** Inverso de [imageToView]: pixel de view → normalizado na imagem upright. */
    fun viewToImage(
        viewX: Float,
        viewY: Float,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Pair<Float, Float> {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return 0f to 0f
        }
        val scale = max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val visibleX = (viewWidth / (imageWidth * scale)).coerceIn(0f, 1f)
        val visibleY = (viewHeight / (imageHeight * scale)).coerceIn(0f, 1f)

        val nx = viewX / viewWidth
        val ny = viewY / viewHeight
        return (nx * visibleX + (1f - visibleX) / 2f) to (ny * visibleY + (1f - visibleY) / 2f)
    }

    /**
     * Proporção da imagem que está visível na view, em cada eixo. Útil para o HUD de debug
     * e para decidir se vale a pena trocar o `ScaleType`.
     */
    fun visibleFractions(
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Pair<Float, Float> {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1f to 1f
        val scale = max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        return (viewWidth / (imageWidth * scale)).coerceIn(0f, 1f) to
            (viewHeight / (imageHeight * scale)).coerceIn(0f, 1f)
    }
}
