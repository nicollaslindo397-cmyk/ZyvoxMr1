package dev.zyvox.mr.ui

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * Céu de tempestade: chuva, relâmpago e trovão, desenhados pelo app em vez de vir da câmera.
 *
 * ### A câmera continua ligada atrás disto
 * Este painel é pintado **por cima** do `PreviewView`, não no lugar dele. O motivo não é
 * preguiça: o hand tracking é alimentado pelo `ImageAnalysis` da mesma câmera que produz o
 * passthrough. Remover o `PreviewView` da composição desfaria o `DisposableEffect` que faz o
 * bind da câmera, e o rastreamento de mãos morreria — justo no modo em que a mão é a única
 * forma de interagir, porque sem o vídeo do mundo real não há nada para apontar.
 *
 * ### Custo
 * Nada aqui aloca por quadro. As gotas são derivadas do índice (hash determinístico), a posição
 * é aritmética sobre o tempo, e o único `Paint` é retido e mutado. São ~260 `drawLine` por
 * quadro, que é barato; o que seria caro é alocar 260 objetos 60 vezes por segundo.
 */
@Composable
fun StormBackdrop(modifier: Modifier = Modifier) {
    val density = LocalDensity.current

    // Estado do relâmpago. `Long`/`Float` em vez de um data class para não alocar por quadro.
    var frameTick by remember { mutableIntStateOf(0) }
    var flashStartNs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var nextFlashNs by remember { mutableLongStateOf(0L) }
    var flashX by remember { mutableFloatStateOf(0.5f) }
    var sheetStartNs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var nextSheetNs by remember { mutableLongStateOf(0L) }

    val thunder = remember { ThunderAudio() }
    // `thunder` é criado no `remember` acima; liberar aqui é o que impede a pista de áudio de
    // vazar quando o usuário volta para o passthrough.
    DisposableEffect(Unit) { onDispose { thunder.release() } }

    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime())
        nextFlashNs = System.nanoTime() + random.nextLong(FIRST_FLASH_DELAY_NS, FIRST_FLASH_DELAY_NS * 2)
        nextSheetNs = System.nanoTime() + random.nextLong(SHEET_MIN_NS, SHEET_MAX_NS)
        while (true) {
            withFrameNanos { now ->
                frameTick++
                if (now >= nextFlashNs) {
                    flashStartNs = now
                    flashX = 0.15f + random.nextFloat() * 0.7f
                    nextFlashNs = now + random.nextLong(FLASH_MIN_NS, FLASH_MAX_NS)
                    thunder.play()
                }
                // "Sheet lightning": o clarão difuso de um relâmpago escondido atrás das nuvens,
                // sem raio visível. É o que faz o céu parecer vivo entre um raio e outro, e custa
                // quase nada — só modula o brilho do topo.
                if (now >= nextSheetNs) {
                    sheetStartNs = now
                    nextSheetNs = now + random.nextLong(SHEET_MIN_NS, SHEET_MAX_NS)
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(SKY_GRADIENT)),
    ) {
        val pxPerDp = density.density
        val wPx = size.width * pxPerDp
        val hPx = size.height * pxPerDp
        // Ler o tick vincula o desenho ao estado que muda por quadro; sem isto o Canvas desenha
        // uma vez e a chuva congela no ar.
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        val nowNs = System.nanoTime()
        val flash = flashEnvelope(nowNs, flashStartNs)
        val sheet = sheetEnvelope(nowNs, sheetStartNs)

        drawClouds(hPx, sheet, flash)
        drawRain(wPx, hPx, nowNs, pxPerDp, flash)
        if (flash > 0.001f) drawBolt(wPx, hPx, flashX, flash, pxPerDp)
        if (flash > 0.02f) drawFlash(flashX, flash)
    }
}

// ─── Envelopes ────────────────────────────────────────────────────────────────────────────

/**
 * Brilho do relâmpago ao longo do tempo: sobe quase instantâneo, cai exponencial.
 *
 * Um clarão com fade linear lê como "a tela piscou". O decaimento exponencial é o que parece
 * luz de verdade — e o `pow(3.0)` no ataque dá o estalo curto e violento do início.
 */
private fun flashEnvelope(nowNs: Long, startNs: Long): Float {
    if (startNs == Long.MIN_VALUE) return 0f
    val t = (nowNs - startNs) / 1e9f
    if (t < 0f) return 0f
    if (t > FLASH_TAIL_S) return 0f
    if (t < FLASH_ATTACK_S) return (t / FLASH_ATTACK_S).pow(3)
    return kotlin.math.exp(-(t - FLASH_ATTACK_S) / FLASH_DECAY_S)
}

/** Clarão difuso das nuvens: mais lento e mais fraco que o raio. */
private fun sheetEnvelope(nowNs: Long, startNs: Long): Float {
    if (startNs == Long.MIN_VALUE) return 0f
    val t = (nowNs - startNs) / 1e9f
    if (t < 0f || t > SHEET_TAIL_S) return 0f
    return kotlin.math.exp(-t / SHEET_DECAY_S) * SHEET_PEAK
}

// ─── Desenho ──────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawClouds(hPx: Float, sheet: Float, flash: Float) {
    val lift = (sheet + flash * 0.5f).coerceIn(0f, 1f)
    val alpha = (0.85f - lift * 0.45f).coerceIn(0f, 1f)

    // Gradiente desenhado no canvas nativo, em pixel, e não com `Brush.verticalGradient`.
    //
    // O motivo é concreto e não estilístico: as unidades de `startY`/`endY` do Compose são dp,
    // e este escopo já trabalha em pixel (`wPx`/`hPx` vêm de `size * density`). Misturar os dois
    // espaços num gradiente é como se produz uma faixa de nuvem que aparece no lugar errado só
    // em aparelhos com densidade diferente da de desenvolvimento — um bug que o emulador nunca
    // mostra. Aqui `drawRect` nativo e `hPx` estão no mesmo espaço, sem conversão nenhuma.
    drawIntoCanvas { canvas ->
        cloudPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            hPx * 0.42f,
            CLOUD_BASE.copy(alpha = alpha).toArgb(),
            CLOUD_BASE.copy(alpha = 0f).toArgb(),
            Shader.TileMode.CLAMP,
        )
        canvas.nativeCanvas.drawRect(0f, 0f, size.width * density.density, hPx, cloudPaint)
    }
}

private fun DrawScope.drawRain(wPx: Float, hPx: Float, nowNs: Long, pxPerDp: Float, flash: Float) {
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        for (layer in RAIN_LAYERS) {
            rainPaint.color = layer.color
            rainPaint.alpha = (layer.baseAlpha + flash * 90).toInt().coerceIn(0, 255)
            rainPaint.strokeWidth = layer.widthDp * pxPerDp

            // `1e9f` e não `1e9`: com literal Double, `Long / Double` dá Double, e o Double
            // contamina `speed`, `y` e `x` até o `drawLine`, que só aceita Float.
            val t = nowNs / 1e9f
            for (i in 0 until layer.count) {
                val r = unit(i * 31 + layer.seed)
                val r2 = unit(i * 57 + layer.seed + 7)
                val r3 = unit(i * 13 + layer.seed + 13)

                val speed = layer.speed * (0.7f + r * 0.6f)
                // A gota percorre a tela mais o próprio comprimento, e o resto da divisão a
                // traz de volta ao topo. É isso que faz a chuva ser infinita sem estado nenhum.
                val span = hPx + layer.lengthPx
                var y = ((t * speed + r2 * span) % span)
                if (y < 0f) y += span

                val x = (r3 * (wPx + SLANT_PX * 2f)) - SLANT_PX + (y / hPx) * SLANT_PX
                native.drawLine(
                    x,
                    y,
                    x - layer.lengthPx * SLANT_RATIO,
                    y - layer.lengthPx,
                    rainPaint,
                )
            }
        }
    }
}

/**
 * O raio: uma polilinha que desce com desvios alternados, mais um galho.
 *
 * O desvio alterna de lado a cada segmento (`if (seg % 2 == 0) 1 else -1`), que é o que dá o
 * zigue-zague angular de um raio. Desvio puramente aleatório por segmento produziria um caminho
 * que parece uma minhoca, não um relâmpago.
 */
private fun DrawScope.drawBolt(wPx: Float, hPx: Float, flashX: Float, intensity: Float, pxPerDp: Float) {
    val segments = BOLT_SEGMENTS
    var x = flashX * wPx
    var y = 0f
    val stepY = hPx * 0.62f / segments

    boltPaint.alpha = (255 * intensity).toInt().coerceIn(0, 255)
    boltPaint.strokeWidth = BOLT_CORE_DP * pxPerDp
    glowPaint.alpha = (150 * intensity).toInt().coerceIn(0, 255)
    glowPaint.strokeWidth = BOLT_GLOW_DP * pxPerDp

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        for (seg in 0 until segments) {
            val jitter = if (seg % 2 == 0) 1f else -1f
            val nx = x + jitter * stepY * BOLT_JITTER * (0.4f + unit(seg * 91) * 0.9f)
            val ny = y + stepY
            // Duas passadas: halo largo embaixo, núcleo branco em cima. Mesmo truque do
            // esqueleto da mão — é o que faz a linha parecer emissiva em vez de pintada.
            native.drawLine(x, y, nx, ny, glowPaint)
            native.drawLine(x, y, nx, ny, boltPaint)

            // Um galho por raio, num segmento fixo: galhos demais viram um arbusto.
            if (seg == BOLT_BRANCH_AT) {
                val bx = nx + stepY * 1.6f * (if (jitter > 0f) 1f else -1f)
                val by = ny + stepY * 1.8f
                native.drawLine(nx, ny, bx, by, glowPaint)
                native.drawLine(nx, ny, bx, by, boltPaint)
            }
            x = nx
            y = ny
        }
    }
}

private fun DrawScope.drawFlash(flashX: Float, intensity: Float) {
    // Clarão radial centrado no raio. Sem isto o raio aparece mas o céu não reage, e a cena lê
    // como um risco branco colado na tela em vez de luz iluminando nuvem.
    // `size` do DrawScope já é dp, então aqui não há conversão nenhuma a fazer.
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                FLASH_COLOR.copy(alpha = 0.55f * intensity),
                FLASH_COLOR.copy(alpha = 0.0f),
            ),
            center = Offset(flashX * size.width, size.height * 0.18f),
            radius = size.width * 1.1f,
        ),
    )
}

/**
 * Hash determinístico em `0..1` a partir de um inteiro.
 *
 * Existe para que a chuva não precise de estado: a gota `i` tem sempre a mesma posição base,
 * velocidade e opacidade, então ela é estável entre quadros sem guardar nada. Um `Random` por
 * quadro faria a chuva inteira trepidar, e um `List<Drop>` alocaria no caminho de render.
 */
private fun unit(seed: Int): Float {
    // Multiplicadores todos abaixo de Int.MAX_VALUE de propósito. Um literal que excede
    // 2147483647 vira Long em Kotlin, e aí `h * literal` devolve Long — que não cabe de volta
    // em `h`. Não precisa de qualidade criptográfica, só de decorarrelar o índice.
    var h = seed * 747796405 + 289133645
    h = h.inv() * 19349663
    h = (h xor (h ushr 15)) * 8324197
    h = h xor (h ushr 13)
    return (abs(h) % 10_000) / 10_000f
}

// ─── Constantes ───────────────────────────────────────────────────────────────────────────

private val SKY_GRADIENT = listOf(
    Color(0xFF0A0E16),
    Color(0xFF131A26),
    Color(0xFF1B2430),
    Color(0xFF0D1219),
)

private val CLOUD_BASE = Color(0xFF2A3543)
private val FLASH_COLOR = Color(0xFFDCE9FF)

private val RAIN_COLOR_FAR = 0x8A9DB4
private val RAIN_COLOR_MID = 0xAFC4D8
private val RAIN_COLOR_NEAR = 0xD3E4F2

private data class RainLayer(
    val count: Int,
    val speed: Float,
    val lengthPx: Float,
    val widthDp: Float,
    val baseAlpha: Int,
    val color: Int,
    val seed: Int,
)

/**
 * Três camadas com velocidades e tamanhos diferentes = paralaxe.
 *
 * Uma camada só de chuva lê como um véu plano sobre a tela. Dar às gotas distantes menos
 * velocidade, menos comprimento e menos opacidade — e às próximas o contrário — é o que faz o
 * olho aceitar que há profundidade, que é justamente o que um app de MR precisa vender.
 */
private val RAIN_LAYERS = listOf(
    RainLayer(count = 130, speed = 900f, lengthPx = 26f, widthDp = 0.9f, baseAlpha = 70, color = RAIN_COLOR_FAR, seed = 101),
    RainLayer(count = 90, speed = 1500f, lengthPx = 44f, widthDp = 1.4f, baseAlpha = 110, color = RAIN_COLOR_MID, seed = 202),
    RainLayer(count = 45, speed = 2300f, lengthPx = 70f, widthDp = 2.1f, baseAlpha = 160, color = RAIN_COLOR_NEAR, seed = 303),
)

/** Inclinação da chuva, em pixels de deslocamento horizontal ao longo da tela. */
private const val SLANT_PX = 90f
private const val SLANT_RATIO = 0.16f

private const val BOLT_SEGMENTS = 14
private const val BOLT_JITTER = 0.55f
private const val BOLT_CORE_DP = 2.0f
private const val BOLT_GLOW_DP = 11.0f
private const val BOLT_BRANCH_AT = 6

private const val FLASH_ATTACK_S = 0.035f
private const val FLASH_DECAY_S = 0.16f
private const val FLASH_TAIL_S = 1.1f

private const val SHEET_PEAK = 0.5f
private const val SHEET_DECAY_S = 0.55f
private const val SHEET_TAIL_S = 2.2f

private const val FIRST_FLASH_DELAY_NS = 900_000_000L
private const val FLASH_MIN_NS = 3_500_000_000L
private const val FLASH_MAX_NS = 9_000_000_000L
private const val SHEET_MIN_NS = 1_800_000_000L
private const val SHEET_MAX_NS = 5_000_000_000L

// Um Paint por tipo, retido no topo do arquivo. Criar Paint dentro do laço de desenho alocaria
// centenas de objetos por segundo e apareceria no GC como engasgo — exatamente o que um efeito
// de fundo não pode causar.
private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
}
private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    color = android.graphics.Color.WHITE
}
private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    color = 0x99BBD8FF.toInt()
}

/**
 * Preenchimento da faixa de nuvem. O `shader` é recriado a cada quadro porque o brilho depende
 * do relâmpago; alocar um `LinearGradient` por frame é aceitável aqui — é um objeto por quadro,
 * não centenas como seriam as gotas de chuva.
 */
private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
