package dev.zyvox.mr

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.zyvox.mr.hand.HandFrame
import dev.zyvox.mr.hand.HandTrackerPipeline
import dev.zyvox.mr.settings.DisplayMode
import dev.zyvox.mr.settings.InterfaceLayout
import dev.zyvox.mr.settings.InterfacePreset
import dev.zyvox.mr.settings.SettingsStore
import dev.zyvox.mr.settings.ZyvoxSettings
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

    // ─── Configuração persistível ───────────────────────────────────────────────────────

    private val settingsStore = SettingsStore(application)

    /**
     * Toda a configuração do app, num único objeto imutável observável.
     *
     * Observável (e não `@Volatile` como o caminho de render) porque mudar configuração é uma
     * ação rara do usuário e a UI precisa reagir: é isso que faz os ajustes valerem **em tempo
     * real**, sem reiniciar o app. O que não pode é estado por quadro virar observável — ver a
     * KDoc da classe.
     *
     * ### Fonte única de verdade
     * `dockScale` e o modo de fundo costumavam ser campos separados aqui. Eles foram absorvidos
     * por [settings]: ter `dockScale` e `settings.layout.scale` ao mesmo tempo são duas fontes
     * que podem divergir em silêncio, e divergência entre "o que a UI mostra" e "o que a cena
     * aplica" é invisível em código e intratável em aparelho.
     */
    var settings by mutableStateOf(settingsStore.load())
        private set

    /**
     * Único caminho de escrita. Sanitiza e persiste em todo ajuste.
     *
     * Centralizar importa porque as duas coisas são fáceis de esquecer num call site novo: sem
     * sanitizar, um slider mal limitado grava um estado inutilizável; sem persistir, o ajuste
     * funciona na sessão e some ao reabrir o app — que o usuário lê como "não salva".
     */
    fun updateSettings(transform: (ZyvoxSettings) -> ZyvoxSettings) {
        val updated = transform(settings).sanitized()
        settings = updated
        settingsStore.save(updated)
    }

    /** Ajusta o posicionamento da interface. Marca o preset como personalizado. */
    fun updateLayout(transform: (InterfaceLayout) -> InterfaceLayout) {
        updateSettings { it.withLayout(transform(it.layout)) }
    }

    /** Escala vigente da interface. Derivada de [settings], nunca um campo próprio. */
    val dockScale: Float get() = settings.layout.scale

    /**
     * Ajusta o tamanho em passos. Os limites vêm de [InterfaceLayout] — os mesmos que a
     * sanitização aplica — para não existir uma segunda faixa de clamp que possa divergir.
     */
    fun adjustDockScale(delta: Float) {
        updateLayout { it.copy(scale = it.scale + delta) }
    }

    fun setDistance(meters: Float) = updateLayout { it.copy(distanceM = meters) }

    fun setHeight(meters: Float) = updateLayout { it.copy(heightM = meters) }

    fun setHorizontalOffset(meters: Float) = updateLayout { it.copy(offsetXM = meters) }

    fun setTilt(degrees: Float) = updateLayout { it.copy(tiltDeg = degrees) }

    fun setScale(scale: Float) = updateLayout { it.copy(scale = scale) }

    /** Alterna "fixar a interface": ancorada no mundo, sem seguir a rotação da cabeça. */
    fun togglePinned() = updateLayout { it.copy(pinned = !it.pinned) }

    /** Aplica um dos quatro presets de distância. */
    fun applyPreset(preset: InterfacePreset) = updateSettings { it.withPreset(preset) }

    /**
     * Recentraliza posição e inclinação, preservando distância, tamanho e fixação.
     *
     * Distinto de [resetAllSettings] de propósito: ver `InterfaceLayout.recentered`.
     */
    fun recenterInterface() = updateLayout { it.recentered() }

    /** Restaura tudo para o padrão de fábrica. */
    fun resetAllSettings() {
        val defaults = ZyvoxSettings()
        settings = defaults
        settingsStore.save(defaults)
        statusMessage = "Configurações restauradas"
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

    /**
     * Alterna entre passthrough e ambiente virtual. Disparado por mão direita fechada.
     *
     * O estado vive em [settings]`.displayMode`, não num campo próprio: o enum `BackgroundMode`
     * que existia aqui foi absorvido por [DisplayMode], que é o mesmo conceito com o terceiro
     * caso que o modo VR Box precisa. Manter os dois seria ter duas fontes de verdade sobre o
     * que aparece atrás dos painéis.
     *
     * ### A câmera continua ligada nos dois modos
     * Isto é o ponto fácil de errar: trocar o fundo **não** pode fechar a câmera. O hand
     * tracking é alimentado pelo `ImageAnalysis` da mesma câmera que produz o passthrough, então
     * desligá-la para mostrar o ambiente virtual mataria todo o rastreamento de mãos — justamente
     * o modo em que o usuário mais precisa dele, porque sem o vídeo do mundo real a única forma de
     * interagir é a mão.
     *
     * O que muda é só o que se DESENHA: a camada de ambiente é pintada por cima do `PreviewView`,
     * que continua na árvore de composição (removê-lo desfaria o `DisposableEffect` que faz o
     * bind da câmera) e continua recebendo quadros; apenas fica coberto.
     */
    fun toggleBackground() {
        updateSettings {
            it.copy(
                displayMode = when (it.displayMode) {
                    DisplayMode.MR_PASSTHROUGH -> DisplayMode.VR_FLAT
                    // VR_BOX também volta para o passthrough: o gesto é um alterna de dois
                    // estados, e sair do modo VR Box pelo mesmo gesto que entrou nele é o que o
                    // usuário espera. Entrar em VR Box se faz pelas configurações.
                    DisplayMode.VR_FLAT, DisplayMode.VR_BOX -> DisplayMode.MR_PASSTHROUGH
                },
            )
        }
        statusMessage = when (settings.displayMode) {
            DisplayMode.MR_PASSTHROUGH -> "Fundo: câmera (passthrough)"
            DisplayMode.VR_FLAT -> "Fundo: ambiente virtual"
            DisplayMode.VR_BOX -> "Modo VR Box"
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
