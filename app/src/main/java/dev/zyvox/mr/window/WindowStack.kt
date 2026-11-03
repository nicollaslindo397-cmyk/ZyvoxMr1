package dev.zyvox.mr.window

/**
 * Estado de uma janela MR. Imutável — a mutação passa por [WindowStack], que publica uma
 * lista nova. Isso evita a classe de bug em que o painel 3D e o estado divergem porque um
 * deles foi alterado de lado.
 *
 * @param widthMeters/heightMeters tamanho do quad no mundo, em metros. A relação com pixels
 *   é fixada por `ViewNode.pxPerUnits` (250 px por metro em escala 1): um painel de 0,64 m
 *   de largura desenha 160 px de conteúdo. Manter os dois em sincronia é responsabilidade de
 *   [WindowPanel].
 * @param depthMeters distância à frente do usuário. 0,55 m é o ponto em que um painel de
 *   ~0,6 m ocupa um campo visual confortável sem exigir afastar o aparelho do rosto.
 */
data class MrWindow(
    val id: String,
    val url: String,
    val title: String,
    val xMeters: Float,
    val yMeters: Float,
    val depthMeters: Float,
    val widthMeters: Float,
    val heightMeters: Float,
    val minimized: Boolean,
) {
    /** Canto inferior direito, usado como alça de redimensionamento. */
    val rightMeters: Float get() = xMeters + widthMeters / 2f
    val bottomMeters: Float get() = yMeters - heightMeters / 2f
    val leftMeters: Float get() = xMeters - widthMeters / 2f
    val topMeters: Float get() = yMeters + heightMeters / 2f

    /** Altura da barra de título, em metros. Fixa: não escala com a janela. */
    val titleBarMeters: Float get() = TITLE_BAR_METERS

    companion object {
        /**
         * Altura da barra de título no mundo.
         *
         * Era 0.045f, que contra a altura antiga de 0.40 m dava 11,25% da janela. A barra real
         * tem 88 px de 800, ou seja 11%. Escalar a janela sem escalar isto faria a barra virar
         * uma fatia cada vez menor — e `WindowController` classifica o toque por
         * `titleBarMeters / heightMeters`, então a área de arrastar encolheria junto. Mantida a
         * proporção de 11% da altura padrão.
         */
        const val TITLE_BAR_METERS = 0.058f

        /**
         * Largura padrão da janela no mundo. Era 0.64f.
         *
         * Subiu porque o relato de aparelho foi explícito ("a interface tá cada vez mais
         * pequena … aumente o tamanho"). A altura é derivada em [DEFAULT_HEIGHT], então a
         * proporção do conteúdo se mantém sozinha.
         */
        const val DEFAULT_WIDTH = 0.82f

        /**
         * Derivada da largura, nunca um literal independente.
         *
         * Aqui estava `0.44f`, e o conteúdo é 1280×800 — proporção 1.6, não 0.64/0.44 = 1.4545.
         * O certo é 0.64/1.6 = 0.40. As duas consequências são visíveis, não teóricas:
         *
         * 1. O quad esticava a página em ~10% na vertical. `ViewNode.pxPerUnits` é um escalar
         *    único, então um quad com proporção diferente da view deforma o conteúdo — texto e
         *    imagens saíam verticalmente esticados em toda janela recém-aberta.
         * 2. O mapeamento de toque errava no eixo y. `pxPerUnitsFor` deriva da LARGURA
         *    (`CONTENT_WIDTH_PX / widthMeters`) e é aplicado aos dois eixos; com a altura
         *    inconsistente, `v` calculado a partir de metros não batia com o pixel real da view,
         *    e o toque saía ~9% deslocado — pior quanto mais longe da barra de título.
         *
         * `resizeBy` nunca teve esse problema porque deriva `newH = newW / ASPECT`. O bug era só
         * no estado inicial, o que explica o sintoma estranho: a primeira redimensionada
         * "consertava" a janela. Derivar aqui elimina a classe inteira de divergência, em vez de
         * trocar 0.44 por 0.40 e deixar a mesma armadilha armada.
         */
        const val DEFAULT_HEIGHT = DEFAULT_WIDTH / WindowMetrics.ASPECT
        const val DEFAULT_DEPTH = 0.55f

        /** Era 0.22f. Subiu junto com o padrão: um mínimo de 0,22 m num app cujo padrão passou a 0,82 m deixaria a janela encolher até ficar ilegível. */
        const val MIN_WIDTH = 0.34f
        const val MAX_WIDTH = 2.2f

        // MIN_HEIGHT e MAX_HEIGHT foram removidos: não eram lidos em lugar nenhum — `resizeBy`
        // restringe só a largura e deriva a altura — e qualquer valor solto ali contradiria a
        // proporção assim que alguém os usasse. Se um dia precisar de limite de altura, escreva
        // `MIN_WIDTH / WindowMetrics.ASPECT`; não um número.
    }
}

/**
 * Pilha de janelas: criação, foco, movimento, redimensionamento e minimização.
 *
 * Kotlin puro (nenhuma dependência de Android), porque é aqui que mora a lógica de
 * multitarefa que o usuário vai exigir — e ela precisa ser testável sem aparelho.
 *
 * ### Limite de janelas
 * Cada janela é um `WebView` de verdade, desenhado off-screen e enviado como textura. WebView
 * é pesado (dezenas de MB cada) e o caminho de renderização do `ViewNode` re-medida e
 * redesenha a view a cada invalidação. Sem teto, abrir dez janelas derruba o app por memória
 * muito antes de derrubar por GPU. [maxWindows] é o freio, e a UI avisa quando ele é atingido
 * em vez de silenciosamente ignorar o clique.
 */
class WindowStack(private val maxWindows: Int = 4) {

    private val windows = mutableListOf<MrWindow>()
    private var sequence = 0

    /** Janela no topo (a que recebe o gesto quando houver sobreposição). */
    var focusedId: String? = null
        private set

    /** Cópia imutável, na ordem de desenho (topo por último). */
    val all: List<MrWindow> get() = windows.toList()

    val count: Int get() = windows.size
    val isFull: Boolean get() = windows.size >= maxWindows

    /**
     * Abre uma janela nova, centrada e levemente deslocada das existentes para que duas
     * janelas abertas em sequência não fiquem exatamente sobrepostas.
     *
     * @return a janela criada, ou `null` se o limite foi atingido.
     */
    fun open(url: String, title: String = url): MrWindow? {
        if (isFull) return null
        sequence++
        // Deslocamento em cascata: cada janela nova aparece um pouco à direita e acima da
        // anterior. Sem isso, a segunda janela cobre a primeira por completo e o usuário
        // conclui que "abrir várias não funciona".
        val offset = ((sequence - 1) % 5) * CASCADE_STEP
        val window = MrWindow(
            id = "win-$sequence",
            url = url,
            title = title,
            xMeters = -0.10f + offset,
            yMeters = 0.02f + offset * 0.6f,
            depthMeters = MrWindow.DEFAULT_DEPTH,
            widthMeters = MrWindow.DEFAULT_WIDTH,
            heightMeters = MrWindow.DEFAULT_HEIGHT,
            minimized = false,
        )
        windows += window
        focusedId = window.id
        return window
    }

    fun close(id: String) {
        windows.removeAll { it.id == id }
        if (focusedId == id) focusedId = windows.lastOrNull()?.id
    }

    fun closeAll() {
        windows.clear()
        focusedId = null
    }

    fun focus(id: String) {
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0) return
        // Reordenar para o fim da lista = topo da pilha de desenho.
        windows.add(windows.removeAt(index))
        focusedId = id
    }

    fun toggleMinimize(id: String) {
        replace(id) { it.copy(minimized = !it.minimized) }
    }

    fun restore(id: String) {
        replace(id) { it.copy(minimized = false) }
        focus(id)
    }

    /** Move a janela por um delta em metros. Limita para não sair totalmente do alcance. */
    fun moveBy(id: String, dx: Float, dy: Float) {
        replace(id) {
            it.copy(
                xMeters = (it.xMeters + dx).coerceIn(-REACH, REACH),
                yMeters = (it.yMeters + dy).coerceIn(-REACH, REACH),
            )
        }
    }

    fun moveTo(id: String, x: Float, y: Float) {
        replace(id) {
            it.copy(xMeters = x.coerceIn(-REACH, REACH), yMeters = y.coerceIn(-REACH, REACH))
        }
    }

    /**
     * Redimensiona a partir do canto inferior direito, **preservando a proporção**.
     *
     * ### Por que proporcional e não livre
     * O conteúdo da janela é uma View de tamanho fixo em pixel ([WindowMetrics.CONTENT_WIDTH_PX]
     * × [WindowMetrics.CONTENT_HEIGHT_PX]) e o tamanho no mundo é derivado dela por
     * `pxPerUnits = widthPx / widthMeters`. Se largura e altura variassem de forma
     * independente, `pxPerUnits` daria um valor por eixo e o quad esticaria a página — texto
     * distorcido, e o mapeamento de toque errado num dos dois eixos.
     *
     * Proporcional mantém um único `pxPerUnits` válido, o texto nítido em qualquer tamanho e
     * o toque alinhado. É o mesmo contrato de um player de vídeo: redimensiona, não deforma.
     *
     * Mantém o canto inferior direito sob o cursor (não o centro), porque é onde o usuário
     * está apontando — se o centro ficasse fixo, a alça fugiria da mão enquanto ele arrasta.
     */
    fun resizeBy(id: String, dWidth: Float, dHeight: Float) {
        replace(id) {
            // Usa a diagonal do movimento como sinal de escala, para que arrastar na
            // diagonal cresça e arrastar de volta encolha — em vez de responder só ao eixo x.
            val grow = (dWidth - dHeight) / 2f
            val newW = (it.widthMeters + grow).coerceIn(MrWindow.MIN_WIDTH, MrWindow.MAX_WIDTH)
            val newH = newW / WindowMetrics.ASPECT

            // O canto inferior direito estava em (right, bottom) e deve ir para
            // (right + dWidth, bottom - dHeight) aproximadamente. Como o quad é centrado,
            // mover o centro em metade da variação de tamanho desloca o canto em metade —
            // então compensamos com a outra metade para o canto seguir a mão de fato.
            val dW = newW - it.widthMeters
            val dH = newH - it.heightMeters
            it.copy(
                widthMeters = newW,
                heightMeters = newH,
                xMeters = (it.xMeters + dW / 2f).coerceIn(-REACH, REACH),
                yMeters = (it.yMeters - dH / 2f).coerceIn(-REACH, REACH),
            )
        }
    }

    /**
     * `pxPerUnits` do `ViewNode` desta janela: quantos pixels de conteúdo cabem por metro.
     *
     * Derivado da largura — e válido para a altura também, justamente porque
     * [resizeBy] preserva a proporção.
     */
    fun pxPerUnitsFor(window: MrWindow): Float =
        WindowMetrics.CONTENT_WIDTH_PX / window.widthMeters

    fun setTitle(id: String, title: String) {
        if (title.isBlank()) return
        replace(id) { it.copy(title = title) }
    }

    fun setUrl(id: String, url: String) {
        replace(id) { it.copy(url = url) }
    }

    /** Reposiciona tudo para o layout inicial centrado, preservando as URLs. */
    fun recenter() {
        val n = windows.size
        windows.forEachIndexed { index, w ->
            // Abre em leque horizontal quando há mais de uma, em vez de empilhar tudo no
            // mesmo ponto.
            val column = if (n == 1) 0f else (index - (n - 1) / 2f)
            windows[index] = w.copy(
                xMeters = column * (MrWindow.DEFAULT_WIDTH + GAP),
                yMeters = 0f,
                depthMeters = MrWindow.DEFAULT_DEPTH,
                minimized = false,
            )
        }
        focusedId = windows.lastOrNull()?.id
    }

    /**
     * Janela cujo quad contém o ponto, procurando do **topo da pilha para baixo**.
     *
     * A ordem importa: com janelas sobrepostas, o gesto deve atingir a que está por cima,
     * que é a que o usuário vê. Iterar na ordem de desenho pegaria a de baixo.
     */
    fun windowAt(xMeters: Float, yMeters: Float): MrWindow? {
        for (index in windows.indices.reversed()) {
            val w = windows[index]
            if (w.minimized) continue
            if (xMeters in w.leftMeters..w.rightMeters && yMeters in w.bottomMeters..w.topMeters) {
                return w
            }
        }
        return null
    }

    /** Região de uma janela que o ponto atinge. */
    fun regionAt(window: MrWindow, xMeters: Float, yMeters: Float): WindowRegion {
        // A alça de redimensionar tem prioridade sobre o corpo: é pequena e fica no canto,
        // então se o usuário mirou nela é porque quer ela.
        val inResizeGrip =
            xMeters > window.rightMeters - RESIZE_GRIP && yMeters < window.bottomMeters + RESIZE_GRIP
        if (inResizeGrip) return WindowRegion.RESIZE_GRIP

        return if (yMeters > window.topMeters - window.titleBarMeters) {
            WindowRegion.TITLE_BAR
        } else {
            WindowRegion.CONTENT
        }
    }

    private fun replace(id: String, transform: (MrWindow) -> MrWindow) {
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0) return
        windows[index] = transform(windows[index])
    }

    companion object {
        /** Deslocamento em cascata entre janelas abertas em sequência. */
        private const val CASCADE_STEP = 0.045f

        /** Espaço entre janelas no leque do [recenter]. */
        private const val GAP = 0.05f

        /** Até onde uma janela pode ser arrastada do centro, em metros. */
        private const val REACH = 1.4f

        /** Lado da alça de redimensionamento, em metros. */
        const val RESIZE_GRIP = 0.055f
    }
}

/** Região de uma janela, para o gesto saber o que fazer. */
enum class WindowRegion {
    /** Barra de título: arrastar move a janela. */
    TITLE_BAR,

    /** Corpo: o gesto vira toque no WebView. */
    CONTENT,

    /** Canto inferior direito: arrastar redimensiona. */
    RESIZE_GRIP,
}
