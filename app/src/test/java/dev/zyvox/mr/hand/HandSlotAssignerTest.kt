package dev.zyvox.mr.hand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da atribuição de slot às duas mãos.
 *
 * O defeito que isto impede: o cursor pular de uma mão para a outra no meio de um arrasto,
 * fazendo o objeto ser solto sem o usuário ter aberto o gesto.
 */
class HandSlotAssignerTest {

    private val assigner = HandSlotAssigner(slotCount = 2)

    @Test
    fun `lista vazia nao produz slot nenhum`() {
        assertEquals(emptyMap<Int, Int>(), assigner.assign(emptyList()))
    }

    @Test
    fun `uma mao so recebe o slot 0`() {
        val result = assigner.assign(listOf(0.5f))
        assertEquals(mapOf(0 to 0), result)
    }

    @Test
    fun `a mao mais a esquerda recebe o slot 0`() {
        // Índice 0 = mão direita da tela (x alto), índice 1 = mão esquerda (x baixo).
        val result = assigner.assign(listOf(0.8f, 0.2f))

        assertEquals("a mais à esquerda (índice 1) deve ficar no slot 0", 0, result[1])
        assertEquals("a mais à direita (índice 0) deve ficar no slot 1", 1, result[0])
    }

    @Test
    fun `slots nunca se repetem`() {
        val result = assigner.assign(listOf(0.3f, 0.7f))
        val slots = result.values.toList()

        assertEquals(2, slots.size)
        assertNotEquals("cada mão precisa de um slot próprio", slots[0], slots[1])
        assertTrue(slots.all { it in 0..1 })
    }

    @Test
    fun `atribuicao e estavel quando as maos trocam de ordem na entrada`() {
        // O MediaPipe não garante a ordem das mãos entre quadros. Se a atribuição dependesse
        // do índice de entrada, o cursor trocaria de mão a cada quadro — e o objeto arrastado
        // seria solto.
        val frameA = assigner.assign(listOf(0.2f, 0.8f))
        val frameB = assigner.assign(listOf(0.8f, 0.2f))

        // Em A, o índice 0 é a mão esquerda. Em B, o índice 1 é. Ambos devem cair no slot 0.
        assertEquals(0, frameA[0])
        assertEquals(0, frameB[1])
    }

    @Test
    fun `mais maos que o limite sao truncadas sem repetir slot`() {
        val assigner3 = HandSlotAssigner(slotCount = 2)
        val result = assigner3.assign(listOf(0.1f, 0.5f, 0.9f))

        assertEquals("só 2 mãos devem ser atendidas", 2, result.size)
        assertEquals(setOf(0, 1), result.values.toSet())
    }

    @Test
    fun `x iguais nao lancam nem produzem slot duplicado`() {
        // Duas mãos exatamente sobrepostas (cruzadas na frente da câmera) é um caso real.
        val result = assigner.assign(listOf(0.5f, 0.5f))

        assertEquals(2, result.size)
        assertNotEquals(result.values.toList()[0], result.values.toList()[1])
    }
}
