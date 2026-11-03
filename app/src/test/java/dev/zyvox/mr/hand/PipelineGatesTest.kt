package dev.zyvox.mr.hand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes das travas de concorrência do pipeline.
 *
 * Estas duas classes são pequenas, mas são o que impede dois modos de falha que se
 * manifestam como "o cursor parou de responder" — sem crash e sem log, portanto quase
 * impossíveis de diagnosticar no aparelho.
 */
class PipelineGatesTest {

    // ─── InferenceSlot ──────────────────────────────────────────────────────────────────

    @Test
    fun `segunda aquisicao e recusada enquanto o slot estiver detido`() {
        val slot = InferenceSlot(stuckAfterNs = 1_000)

        assertTrue(slot.tryAcquire(ticket = 100, nowNs = 0))
        assertTrue(slot.isHeld)

        // É isto que implementa "descarta o quadro em vez de enfileirar". Enfileirar faria a
        // latência crescer sem limite: o cursor passaria a mostrar onde a mão esteve.
        assertFalse(slot.tryAcquire(ticket = 101, nowNs = 500))
    }

    @Test
    fun `release so libera se o ticket ainda detem o slot - resultado tardio e ignorado`() {
        val slot = InferenceSlot(stuckAfterNs = 1_000)

        assertTrue(slot.tryAcquire(ticket = 100, nowNs = 0))
        // 100 trava; o slot é retomado por 200 depois do timeout.
        assertTrue(slot.tryAcquire(ticket = 200, nowNs = 5_000))

        // O resultado de 100 chega atrasado. Sem o ticket, ele liberaria o slot de 200 e o
        // pipeline passaria a admitir uma terceira inferência concorrente — e os buffers
        // compartilhados seriam sobrescritos no meio dela.
        assertFalse(slot.release(100))
        assertTrue(slot.isHeld)

        assertTrue(slot.release(200))
        assertFalse(slot.isHeld)
    }

    @Test
    fun `slot travado e retomado depois do timeout`() {
        val slot = InferenceSlot(stuckAfterNs = 1_000)

        assertTrue(slot.tryAcquire(ticket = 1, nowNs = 0))
        // Dentro do timeout: ainda recusa.
        assertFalse(slot.tryAcquire(ticket = 2, nowNs = 999))
        // Passou do timeout: o MediaPipe nunca chamou de volta, então retomamos em vez de
        // travar o hand tracking para sempre.
        assertTrue(slot.tryAcquire(ticket = 3, nowNs = 1_001))
    }

    @Test
    fun `releaseAny libera independentemente do ticket`() {
        val slot = InferenceSlot(stuckAfterNs = 1_000)
        assertTrue(slot.tryAcquire(ticket = 7, nowNs = 0))

        // Usado pelo errorListener do MediaPipe, que não informa qual requisição falhou.
        // Sem isto, um erro deixaria o slot detido até o timeout.
        slot.releaseAny()
        assertFalse(slot.isHeld)
    }

    // ─── ModelTimestamps ────────────────────────────────────────────────────────────────

    @Test
    fun `mesmo quadro duas vezes devolve null`() {
        val timestamps = ModelTimestamps()

        assertEquals(1_000L, timestamps.next(1_000L))
        // O analisador pode receber o mesmo buffer duas vezes. Submeter o mesmo timestamp ao
        // LIVE_STREAM lançaria IllegalArgumentException.
        assertNull(timestamps.next(1_000L))
    }

    @Test
    fun `timestamps sao sempre estritamente crescentes para frente`() {
        val timestamps = ModelTimestamps()

        var previous = Long.MIN_VALUE
        listOf(10L, 40L, 70L, 100L, 130L).forEach { cameraMs ->
            val model = timestamps.next(cameraMs)
            assertNotNull(model)
            assertTrue("esperado > $previous, veio $model", model!! > previous)
            previous = model
        }
    }

    @Test
    fun `reinicio da camera com relogio menor e rebasado em vez de travar`() {
        val timestamps = ModelTimestamps()

        assertEquals(10_000L, timestamps.next(10_000L))
        assertEquals(10_033L, timestamps.next(10_033L))

        // A câmera reinicia (tela bloqueada, retorno de background) e o relógio volta a um
        // valor menor. Um guarda ingênuo `timestamp <= last → pula` descartaria então TODOS
        // os quadros da sessão nova, e o hand tracking morreria para sempre.
        val rebased = timestamps.next(5L)
        assertNotNull(rebased)
        assertTrue("esperado seguir em frente, veio $rebased", rebased!! > 10_033L)
    }

    @Test
    fun `depois de rebasear a sequencia continua normal`() {
        val timestamps = ModelTimestamps()
        timestamps.next(10_000L)
        val rebased = timestamps.next(5L)!!

        val next = timestamps.next(38L)
        assertNotNull(next)
        assertTrue("esperado > $rebased, veio $next", next!! > rebased)
    }
}
