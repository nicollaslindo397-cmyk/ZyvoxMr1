package dev.zyvox.mr.hand

/**
 * Portão de "uma inferência por vez", com **ticket** por requisição.
 *
 * Copiado do padrão que o próprio sample do SceneView usa, porque um flag booleano simples
 * tem dois modos de falha quando uma requisição se perde (o MediaPipe nunca chama de
 * volta):
 *
 * 1. Os quadros seguintes ficam descartados até um timeout liberar o flag — o cursor congela.
 * 2. Quando o resultado perdido finalmente chega atrasado, ele libera o flag de uma
 *    requisição *mais nova* que está em voo, e essa requisição nova passa a dividir buffers
 *    com a velha — o cursor pula para a posição errada.
 *
 * Aqui um slot tomado é retomado pelo próximo quadro depois de [stuckAfterNs], e um
 * resultado só libera o slot (e só conta) se o ticket dele ainda é o que o detém.
 *
 * Kotlin puro e thread-safe: a thread da câmera adquire, a thread do MediaPipe libera.
 * Isso o torna testável em JVM sem Robolectric.
 */
class InferenceSlot(private val stuckAfterNs: Long) {

    private var holder = NO_TICKET
    private var heldSinceNs = 0L

    /** `true` enquanto uma requisição detém o slot (travada ou não). */
    val isHeld: Boolean
        @Synchronized get() = holder != NO_TICKET

    /**
     * Toma o slot para [ticket] quando livre, ou quando o detentor está fora há mais que
     * [stuckAfterNs] — esse detentor é esquecido e o resultado tardio dele será ignorado.
     */
    @Synchronized
    fun tryAcquire(ticket: Long, nowNs: Long): Boolean {
        if (holder != NO_TICKET && nowNs - heldSinceNs <= stuckAfterNs) return false
        holder = ticket
        heldSinceNs = nowNs
        return true
    }

    /** Libera o slot se [ticket] ainda o detém. `false` = resultado obsoleto, descartar. */
    @Synchronized
    fun release(ticket: Long): Boolean {
        if (holder != ticket) return false
        holder = NO_TICKET
        return true
    }

    /** Libera o slot seja quem for o detentor — para falhas que não dizem qual requisição falhou. */
    @Synchronized
    fun releaseAny() {
        holder = NO_TICKET
    }

    private companion object {
        const val NO_TICKET = Long.MIN_VALUE
    }
}

/**
 * Converte timestamps de quadro de câmera nos timestamps **estritamente crescentes** que o
 * modo `LIVE_STREAM` do MediaPipe exige, e continua correto quando a câmera reinicia.
 *
 * O pipeline sobrevive à sessão de câmera: bloquear a tela, trocar de Activity ou o
 * CameraX reconfigurar começa um relógio que pode voltar a um valor menor que o último
 * quadro visto. Um guarda ingênuo `timestamp <= last → pula` descartaria então *todos* os
 * quadros da sessão nova e o hand tracking morreria para sempre até o relógio ultrapassar o
 * antigo. Quando o relógio anda para trás, isto o rebaseia para o próximo timestamp seguir
 * em frente.
 *
 * O `LIVE_STREAM` do MediaPipe lança `IllegalArgumentException` em timestamp não crescente,
 * então isso não é só conveniência — sem isto o app crasha ao voltar do background.
 */
class ModelTimestamps {

    private var lastCameraMs = 0L
    private var lastModelMs = 0L
    private var started = false

    /**
     * O timestamp de modelo para um quadro carimbado com [cameraMs], ou `null` quando é o
     * mesmo quadro já visto (o analisador pode entregar o mesmo buffer duas vezes).
     */
    @Synchronized
    fun next(cameraMs: Long): Long? {
        if (!started) {
            started = true
            lastCameraMs = cameraMs
            lastModelMs = cameraMs
            return cameraMs
        }
        if (cameraMs == lastCameraMs) return null
        // Para frente: preserva o espaçamento da câmera. Para trás: sessão nova — segue em
        // frente com passo 1 ms, que é o mínimo que o LIVE_STREAM aceita.
        val step = if (cameraMs > lastCameraMs) cameraMs - lastCameraMs else 1L
        lastCameraMs = cameraMs
        lastModelMs += step
        return lastModelMs
    }
}
