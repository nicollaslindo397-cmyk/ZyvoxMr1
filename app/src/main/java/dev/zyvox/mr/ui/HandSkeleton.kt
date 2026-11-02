package dev.zyvox.mr.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import dev.zyvox.mr.MrViewModel
import dev.zyvox.mr.hand.HAND_BONES
import dev.zyvox.mr.hand.HandJoint
import dev.zyvox.mr.hand.TrackedHand
import dev.zyvox.mr.spatial.ScreenMapper

/**
 * Esqueleto da mão desenhado sobre o passthrough: as "cordinhas" brilhantes ligando as
 * articulações, no estilo do hand tracking do Quest.
 *
 * ### Por que overlay 2D e não geometria 3D
 * A mão que o usuário vê **está no vídeo da câmera**. Desenhar o esqueleto no mesmo espaço de
 * tela é o que o mantém colado à mão. Como objeto 3D no plano de interação, a mão real e o
 * esqueleto ficariam em profundidades diferentes e o efeito leria como um boneco flutuando
 * *perto* da mão, não como a mão brilhando.
 *
 * O custo também decide: projetar um ponto de tela para o mundo exige uma inversão de matriz
 * 4×4, e o cursor já paga duas por frame (ver [MrScreen]). Para 21 landmarks × 2 mãos seriam 42
 * inversões por frame, num orçamento de 16,6 ms que já é apertado com WebView e Filament juntos.
 *
 * ### Por que `android.graphics.Paint` e não `androidx.compose.ui.graphics.Paint`
 * `Paint.asFrameworkPaint()` do Compose devolve um objeto **novo** a cada chamada. Configurar um
 * `BlurMaskFilter` num `Paint` do Compose e depois passar `asFrameworkPaint()` ao `drawLine`
 * desenha com um paint sem filtro nenhum — o brilho simplesmente não aparece, sem erro e sem
 * aviso. Aqui os três `Paint` nativos são criados uma vez, retidos e mutados por frame, que é
 * também o que evita alocar no caminho de render.
 *
 * ### Por que isto também é diagnóstico
 * O esqueleto só aparece quando o MediaPipe devolve landmarks. Se o tracking estiver funcionando
 * e o cursor não, o esqueleto aparece e o problema é a projeção. Se não aparecer, o problema é a
 * detecção. Isso fica visível na tela, sem precisar de `adb logcat`.
 */
@Composable
fun HandSkeleton(
    viewModel: MrViewModel,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
) {
    // `handCount` é observável só no sentido de que o bloco relê a cada recomposição causada por
    // outra coisa; quem realmente invalida o desenho é o contador de quadros abaixo.
    val hasHand = viewModel.latestHandFrame.handCount > 0
    val density = LocalDensity.current

    var frameTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hasHand) {
        if (!hasHand) return@LaunchedEffect
        // `latestHandFrame` é `@Volatile` e não observável (de propósito: ver a KDoc de
        // [MrViewModel]). Sem este loop o Canvas desenharia uma vez e congelaria na primeira
        // posição da mão. Incrementar um Int por quadro é o mínimo que invalida o desenho sem
        // recompor a árvore — o estado publicado é um número, não a cena.
        while (true) {
            withFrameNanos { frameTick++ }
        }
    }

    if (!hasHand || imageWidth <= 0 || imageHeight <= 0) return

    val glow = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            // Explícito de propósito: um Paint STROKE sem `strokeWidth` usa 0, que o Android
            // interpreta como "hairline" de ~1 px independente da densidade. O halo sumiria.
            strokeWidth = GLOW_STROKE_DP
            // O brilho de verdade: um traço largo e desfocado por baixo do núcleo fino. É o que
            // faz a linha ler como emissiva em vez de risco de giz — e importa ainda mais aqui
            // porque o fundo é o passthrough, que pode ser claro.
            maskFilter = BlurMaskFilter(GLOW_BLUR_PX, BlurMaskFilter.Blur.NORMAL)
        }
    }
    val core = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = CORE_STROKE_DP
            color = CORE_COLOR
        }
    }
    val joint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }

    Canvas(modifier = modifier.fillMaxSize()) {
        // O Canvas do Compose mede em dp; os landmarks mapeados estão em pixel de dispositivo.
        val pxPerDp = density.density
        val viewW = size.width * pxPerDp
        val viewH = size.height * pxPerDp
        // Ler o tick aqui é o que vincula o desenho ao estado que muda por quadro.
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (hand in viewModel.latestHandFrame.hands) {
                if (hand.points.size < HandJoint.COUNT) continue
                val pts = mapLandmarks(hand, imageWidth, imageHeight, viewW, viewH, pxPerDp)
                    ?: continue

                val accent = if (hand.isPinching) PINCH_COLOR else IDLE_COLOR
                glow.color = accent.copy(alpha = GLOW_ALPHA).toArgb()
                joint.color = accent.toArgb()

                for ((a, b) in HAND_BONES) {
                    val from = pts[a] ?: continue
                    val to = pts[b] ?: continue
                    // Duas passadas: halo saturado embaixo, núcleo quase branco em cima. É isso
                    // que dá a leitura de "cordinha brilhando" em vez de uma linha colorida.
                    native.drawLine(from.x, from.y, to.x, to.y, glow)
                    native.drawLine(from.x, from.y, to.x, to.y, core)
                }

                // Articulações. Sem elas as linhas se encontram em vértices visivelmente finos e
                // o desenho perde a leitura de "mão".
                for (p in pts) {
                    if (p != null) native.drawCircle(p.x, p.y, JOINT_RADIUS_DP, joint)
                }

                // O pinch ganha um anel entre polegar e indicador: confirma que o gesto foi
                // reconhecido exatamente onde o usuário está olhando.
                if (hand.isPinching) {
                    val thumb = pts[HandJoint.THUMB_TIP]
                    val index = pts[HandJoint.INDEX_TIP]
                    if (thumb != null && index != null) {
                        native.drawCircle(
                            (thumb.x + index.x) / 2f,
                            (thumb.y + index.y) / 2f,
                            PINCH_RING_DP,
                            glow,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Landmarks normalizados da imagem → [Offset] em **dp** dentro do Canvas.
 *
 * Usa o mesmo [ScreenMapper.imageToView] e as mesmas dimensões upright que o cursor usa. Não é
 * comodidade: se o esqueleto e o cursor fossem mapeados por caminhos diferentes eles
 * divergiriam, e o usuário veria o cursor fora da mão que está brilhando.
 *
 * Devolve `null` quando o mapeamento não é possível, para o chamador pular o quadro em vez de
 * desenhar um esqueleto colapsado na origem.
 */
private fun mapLandmarks(
    hand: TrackedHand,
    imageWidth: Int,
    imageHeight: Int,
    viewWidthPx: Float,
    viewHeightPx: Float,
    pxPerDp: Float,
): List<Offset?>? {
    if (viewWidthPx <= 0f || viewHeightPx <= 0f || pxPerDp <= 0f) return null
    return hand.points.map { point ->
        val (sx, sy) = ScreenMapper.imageToView(
            nx = point.x,
            ny = point.y,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            viewWidth = viewWidthPx.toInt(),
            viewHeight = viewHeightPx.toInt(),
        )
        Offset(sx / pxPerDp, sy / pxPerDp)
    }
}

// Cores do Zyvox (ver [MrTokens]): ciano no repouso, verde no gesto — as mesmas do cursor, para
// que "mão rastreada" e "pinch ativo" tenham uma cor só no app inteiro.
private val IDLE_COLOR get() = MrTokens.Accent
private val PINCH_COLOR get() = MrTokens.Success
private const val CORE_COLOR = 0xE6FFFFFF.toInt()

private const val GLOW_STROKE_DP = 7f
private const val CORE_STROKE_DP = 2.2f
private const val GLOW_ALPHA = 0.55f
private const val GLOW_BLUR_PX = 14f
private const val JOINT_RADIUS_DP = 3.2f
private const val PINCH_RING_DP = 16f
