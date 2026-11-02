package dev.zyvox.mr.hand

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Câmera → Hand Landmarker → [HandFrame], construído para **nunca segurar a thread que
 * entrega quadros da câmera**.
 *
 * ### O desenho em três threads
 *
 * 1. **Thread do analisador** ([onFrame]): se não houver inferência em voo, copia os três
 *    planos YUV para fora do `ImageProxy` (algumas centenas de KB de `memcpy`), anota os
 *    metadados e entrega. Caso contrário **descarta o quadro** — nunca enfileira, nunca
 *    espera. Enfileirar é o erro clássico aqui: a fila cresce, a latência dispara e o
 *    cursor passa a mostrar onde a mão *esteve*. Descartar mantém a latência no piso.
 * 2. **Worker** (`zyvox-hand`): converte YUV → RGB upright em meia resolução (sem JPEG, sem
 *    Bitmap) e submete via `detectAsync` num landmarker em `LIVE_STREAM`.
 * 3. **Thread do MediaPipe** (result listener): reduz os landmarks a [TrackedHand], aplica
 *    histerese de pinch e suavização, e posta um [HandFrame] na main.
 *
 * ### Por que `LIVE_STREAM` e não `IMAGE`
 * `RunningMode.IMAGE` é **bloqueante**: `detect()` só retorna quando o modelo termina. Como
 * `onSessionUpdated`/o analisador rodam na thread que alimenta o preview, isso congela a
 * imagem da câmera pelo tempo da inferência. `LIVE_STREAM` devolve por callback e permite o
 * descarte de quadros acima.
 *
 * ### Prova no aparelho
 * `adb logcat -s ZyvoxHandPerf` imprime, a cada 2 s: fps de entrega de quadros, custo do
 * hand-off, custo da conversão, latência de inferência e quantos quadros foram descartados.
 * Se `dropped` for alto e constante, o aparelho não acompanha — reduza
 * [analysisResolution] ou troque para o modelo `.task` "lite".
 */
class HandTrackerPipeline(
    context: Context,
    private val onResult: (HandFrame) -> Unit,
    private val maxHands: Int = 2,
) : AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zyvox-hand").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val closed = AtomicBoolean(false)

    /** Uma inferência por vez, com ticket. Ver [InferenceSlot]. */
    private val slot = InferenceSlot(stuckAfterNs = STUCK_NS)

    /** Timestamps estritamente crescentes, inclusive entre reinícios da câmera. */
    private val timestamps = ModelTimestamps()

    /** Atribuição estável de slot 0/1 às duas mãos. Ver [HandSlotAssigner]. */
    private val slotAssigner = HandSlotAssigner(slotCount = maxHands)

    /**
     * Estado por mão: detector de pinch e suavizador próprios. Precisam ser independentes
     * porque as duas mãos podem estar em estados de gesto diferentes ao mesmo tempo — e
     * compartilhar um detector faria a mão esquerda "herdar" o pinch da direita.
     */
    private val pinchDetectors = List(maxHands) { PinchDetector() }
    private val smoothers = List(maxHands) { LandmarkSmoother() }

    private val landmarker: HandLandmarker? = runCatching {
        HandLandmarker.createFromOptions(
            context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET_PATH)
                        // Delegate padrão (CPU). O delegate GPU do MediaPipe Tasks exige
                        // EGL próprio e falha em vários aparelhos; CPU com o modelo float16
                        // já fica na casa de 15–25 ms, dentro do orçamento.
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(maxHands)
                // Detecção mais frouxa que o padrão 0.5, e é por causa da câmera TRASEIRA.
                //
                // Os limiares do modelo são calibrados para mão perto da lente (selfie, ~25 cm).
                // Aqui o aparelho fica a ~50-60 cm do usuário e a mão se estende à frente dele,
                // então a mão ocupa bem menos pixels e chega com menos nitidez — exatamente o
                // regime em que 0.5 descarta detecções legítimas. O sintoma é indistinguível de
                // "hand tracking não funciona": o modelo roda, não lança erro, e devolve zero
                // mãos para sempre.
                //
                // Só a DETECÇÃO afrouxa. Presença e tracking ficam em 0.5: eles governam a
                // estabilidade do gesto uma vez que a mão foi achada, e baixá-los traria o
                // tremor e a seleção fantasma que a histerese de pinch existe para evitar.
                // Detecção fantasma é barata aqui — ela morre no quadro seguinte, e
                // [isHandUsable] ainda filtra mão pequena demais ou fora do quadro.
                .setMinHandDetectionConfidence(0.35f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(::onHands)
                .setErrorListener { error ->
                    Log.w(PERF_TAG, "inferência de mão falhou", error)
                    // Falha sem ticket identificável: libera quem estiver segurando, senão
                    // o pipeline trava para sempre.
                    slot.releaseAny()
                }
                .build(),
        )
    }.onFailure {
        // Causa mais provável: o `.task` não foi baixado no build. O app segue rodando com
        // passthrough e dock, só sem hand tracking — a UI avisa via [isAvailable].
        Log.e(PERF_TAG, "HandLandmarker não inicializou (modelo presente em assets?)", it)
    }.getOrNull()

    /** `false` quando o modelo não pôde ser carregado — a UI avisa em vez de crashar. */
    val isAvailable: Boolean get() = landmarker != null

    // ── Buffers do único request em voo ──────────────────────────────────────────────────
    private val scratch = PlaneScratch()
    private var requestRotation = 0
    private var requestSubmitNs = 0L
    private var requestSourceNs = 0L
    private var rgb = ByteArray(0)
    private var rgbDirect: ByteBuffer = ByteBuffer.allocateDirect(0)

    // ── Métricas ─────────────────────────────────────────────────────────────────────────
    private val handOffCost = PerfWindow()
    private val convertCost = PerfWindow()
    private val inferenceLatency = PerfWindow()
    private val dropped = AtomicInteger()
    private var lastFrameNs = 0L
    private var lastLogNs = 0L
    private val frameGaps = PerfWindow()

    /**
     * Chamado pelo `ImageAnalysis.Analyzer`. **Nunca bloqueia.**
     *
     * O `ImageProxy` é fechado aqui mesmo, no fim: segurar o proxy aberto até a inferência
     * terminar esgota o pool de buffers da câmera (normalmente 4) e o preview para.
     */
    fun onFrame(imageProxy: ImageProxy) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        try {
            val detector = landmarker ?: return
            if (closed.get()) return

            if (lastFrameNs != 0L) frameGaps.add((startNs - lastFrameNs) / NS_PER_MS)
            lastFrameNs = startNs

            val timestampMs = timestamps.next(imageProxy.imageInfo.timestamp / NS_PER_MS_LONG) ?: return

            val wasHeld = slot.isHeld
            if (!slot.tryAcquire(ticket = timestampMs, nowNs = startNs)) {
                dropped.incrementAndGet()
                return
            }
            if (wasHeld) Log.w(PERF_TAG, "slot de inferência travado por > 1 s, retomado")

            val copied = runCatching { copyFrame(imageProxy) }.getOrElse {
                Log.w(PERF_TAG, "falha ao copiar plano da câmera", it)
                false
            }
            if (!copied) {
                slot.release(timestampMs)
                return
            }
            requestSubmitNs = startNs
            requestSourceNs = imageProxy.imageInfo.timestamp
            handOffCost.add((SystemClock.elapsedRealtimeNanos() - startNs) / NS_PER_MS)

            worker.execute { convertAndDetect(detector, timestampMs) }
        } finally {
            maybeLog(startNs)
            imageProxy.close()
        }
    }

    /** Copia os planos YUV e anota a rotação. Thread do analisador. */
    private fun copyFrame(imageProxy: ImageProxy): Boolean {
        val image = imageProxy.image ?: return false
        if (!scratch.copyFrom(image)) return false
        requestRotation = imageProxy.imageInfo.rotationDegrees
        return true
    }

    /** Worker: YUV → RGB upright em meia resolução, e submete ao MediaPipe. */
    private fun convertAndDetect(detector: HandLandmarker, timestampMs: Long) {
        if (closed.get()) return
        runCatching {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val needed = (scratch.width / 2) * (scratch.height / 2) * RGB_CHANNELS
            if (rgb.size != needed) {
                rgb = ByteArray(needed)
                // allocateDirect é obrigatório: o ByteBufferImageBuilder precisa de memória
                // nativa para passar ao MediaPipe sem cópia extra via JNI.
                rgbDirect = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            }
            val (w, h) = YuvRgbConverter.convert(
                y = scratch.y,
                yRowStride = scratch.yRowStride,
                u = scratch.u,
                v = scratch.v,
                uvRowStride = scratch.uvRowStride,
                uvPixelStride = scratch.uvPixelStride,
                rawWidth = scratch.width,
                rawHeight = scratch.height,
                rotationDegrees = requestRotation,
                out = rgb,
            )
            rgbDirect.clear()
            rgbDirect.put(rgb, 0, w * h * RGB_CHANNELS)
            rgbDirect.flip()
            convertCost.add((SystemClock.elapsedRealtimeNanos() - t0) / NS_PER_MS)

            val mpImage = ByteBufferImageBuilder(rgbDirect, w, h, MPImage.IMAGE_FORMAT_RGB).build()
            detector.detectAsync(mpImage, timestampMs)
        }.onFailure {
            Log.w(PERF_TAG, "falha ao submeter quadro ao modelo", it)
            slot.release(timestampMs)
        }
    }

    /** Thread de callback do MediaPipe. */
    private fun onHands(result: HandLandmarkerResult, input: MPImage) {
        val submitNs = requestSubmitNs
        val sourceNs = requestSourceNs
        // Lê o estado do request ANTES de liberar o slot: o próximo request sobrescreve.
        // Um resultado cujo slot foi retomado devolve `false` e é descartado — os buffers
        // dele agora pertencem a um request mais novo.
        if (!slot.release(result.timestampMs())) return
        inferenceLatency.add((SystemClock.elapsedRealtimeNanos() - submitNs) / NS_PER_MS)
        if (closed.get()) return

        val landmarkLists = result.landmarks()
        val handedness = result.handedness()

        // Centro x de cada mão, para a atribuição estável de slot.
        val centers = landmarkLists.map { points ->
            palmCenter(points.map { HandPoint(it.x(), it.y(), it.z()) })?.x ?: 0f
        }
        val slots = slotAssigner.assign(centers)

        val hands = ArrayList<TrackedHand>(landmarkLists.size)
        landmarkLists.forEachIndexed { index, rawPoints ->
            val slot = slots[index] ?: return@forEachIndexed
            val points = rawPoints.map { HandPoint(it.x(), it.y(), it.z()) }
            if (!isHandUsable(points)) return@forEachIndexed

            val palm = palmCenter(points) ?: return@forEachIndexed
            val (sx, sy) = smoothers[slot].update(palm.x, palm.y)

            val ratio = pinchRatio(points)
            val pinching = pinchDetectors[slot].update(ratio)

            val wrist = points[HandJoint.WRIST]
            val middleMcp = points[HandJoint.MIDDLE_MCP]
            val handScale = kotlin.math.hypot(middleMcp.x - wrist.x, middleMcp.y - wrist.y)

            val category = handedness.getOrNull(index)?.firstOrNull()
            hands += TrackedHand(
                slot = slot,
                handednessLabel = category?.categoryName() ?: "?",
                handednessScore = category?.score() ?: 0f,
                points = points,
                // O cursor segue o CENTRO DA PALMA suavizado, não a ponta do indicador.
                // A ponta do indicador é o landmark mais ruidoso (é o que mais se move), e
                // usá-la faz o cursor tremer justamente quando o usuário está mirando.
                viewX = sx,
                viewY = sy,
                pinchRatio = ratio ?: Float.NaN,
                isPinching = pinching,
                handScale = handScale,
                trackingScore = category?.score() ?: 0f,
            )
        }

        val frame = HandFrame(
            hands = hands,
            sourceTimeNs = sourceNs,
            latencyMs = (SystemClock.elapsedRealtimeNanos() - submitNs) / NS_PER_MS.toFloat(),
        )
        mainHandler.post { if (!closed.get()) onResult(frame) }
    }

    /** Reinicia o estado de gesto — usado quando o tracking é perdido por muito tempo. */
    fun resetGestures() {
        pinchDetectors.forEach { it.reset() }
        smoothers.forEach { it.reset() }
        slot.releaseAny()
    }

    private fun maybeLog(nowNs: Long) {
        if (lastLogNs == 0L) {
            lastLogNs = nowNs
            return
        }
        if (nowNs - lastLogNs < LOG_EVERY_NS) return
        val seconds = (nowNs - lastLogNs) / NS_PER_S
        lastLogNs = nowNs
        val (frames, gapAvg, gapMax) = frameGaps.drain()
        val (handOffs, handOffAvg, handOffMax) = handOffCost.drain()
        val (_, convertAvg, convertMax) = convertCost.drain()
        val (inferences, latencyAvg, latencyMax) = inferenceLatency.drain()
        Log.i(
            PERF_TAG,
            String.format(
                Locale.US,
                "camera fps=%.1f gap avg=%.1fms max=%.1fms | handOff n=%d avg=%.2fms max=%.2fms | " +
                    "convert avg=%.1fms max=%.1fms | inference n=%d (%.1f/s) latency avg=%.1fms max=%.1fms | " +
                    "dropped=%d",
                frames / seconds, gapAvg, gapMax,
                handOffs, handOffAvg, handOffMax,
                convertAvg, convertMax,
                inferences, inferences / seconds, latencyAvg, latencyMax,
                dropped.getAndSet(0),
            ),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Fecha o landmarker no worker para não correr com uma conversão em andamento.
        worker.execute { runCatching { landmarker?.close() } }
        worker.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val PERF_TAG = "ZyvoxHandPerf"

        /** Caminho em assets — materializado pelo build (ver app/build.gradle.kts). */
        const val MODEL_ASSET_PATH = "hand_landmarker.task"

        private const val RGB_CHANNELS = 3
        private const val NS_PER_MS = 1_000_000.0
        private const val NS_PER_MS_LONG = 1_000_000L
        private const val NS_PER_S = 1_000_000_000.0
        private const val LOG_EVERY_NS = 2_000_000_000L
        private const val STUCK_NS = 1_000_000_000L
    }
}

/**
 * Janela deslizante de medidas com média e máximo, e `drain()` que zera ao ler.
 *
 * Existe só para o log de desempenho: sem ela, diagnosticar "o cursor está atrasado" no
 * aparelho vira adivinhação. Custo é desprezível (um `add` por quadro).
 */
class PerfWindow {
    private var count = 0
    private var sum = 0.0
    private var max = 0.0

    @Synchronized
    fun add(valueMs: Double) {
        count++
        sum += valueMs
        if (valueMs > max) max = valueMs
    }

    /** `(amostras, média, máximo)` desde o último `drain`. */
    @Synchronized
    fun drain(): Triple<Int, Double, Double> {
        val result = Triple(count, if (count == 0) 0.0 else sum / count, max)
        count = 0
        sum = 0.0
        max = 0.0
        return result
    }
}
