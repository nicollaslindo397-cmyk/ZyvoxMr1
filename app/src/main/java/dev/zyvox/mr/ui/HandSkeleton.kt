package dev.zyvox.mr.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import kotlin.math.sqrt

/**
 * Esqueleto da mão desenhado sobre o fundo: as "cordinhas" brilhantes ligando as articulações,
 * com a palma preenchida, no estilo do hand tracking do Quest.
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
 * ### O que faz parecer o Quest e não um arame
 * Três coisas, e a ausência de qualquer uma delas já quebra a leitura:
 *
 * 1. **Palma preenchida.** É a diferença maior. Só linhas desenha um esqueleto; uma superfície
 *    translúcida entre pulso e nós dos dedos desenha uma *mão*. O Quest mostra volume, não topologia.
 * 2. **Ossos que afinam.** Largos na base, estreitos na ponta. Um traço de largura constante faz
 *    os cinco dedos parecerem palitos iguais, e a mão perde a hierarquia que o olho usa para
 *    reconhecer dedos.
 * 3. **Mistura aditiva** ([PorterDuff.Mode.ADD]) nas partes luminosas. `SRC_OVER` pinta por cima;
 *    `ADD` soma luz. É o que separa "tinta ciano" de "luz ciano" — e importa mais aqui porque o
 *    fundo é o passthrough, que pode ser claro.
 *
 * ### Por que `android.graphics.Paint` e não `androidx.compose.ui.graphics.Paint`
 * `Paint.asFrameworkPaint()` do Compose devolve um objeto **novo** a cada chamada. Configurar um
 * `BlurMaskFilter` num `Paint` do Compose e depois passar `asFrameworkPaint()` ao `drawLine`
 * desenha com um paint sem filtro nenhum — o brilho simplesmente não aparece, sem erro e sem
 * aviso. Aqui os `Paint` nativos são criados uma vez, retidos e mutados por frame, que é também
 * o que evita alocar no caminho de render.
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

    val paints = remember { SkeletonPaints() }
    // `Path` e o vetor de 8 floats são reutilizados, nunca alocados no laço. `Path.reset()` é
    // barato; alocar um `Path` por osso (21 por mão, 2 mãos, 60 fps) são ~2500 objetos/s e o GC
    // devolve isso como engasgo — exatamente o que um overlay de diagnóstico não pode causar.
    val path = remember { Path() }
    val quad = remember { FloatArray(8) }

    Canvas(modifier = modifier.fillMaxSize()) {
        // O Canvas do Compose mede em dp; os landmarks mapeados também chegam em dp (ver
        // `mapLandmarks`). Então todas as constantes de largura abaixo são dp, consistentemente.
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
                val accentArgb = accent.toArgb()

                paints.glow.color = accent.copy(alpha = GLOW_ALPHA).toArgb()
                paints.bone.color = accent.copy(alpha = BONE_ALPHA).toArgb()
                paints.palm.color = accent.copy(alpha = PALM_ALPHA).toArgb()
                paints.joint.color = accentArgb
                paints.core.color = CORE_COLOR

                drawPalm(native, pts, paints.palm, path)

                for ((a, b) in HAND_BONES) {
                    val from = pts[a] ?: continue
                    val to = pts[b] ?: continue
                    // Halo primeiro, osso em cima. Inverter desenha o halo cobrindo o osso.
                    native.drawLine(from.x, from.y, to.x, to.y, paints.glow)
                    drawBone(native, from, to, BONE_BASE_DP, BONE_TIP_DP, paints.bone, path, quad)
                    native.drawLine(from.x, from.y, to.x, to.y, paints.core)
                }

                // Articulações com hierarquia de tamanho: o pulso é o maior, as pontas os menores.
                // Raio constante faz a mão parecer um diagrama; a hierarquia é o que dá a leitura
                // anatômica de nós-dos-dedos versus falanges.
                for (i in pts.indices) {
                    val p = pts[i] ?: continue
                    native.drawCircle(p.x, p.y, JOINT_RADIUS_DP[i], paints.joint)
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
                            paints.glow,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Palma preenchida: polígono pulso → base do polegar → nós dos quatro dedos → pulso.
 *
 * Este é o traço que mais distingue o desenho do Quest de um arame. Os vértices são os
 * landmarks que já delimitam a palma anatomicamente — o 17 (nó do mindinho) e o 2 (nó do
 * polegar) fecham os dois lados, e o pulso é a base. Não há ponto a inventar.
 *
 * `ADD` e não `SRC_OVER`: a palma é luz sobre a mão real, não tinta que a cobre. Com `SRC_OVER`
 * e alpha alto o suficiente para ser visível num passthrough claro, ela esconderia os dedos do
 * usuário — que é a referência visual que ele está usando para mirar.
 */
private fun drawPalm(native: android.graphics.Canvas, pts: List<Offset?>, paint: Paint, path: Path) {
    path.reset()
    var started = false
    for (index in PALM_POLYGON) {
        val p = pts[index] ?: return
        if (!started) {
            path.moveTo(p.x, p.y)
            started = true
        } else {
            path.lineTo(p.x, p.y)
        }
    }
    if (started) {
        path.close()
        native.drawPath(path, paint)
    }
}

/**
 * Osso afunilado: um quad de [baseWidth] na origem até [tipWidth] no destino.
 *
 * `drawLine` só faz largura constante. Para afinar é preciso um polígono, e os dois vértices
 * extras saem da perpendicular à direção do osso. `quad` é reutilizado para não alocar.
 */
private fun drawBone(
    native: android.graphics.Canvas,
    from: Offset,
    to: Offset,
    baseWidth: Float,
    tipWidth: Float,
    paint: Paint,
    path: Path,
    quad: FloatArray,
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy)
    // Osso degenerado (landmarks coincidentes, o que acontece quando a mão está de perfil):
    // sem direção não há perpendicular, e dividir por zero produziria NaN que o Canvas propaga
    // para o desenho inteiro. Pular é o correto — não há nada para desenhar.
    if (len < 1e-4f) return

    val nx = -dy / len
    val ny = dx / len
    val halfBase = baseWidth / 2f
    val halfTip = tipWidth / 2f

    quad[0] = from.x + nx * halfBase
    quad[1] = from.y + ny * halfBase
    quad[2] = to.x + nx * halfTip
    quad[3] = to.y + ny * halfTip
    quad[4] = to.x - nx * halfTip
    quad[5] = to.y - ny * halfTip
    quad[6] = from.x - nx * halfBase
    quad[7] = from.y - ny * halfBase

    path.reset()
    path.moveTo(quad[0], quad[1])
    path.lineTo(quad[2], quad[3])
    path.lineTo(quad[4], quad[5])
    path.lineTo(quad[6], quad[7])
    path.close()
    native.drawPath(path, paint)
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

/**
 * Os seis `Paint`, criados uma vez. Ver a KDoc do [HandSkeleton] para o porquê de não usar
 * `Paint` do Compose.
 *
 * Os aditivos estão separados dos normais de propósito: o núcleo branco e as articulações usam
 * `SRC_OVER` porque precisam de contraste contra fundo claro, e `ADD` os deixaria invisíveis
 * sobre branco. Só o que deve *brilhar* é aditivo.
 */
private class SkeletonPaints {
    private val additive = PorterDuffXfermode(PorterDuff.Mode.ADD)

    /** Halo desfocado. Traço, não preenchimento: o desfoque se aplica à borda do traço. */
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        // Explícito de propósito: um Paint STROKE sem `strokeWidth` usa 0, que o Android
        // interpreta como "hairline" de ~1 px independente da densidade. O halo sumiria.
        strokeWidth = GLOW_STROKE_DP
        maskFilter = BlurMaskFilter(GLOW_BLUR_DP, BlurMaskFilter.Blur.NORMAL)
        xfermode = additive
    }

    /** Corpo do osso, preenchido pelo quad afunilado. */
    val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = additive
    }

    /** Superfície da palma. */
    val palm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = additive
    }

    /** Articulações. `SRC_OVER`: precisam aparecer sobre qualquer fundo. */
    val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Núcleo fino quase branco, sobre o osso. `SRC_OVER` pelo mesmo motivo das articulações. */
    val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = CORE_STROKE_DP
    }
}

// Cores do Zyvox (ver [MrTokens]): ciano no repouso, verde no gesto — as mesmas do cursor, para
// que "mão rastreada" e "pinch ativo" tenham uma cor só no app inteiro.
private val IDLE_COLOR get() = MrTokens.Accent
private val PINCH_COLOR get() = MrTokens.Success
private const val CORE_COLOR = 0xE6FFFFFF.toInt()

private const val GLOW_STROKE_DP = 9f
private const val GLOW_ALPHA = 0.45f
private const val GLOW_BLUR_DP = 16f

private const val BONE_BASE_DP = 6.0f
private const val BONE_TIP_DP = 2.6f
private const val BONE_ALPHA = 0.60f

/**
 * Alpha da palma. Baixo de propósito: alta o suficiente para dar volume, baixa o suficiente para
 * os dedos do usuário continuarem visíveis através dela.
 */
private const val PALM_ALPHA = 0.20f

private const val PINCH_RING_DP = 18f

/**
 * Vértices da palma, em ordem de contorno. A ordem importa: fora dela o polígono se
 * auto-intersecta e o preenchimento sai com buracos.
 */
private val PALM_POLYGON = intArrayOf(
    HandJoint.WRIST,
    HandJoint.THUMB_CMC,
    HandJoint.THUMB_MCP,
    HandJoint.INDEX_MCP,
    HandJoint.MIDDLE_MCP,
    HandJoint.RING_MCP,
    HandJoint.PINKY_MCP,
)

/**
 * Raio de cada uma das 21 articulações, indexado pelo número do landmark.
 *
 * A hierarquia é pulso > nós dos dedos > falanges > pontas. É ela que faz o desenho ler como
 * mão em vez de grafo: num grafo todos os vértices são iguais.
 */
private val JOINT_RADIUS_DP = floatArrayOf(
    4.6f, // 0 WRIST
    3.2f, // 1 THUMB_CMC
    3.6f, // 2 THUMB_MCP
    2.9f, // 3 THUMB_IP
    2.5f, // 4 THUMB_TIP
    3.6f, // 5 INDEX_MCP
    2.9f, // 6 INDEX_PIP
    2.5f, // 7 INDEX_DIP
    2.2f, // 8 INDEX_TIP
    3.7f, // 9 MIDDLE_MCP
    3.0f, // 10 MIDDLE_PIP
    2.6f, // 11 MIDDLE_DIP
    2.3f, // 12 MIDDLE_TIP
    3.5f, // 13 RING_MCP
    2.9f, // 14 RING_PIP
    2.5f, // 15 RING_DIP
    2.2f, // 16 RING_TIP
    3.3f, // 17 PINKY_MCP
    2.7f, // 18 PINKY_PIP
    2.3f, // 19 PINKY_DIP
    2.0f, // 20 PINKY_TIP
)
