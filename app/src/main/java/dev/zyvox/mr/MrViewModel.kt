package dev.zyvox.mr

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.zyvox.mr.hand.HandFrame
import dev.zyvox.mr.hand.HandTrackerPipeline
import dev.zyvox.mr.ui.MrTokens
import dev.zyvox.mr.window.MrWindow
import dev.zyvox.mr.window.WindowStack

/**
 * Estado compartilhado da sessão MR.
 *
 * ### A decisão de projeto mais importante desta classe
 * Existem aqui **dois tipos de estado, com disciplinas diferentes**, e confundir os dois é o
 * que destruiria o desempenho:
 *
 * 1. **Estado de janela** (`windows`): muda raramente (abrir, fechar, arrastar). Precisa
 *    disparar recomposição para o dock e a HUD refletirem. Vai em `mutableStateListOf`.
 * 2. **Estado de quadro** (`latestHandFrame`, `cursorWorldX/Y/Z`): muda a ~30 Hz. Se isto
 *    fosse `mutableStateOf`, cada quadro de hand tracking recomporia a tela inteira — o
 *    Compose gastaria mais que o Filament e a latência do cursor *pioraria* com a
 *    otimização. Então são campos `@Volatile` comuns, lidos diretamente dentro de
 *    `onFrame` (que roda fora da composição).
 *
 * Regra prática: se algo é lido em `onFrame`, não pode ser estado do Compose.
 *
 * ### Por que AndroidViewModel
 * O [HandTrackerPipeline] precisa de `Context` para carregar o modelo, e é caro demais para
 * ser recriado a cada recomposição ou rotação. Segurá-lo aqui dá a ele o ciclo de vida da
 * Activity sem vazá-lo para além dela.
 */
class MrViewModel(application: Application) : AndroidViewModel(application) {

    // ─── Hand tracking ──────────────────────────────────────────────────────────────────
    /**
     * Criado sob demanda (e não no construtor) porque carregar o modelo custa ~200 ms e não
     * deve acontecer antes de a permissão de câmera existir.
     */
    var pipeline: HandTrackerPipeline? = null
        private set

    /** `false` quando o `.task` não está nos assets — a UI avisa em vez de crashar. */
    var handTrackingAvailable by mutableStateOf(false)
        private set

    /**
     * Último quadro de mãos. `@Volatile`, não-observável: ver a KDoc da classe.
     * A inferência publica na main thread (o pipeline faz `mainHandler.post`), então a
     * leitura em `onFrame` — também na main — nunca vê estado parcialmente escrito.
     */
    @Volatile
    var latestHandFrame: HandFrame = HandFrame.EMPTY

    // ─── Cursor, em metros no mundo ─────────────────────────────────────────────────────
    @Volatile
    var cursorWorldX: Float = 0f

    @Volatile
    var cursorWorldY: Float = 0f

    @Volatile
    var cursorWorldZ: Float = 0f

    /** `true` quando o cursor tem posição válida neste quadro. */
    @Volatile
    var cursorValid: Boolean = false

    /**
     * Pixel de tela do cursor. A HUD 2D usa isto para desenhar o anel de feedback por cima
     * do passthrough sem depender da cena 3D — e serve de verificação visual de que o
     * mapeamento imagem→tela está correto.
     */
    @Volatile
    var cursorScreenX: Float = 0f

    @Volatile
    var cursorScreenY: Float = 0f

    // ─── Janelas ────────────────────────────────────────────────────────────────────────
    val stack = WindowStack(maxWindows = MAX_WINDOWS)

    /**
     * Espelho observável de [stack]. O Compose lê daqui; a lógica vive em [WindowStack]
     * (Kotlin puro, testável). Manter os dois em sincronia é trabalho de [syncWindows],
     * chamado após toda mutação.
     */
    val windows = mutableStateListOf<MrWindow>()

    var dockVisible by mutableStateOf(true)

    /**
     * Escala do dock, aplicada sobre [dev.zyvox.mr.ui.MrTokens.DOCK_WIDTH_M].
     *
     * Observável de propósito (e não `@Volatile` como o resto do caminho de render): mudar a
     * escala é uma ação rara e discreta do usuário, então vale uma recomposição. O que não pode
     * é o estado por quadro do hand tracking virar observável — ver a KDoc da classe.
     */
    var dockScale by mutableStateOf(1.0f)
        private set

    /**
     * Ajusta o tamanho do dock. Preso entre [MrTokens.DOCK_SCALE_MIN] e
     * [MrTokens.DOCK_SCALE_MAX]: abaixo disso os botões ficam menores que o cursor e o hand
     * tracking não os acerta; acima, o dock passa a cobrir o conteúdo que ele existe para abrir.
     */
    fun adjustDockScale(delta: Float) {
        dockScale = (dockScale + delta).coerceIn(MrTokens.DOCK_SCALE_MIN, MrTokens.DOCK_SCALE_MAX)
    }

    /** Não-observável: só a HUD lê, e ela já recomposição por outros motivos. */
    var lastLatencyMs: Float = 0f

    var lastInferenceFps: Float = 0f
        private set

    private var inferenceCount = 0
    private var inferenceWindowStartNs = 0L

    /** Mensagem de status para a HUD. */
    var statusMessage by mutableStateOf<String?>(null)

    // ─── Ciclo de vida ──────────────────────────────────────────────────────────────────
    private var closed = false

    /**
     * Inicializa o hand tracking. Chamado **depois** de a permissão de câmera ser concedida.
     * Idempotente.
     */
    fun ensurePipeline(): HandTrackerPipeline? {
        pipeline?.let { return it }
        if (closed) return null
        // `onResult =` nomeado de propósito. O construtor é
        // `HandTrackerPipeline(context, onResult, maxHands = 2)`, então uma lambda à solta depois
        // do context vai para o ÚLTIMO parâmetro — `maxHands: Int` — e não para `onResult`. O
        // compilador responde com "Argument type mismatch: 'Int' was expected" mais "No value
        // passed for parameter 'onResult'", que são dois erros para um único deslize.
        val created = HandTrackerPipeline(getApplication(), onResult = { frame -> onHandFrame(frame) })
        pipeline = created
        handTrackingAvailable = created.isAvailable
        if (!created.isAvailable) {
            statusMessage =
                "Modelo de hand tracking ausente — rode ./gradlew :app:fetchDebugHandModel " +
                    "ou ./scripts/fetch-hand-model.sh"
        }
        return created
    }

    /** Recebe cada quadro da inferência, na main thread. */
    private fun onHandFrame(frame: HandFrame) {
        latestHandFrame = frame
        lastLatencyMs = frame.latencyMs
        trackInferenceRate()
    }

    /**
     * Mede a taxa de inferência real (não a de câmera): é o número que diz se o aparelho
     * aguenta o modelo. Contada em janela de 1 s, sem alocar.
     */
    private fun trackInferenceRate() {
        val now = System.nanoTime()
        if (inferenceWindowStartNs == 0L) {
            inferenceWindowStartNs = now
            inferenceCount = 0
            return
        }
        inferenceCount++
        val elapsedNs = now - inferenceWindowStartNs
        if (elapsedNs >= 1_000_000_000L) {
            lastInferenceFps = inferenceCount * 1_000_000_000f / elapsedNs
            inferenceCount = 0
            inferenceWindowStartNs = now
        }
    }

    // ─── Ações de janela ────────────────────────────────────────────────────────────────
    /**
     * Abre uma janela. Retorna `false` se o limite foi atingido — o chamador deve avisar o
     * usuário, porque falhar em silêncio parece "o botão não funciona".
     */
    fun openWindow(url: String, title: String = url): Boolean {
        val created = stack.open(url, title) ?: run {
            statusMessage = "Limite de $MAX_WINDOWS janelas atingido"
            return false
        }
        statusMessage = null
        syncWindows()
        return created.id.isNotEmpty()
    }

    fun closeWindow(id: String) {
        stack.close(id)
        syncWindows()
    }

    fun closeAllWindows() {
        stack.closeAll()
        syncWindows()
    }

    fun minimizeWindow(id: String) {
        stack.toggleMinimize(id)
        syncWindows()
    }

    fun recenterWindows() {
        stack.recenter()
        syncWindows()
    }

    fun setTitle(id: String, title: String) {
        stack.setTitle(id, title)
        syncWindows()
    }

    fun toggleDock() {
        dockVisible = !dockVisible
    }

    // ─── Fundo da experiência ───────────────────────────────────────────────────────────

    /** O que aparece atrás dos painéis. Ver [toggleBackground]. */
    enum class BackgroundMode {
        /** Passthrough: o vídeo da câmera traseira, que é o modo padrão de um app de MR. */
        CAMERA,

        /** Ambiente virtual: céu de tempestade com chuva e relâmpago, desenhado pelo app. */
        STORM,
    }

    var backgroundMode by mutableStateOf(BackgroundMode.CAMERA)
        private set

    /**
     * Alterna entre a câmera e o ambiente de tempestade. Disparado por mão direita fechada.
     *
     * ### A câmera continua ligada nos dois modos
     * Isto é o ponto fácil de errar: trocar o fundo **não** pode fechar a câmera. O hand
     * tracking é alimentado pelo `ImageAnalysis` da mesma câmera que produz o passthrough, então
     * desligá-la para mostrar a tempestade mataria todo o rastreamento de mãos no modo virtual —
     * justamente o modo em que o usuário mais precisa dele, porque sem o vídeo do mundo real a
     * única forma de interagir é a mão.
     *
     * O que muda é só o que se DESENHA: a camada de tempestade é pintada por cima do
     * `PreviewView`. O `PreviewView` continua na árvore de composição (removê-lo desfaria o
     * `DisposableEffect` que faz o bind da câmera) e continua recebendo quadros; apenas fica
     * coberto.
     */
    fun toggleBackground() {
        backgroundMode = when (backgroundMode) {
            BackgroundMode.CAMERA -> BackgroundMode.STORM
            BackgroundMode.STORM -> BackgroundMode.CAMERA
        }
        statusMessage = when (backgroundMode) {
            BackgroundMode.CAMERA -> "Fundo: câmera (passthrough)"
            BackgroundMode.STORM -> "Fundo: tempestade"
        }
    }

    /**
     * Copia o estado imutável do [stack] para a lista observável.
     *
     * `clear()` + `addAll()` em vez de diff: o número de janelas é no máximo
     * [MAX_WINDOWS], então o custo é trivial, e um diff manual é mais uma chance de os dois
     * lados divergirem em silêncio.
     */
    private fun syncWindows() {
        val snapshot = stack.all
        windows.clear()
        windows.addAll(snapshot)
    }

    override fun onCleared() {
        closed = true
        pipeline?.close()
        pipeline = null
        super.onCleared()
    }

    companion object {
        /**
         * Teto de janelas simultâneas.
         *
         * Cada janela é um `WebView` de verdade desenhado off-screen e enviado como textura.
         * WebView custa dezenas de MB cada, e o caminho do `ViewNode` re-medida e redesenha a
         * view a cada invalidação. Sem teto o app morre por memória bem antes de morrer por
         * GPU — e morre sem aviso. 4 é o que ainda deixa espaço para o Filament e o modelo.
         */
        const val MAX_WINDOWS = 4
    }
}
