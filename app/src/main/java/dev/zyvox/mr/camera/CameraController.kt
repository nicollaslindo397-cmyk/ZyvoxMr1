package dev.zyvox.mr.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.zyvox.mr.hand.HandTrackerPipeline
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Liga a câmera traseira aos dois consumidores ao mesmo tempo: o **passthrough** (`Preview`
 * → `PreviewView`) e o **hand tracking** (`ImageAnalysis` → [HandTrackerPipeline]).
 *
 * ### Por que dois use cases e não um
 * O `Preview` desenha na tela com latência mínima e sem cópia de CPU. O `ImageAnalysis`
 * entrega quadros YUV legíveis pelo modelo. São caminhos diferentes no pipeline da câmera e
 * o CameraX foi feito para rodá-los juntos — tentar obter ambos de um só use case significa
 * copiar frames da GPU para a CPU por quadro, que é o oposto do que queremos.
 *
 * ### As decisões que afetam latência
 *
 * - **`STRATEGY_KEEP_ONLY_LATEST`**: se o analisador estiver ocupado, o CameraX descarta o
 *   quadro antigo e entrega só o mais recente. A alternativa (`BLOCK_PRODUCER`) faz a câmera
 *   esperar o analisador — o preview então engasga junto com a inferência, que é o defeito
 *   que este app existe para não ter.
 * - **Resolução de análise menor que a do preview**: o Hand Landmarker recebe 224 px, então
 *   analisar em 1920×1080 é desperdício puro — paga-se a conversão YUV de 2 megapixels por
 *   quadro para alimentar um modelo que reduz tudo a 224. [ANALYSIS_SIZE] corta isso.
 * - **Executor dedicado**: o analisador nunca roda na main. Rodar lá bloquearia o Compose e
 *   o Filament a cada quadro.
 *
 * ### Ciclo de vida
 * O `ProcessCameraProvider` é ligado a um `LifecycleOwner`, então a câmera abre e fecha
 * sozinho com a Activity. Não fazemos `unbindAll` manual: fazê-lo no momento errado (por
 * exemplo durante uma rotação) deixa o preview preto até o próximo bind.
 */
class CameraController(
    private val context: Context,
    private val pipeline: HandTrackerPipeline,
) {

    private var analysisExecutor: ExecutorService? = null
    private var bound = false

    /** Dimensões da última imagem de análise entregue, para o mapeamento tela↔imagem. */
    @Volatile
    var analysisWidth: Int = 0
        private set

    @Volatile
    var analysisHeight: Int = 0
        private set

    /** Rotação aplicada para pôr a imagem em pé, em graus. Vem do `ImageInfo`. */
    @Volatile
    var rotationDegrees: Int = 0
        private set

    /**
     * Faz o bind. Idempotente: chamar duas vezes (recomposição, retorno de background) não
     * duplica use cases.
     */
    fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val executor = analysisExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "zyvox-camera-analysis").apply {
                // Prioridade acima do normal: é o caminho que determina a latência do
                // cursor. Abaixo de `MAX_PRIORITY` para não matar o render.
                priority = Thread.NORM_PRIORITY + 2
            }
        }.also { analysisExecutor = it }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = runCatching { future.get() }.getOrElse {
                    Log.e(TAG, "ProcessCameraProvider indisponível", it)
                    return@addListener
                }
                runCatching { bindInternal(provider, lifecycleOwner, previewView, executor) }
                    .onFailure { Log.e(TAG, "bind da câmera falhou", it) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun bindInternal(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        executor: ExecutorService,
    ) {
        val preview = Preview.Builder()
            // O passthrough ocupa a tela inteira; escolher a proporção do display evita que o
            // PreviewView corte de um jeito diferente do que o analisador vê, o que
            // desalinha o cursor do dedo.
            .setTargetAspectRatio(ASPECT_RATIO_16_9)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(ANALYSIS_SIZE)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // YUV_420_888 explícito: é o formato que o conversor espera. Deixar o padrão
            // permitiria ao CameraX escolher PRIVATE em alguns aparelhos, cujo buffer não é
            // legível pela CPU — o analisador receberia quadros inutilizáveis.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(executor) { imageProxy -> onAnalysisFrame(imageProxy) }
            }

        // Câmera traseira principal, obrigatoriamente — é o passthrough pedido.
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        bound = true
        Log.i(TAG, "câmera ligada: preview + analysis ${ANALYSIS_SIZE.width}x${ANALYSIS_SIZE.height}")
    }

    /**
     * Quadro de análise. Anota os metadados que o mapeamento de tela precisa e entrega ao
     * pipeline — que decide sozinho se processa ou descarta, e que **fecha o `ImageProxy`**.
     *
     * O fechamento ficar do lado do pipeline não é descuido: é lá que se sabe se o quadro foi
     * usado, e fechar aqui exigiria copiar os planos duas vezes.
     */
    private fun onAnalysisFrame(imageProxy: ImageProxy) {
        if (!bound) {
            imageProxy.close()
            return
        }
        analysisWidth = imageProxy.width
        analysisHeight = imageProxy.height
        rotationDegrees = imageProxy.imageInfo.rotationDegrees
        try {
            pipeline.onFrame(imageProxy)
        } catch (t: Throwable) {
            // Nunca deixar uma exceção do analisador escapar: o CameraX para de entregar
            // quadros e o hand tracking morre sem nenhum log visível.
            Log.e(TAG, "analisador falhou", t)
            runCatching { imageProxy.close() }
        }
    }

    fun release() {
        bound = false
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    companion object {
        private const val TAG = "ZyvoxCamera"

        /**
         * Resolução de análise. 640×480 é o ponto em que o Hand Landmarker ainda acha a mão
         * com folga e a conversão YUV custa ~0,15 MP em vez de ~2 MP.
         *
         * O `ImageAnalysis` trata isto como **alvo**, não como contrato: o CameraX escolhe a
         * resolução suportada mais próxima. Por isso as dimensões reais são lidas de cada
         * `ImageProxy` em [onAnalysisFrame] e nunca assumidas.
         */
        private val ANALYSIS_SIZE = Size(640, 480)

        /** `androidx.camera.core.AspectRatio.RATIO_16_9`. */
        private const val ASPECT_RATIO_16_9 = 1
    }
}
