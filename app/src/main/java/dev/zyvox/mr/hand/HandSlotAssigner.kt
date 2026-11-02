package dev.zyvox.mr.hand

/**
 * Atribui às mãos detectadas um **slot estável** (0 ou 1) entre quadros.
 *
 * ### Por que não usar a lateralidade do MediaPipe
 * O `Handedness` do modelo devolve `Left`/`Right` sob a convenção de que a imagem está
 * **espelhada** — o que é verdade para câmera frontal, mas não para a traseira, que é a
 * deste app. Na câmera traseira o rótulo sai invertido. Pior: ele pode trocar entre quadros
 * quando a mão gira, o que faria o cursor pular de uma mão para a outra no meio de um
 * arrasto.
 *
 * Então não dependemos do rótulo para nada além de exibir. O slot é atribuído por posição
 * na tela com histerese temporal: a mão mais à esquerda tende a continuar sendo o slot 0.
 *
 * ### Por que "mais à esquerda" e não "a mais próxima do quadro anterior"
 * Associação por proximidade (tracking greedy) é mais precisa, mas pode trocar os slots
 * quando as duas mãos se cruzam — e aí o objeto arrastado pula de mão. Fixar por lado
 * esquerdo/direito é previsível para o usuário e nunca troca no meio de um gesto, que é o
 * que importa para a interação. O custo é que cruzar as mãos troca os cursores, o que é
 * visível e compreensível.
 *
 * Kotlin puro, testável em JVM.
 */
class HandSlotAssigner(private val slotCount: Int = 2) {

    /**
     * Ordena as mãos por `x` e devolve os slots.
     *
     * @param candidates índice da mão no resultado do MediaPipe → centro `x` normalizado.
     * @return mapa índice da mão → slot. Sempre `0..slotCount-1`, sem repetição.
     */
    fun assign(candidates: List<Float>): Map<Int, Int> {
        if (candidates.isEmpty()) return emptyMap()
        val take = candidates.size.coerceAtMost(slotCount)
        return candidates.indices
            .sortedBy { candidates[it] }
            .take(take)
            .withIndex()
            .associate { (slot, handIndex) -> handIndex to slot }
    }
}
