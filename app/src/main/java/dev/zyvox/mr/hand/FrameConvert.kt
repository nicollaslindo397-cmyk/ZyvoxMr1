package dev.zyvox.mr.hand

import android.graphics.ImageFormat
import android.media.Image
import kotlin.math.max
import kotlin.math.min

/**
 * Converte um `Image` YUV_420_888 do CameraX direto para RGB empacotado, **já na orientação
 * correta e em meia resolução**, pronto para o `ByteBufferImageBuilder(…, IMAGE_FORMAT_RGB)`
 * do MediaPipe.
 *
 * ### Por que não `Image.toBitmap()`
 * O caminho óbvio (`ImageProxy.toBitmap()` ou YUV→NV21→JPEG→Bitmap) faz um encode e um
 * decode JPEG por quadro. Isso custa caro o suficiente para congelar o preview várias vezes
 * por segundo — foi exatamente a causa do lag que o sample oficial do SceneView documenta
 * ter corrigido. Aqui a conversão é uma escrita direta no buffer de saída.
 *
 * ### Por que meia resolução é grátis
 * O 4:2:0 **já é** meia resolução em croma: cada pixel de saída lê uma amostra de luma e um
 * par de croma, sem filtragem nenhuma. E não perde nada que o modelo precise — a entrada do
 * Hand Landmarker é 224 px, muito abaixo da meia resolução de um sensor de celular.
 *
 * ### Por que rotacionar durante a escrita
 * Aplicar a rotação enquanto se escreve evita uma cópia intermediária rotacionada e evita
 * depender de "rotation hint" do modelo, que nem todos os delegates honram.
 *
 * ### Strides
 * Honra `rowStride` e `pixelStride` de cada plano. Assumir pixelStride 1 em U/V (o que o
 * caminho NV21 ingênuo faz) produz imagem com cores rasgadas na maioria dos aparelhos reais,
 * porque o formato mais comum entrelaça VU com pixelStride 2.
 */
object YuvRgbConverter {

    /**
     * @param y/u/v bytes de cada plano, já copiados para fora do `Image`.
     * @param rotationDegrees 0, 90, 180 ou 270 — rotação **horária** necessária para pôr a
     *   imagem em pé na orientação do display. Vem de `ImageInfo.getRotationDegrees()`.
     * @param out buffer de pelo menos `(rawWidth/2) * (rawHeight/2) * 3` bytes; reutilizado
     *   entre quadros para não alocar no caminho quente.
     * @return `Pair(largura, altura)` da imagem upright de saída.
     */
    @Suppress("LongParameterList")
    fun convert(
        y: ByteArray,
        yRowStride: Int,
        u: ByteArray,
        v: ByteArray,
        uvRowStride: Int,
        uvPixelStride: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        out: ByteArray,
    ): Pair<Int, Int> {
        val halfW = rawWidth / 2
        val halfH = rawHeight / 2
        // 90/270 trocam os eixos; 0/180 mantêm.
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val outW = if (swap) halfH else halfW
        val outH = if (swap) halfW else halfH
        require(out.size >= outW * outH * 3) {
            "buffer de saída pequeno demais: ${out.size} < ${outW * outH * 3}"
        }

        var o = 0
        for (j in 0 until outH) {
            for (i in 0 until outW) {
                // (i, j) na saída upright → (ru, rv) na meia resolução crua.
                //
                // Cada caso é a rotação inversa aplicada ao índice. Derivado uma vez e
                // fixado pelos testes unitários em YuvRgbConverterTest, porque errar aqui
                // produz uma imagem espelhada/de cabeça para baixo e o gesto passa a
                // selecionar o lado errado da tela — um bug fácil de introduzir e chato de
                // diagnosticar no aparelho.
                val ru: Int
                val rv: Int
                when (rotationDegrees) {
                    0 -> {
                        ru = i
                        rv = j
                    }
                    90 -> {
                        ru = j
                        rv = halfH - 1 - i
                    }
                    180 -> {
                        ru = halfW - 1 - i
                        rv = halfH - 1 - j
                    }
                    270 -> {
                        ru = halfW - 1 - j
                        rv = i
                    }
                    else -> {
                        ru = i
                        rv = j
                    }
                }

                val luma = (y[(rv * 2) * yRowStride + ru * 2].toInt() and 0xFF) - 16
                val c = rv * uvRowStride + ru * uvPixelStride
                val cb = (u[c].toInt() and 0xFF) - 128
                val cr = (v[c].toInt() and 0xFF) - 128

                // BT.601 faixa limitada, em ponto fixo (×1024). Inteiro em vez de float
                // porque isto roda duas vezes por pixel, ~500 mil vezes por quadro.
                val l = 1192 * max(luma, 0)
                out[o++] = clampByte((l + 1634 * cr) shr 10)
                out[o++] = clampByte((l - 833 * cr - 400 * cb) shr 10)
                out[o++] = clampByte((l + 2066 * cb) shr 10)
            }
        }
        return outW to outH
    }

    private fun clampByte(value: Int): Byte = min(max(value, 0), 255).toByte()
}

/**
 * Cópia barata dos três planos de um `Image` para arrays reutilizáveis.
 *
 * Roda na thread do analisador (não na de render) e é só `memcpy`: algumas centenas de KB.
 * O `Image` é fechado logo em seguida, então os bytes precisam sair dele aqui — segurar o
 * `Image` aberto bloqueia o buffer da câmera e derruba o preview.
 */
class PlaneScratch {
    var y: ByteArray = ByteArray(0)
        private set
    var u: ByteArray = ByteArray(0)
        private set
    var v: ByteArray = ByteArray(0)
        private set
    var yRowStride = 0
        private set
    var uvRowStride = 0
        private set
    var uvPixelStride = 1
        private set
    var width = 0
        private set
    var height = 0
        private set

    /** `false` se o formato não for YUV_420_888 (não temos o que fazer com ele). */
    fun copyFrom(image: Image): Boolean {
        if (image.format != ImageFormat.YUV_420_888) return false
        val planes = image.planes
        if (planes.size < 3) return false

        y = planes[0].buffer.copyInto(y)
        u = planes[1].buffer.copyInto(u)
        v = planes[2].buffer.copyInto(v)

        yRowStride = planes[0].rowStride
        uvRowStride = planes[1].rowStride
        // pixelStride do plano U. Em NV12 (o comum) é 2; em I420 é 1. Usar o do plano V
        // daria no mesmo, mas lemos um só para não assumir que são iguais sem verificar.
        uvPixelStride = planes[1].pixelStride.coerceAtLeast(1)
        width = image.width
        height = image.height
        return true
    }
}

/** Copia os bytes restantes do buffer em [reuse] quando couber, senão aloca um novo. */
private fun java.nio.ByteBuffer.copyInto(reuse: ByteArray): ByteArray {
    val size = remaining()
    val target = if (reuse.size >= size) reuse else ByteArray(size)
    get(target, 0, size)
    return target
}
