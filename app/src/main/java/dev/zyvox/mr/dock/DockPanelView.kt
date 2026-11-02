package dev.zyvox.mr.dock

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * O dock flutuante: uma faixa compacta de botões, desenhada como View real para o
 * `ViewNode` transformar em textura 3D.
 *
 * ### Decisões de forma, vindas do requisito
 * - **Pequeno e discreto**: [DOCK_WIDTH_PX] × [DOCK_HEIGHT_PX] resultam em ~0,34 m × 0,09 m
 *   no mundo a 0,45 m de distância — ocupa uma faixa estreita na parte de baixo do campo de
 *   visão, não a tela inteira.
 * - **Fundo escuro com transparência e efeito de vidro**: ARGB com alpha ~0xD9 sobre a cena,
 *   borda clara de 1 px e cantos bem arredondados. Não há blur de verdade aqui (exigiria
 *   shader próprio no Filament); o que entrega a leitura de "vidro" é a transparência +
 *   borda luminosa + canto arredondado, que é como a referência faz também em escala de
 *   telefone.
 * - **Ícones simples**: glifos de texto em vez de vetores. Mantém o tamanho do painel
 *   previsível e evita um drawable por controle.
 *
 * ### Por que View e não Compose
 * Mesmo motivo do [dev.zyvox.mr.window.WindowPanelView]: o `viewContent` do `ViewNode` não
 * herda `CompositionLocal` nenhum, então o tema teria de ser re-aplicado à mão. View puro
 * também dá acesso direto às views-alvo, que é o que o injetor de toque precisa.
 */
class DockPanelView(context: Context) : FrameLayout(context) {

    /**
     * Ações que o dock expõe.
     *
     * `YOUTUBE` e `CHROME` substituem o antigo `BROWSER` genérico, que abria
     * `https://example.com` — um placeholder de desenvolvimento no lugar de um app. O pedido
     * foi explícito por esses dois, e faz sentido: são os dois destinos que alguém aponta um
     * navegador de MR para assistir ou ler, e cada um abre como janela WebView independente.
     */
    enum class Action { YOUTUBE, CHROME, NEW_WINDOW, RECENTER, DOCK_SMALLER, DOCK_BIGGER, CLOSE_ALL, TOGGLE_DOCK }

    var onAction: ((Action) -> Unit)? = null

    private val buttons = mutableMapOf<Action, View>()

    init {
        layoutParams = LayoutParams(DOCK_WIDTH_PX, DOCK_HEIGHT_PX)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, DOCK_CORNER_PX)
            }
        }
        background = dockBackground()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Ordem pensada para o polegar: o que se usa mais fica ao alcance do centro.
        val items = ACTION_ORDER.map { action ->
            Triple(action, GLYPHS.getValue(action), LABELS.getValue(action))
        }

        items.forEach { (action, glyph, label) ->
            val button = dockButton(glyph, label) { onAction?.invoke(action) }
            buttons[action] = button
            row.addView(button)
        }
        addView(row)
    }

    /** Realça um botão enquanto o cursor da mão está sobre ele. */
    fun setHighlighted(action: Action?, enabled: Boolean) {
        buttons.values.forEach { it.background = buttonBackground(highlight = false) }
        if (enabled) buttons[action]?.background = buttonBackground(highlight = true)
    }

    private fun dockButton(glyph: String, label: String, onClick: () -> Unit): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            background = buttonBackground(highlight = false)
            setPadding(0, PAD_PX, 0, PAD_PX)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            // Sem isto o TalkBack não anuncia nada: o dock inteiro seria invisível.
            contentDescription = label
        }
        cell.addView(
            TextView(context).apply {
                text = glyph
                setTextColor(GLYPH_COLOR)
                textSize = 40f
                gravity = Gravity.CENTER
            }
        )
        cell.addView(
            TextView(context).apply {
                text = label
                setTextColor(LABEL_COLOR)
                textSize = 18f
                gravity = Gravity.CENTER
                maxLines = 1
            }
        )
        return cell
    }

    private fun dockBackground() = GradientDrawable().apply {
        setColor(DOCK_COLOR)
        cornerRadius = DOCK_CORNER_PX
        setStroke(2, DOCK_BORDER_COLOR)
    }

    private fun buttonBackground(highlight: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(if (highlight) BUTTON_HIGHLIGHT else BUTTON_IDLE)
        cornerRadius = BUTTON_CORNER_PX
    }

    companion object {
        /**
         * Ordem dos botões, da esquerda para a direita — **fonte única**.
         *
         * O [dev.zyvox.mr.spatial.SpatialInputRouter] divide a largura do dock por esta lista
         * para decidir qual botão o cursor está apontando. Antes a ordem existia duas vezes,
         * uma aqui e uma hardcoded no router: adicionar YouTube e Chrome numa e esquecer a outra
         * faria o toque disparar o botão vizinho, sem nenhum erro em lugar algum. Derivar o
         * layout daqui e o router ler daqui é o que impede essa divergência.
         *
         * A ordem põe os destinos primeiro (é o que se usa mais), depois o controle de tamanho
         * do dock, e por último o que destrói estado — longe do polegar, porque "Fechar tudo"
         * ao lado de "YouTube" é um clique acidental esperando para acontecer.
         */
        val ACTION_ORDER: List<Action> = listOf(
            Action.YOUTUBE,
            Action.CHROME,
            Action.NEW_WINDOW,
            Action.RECENTER,
            Action.DOCK_SMALLER,
            Action.DOCK_BIGGER,
            Action.CLOSE_ALL,
            Action.TOGGLE_DOCK,
        )

        // Glifos escolhidos por estarem presentes em qualquer fonte de sistema. Evitamos
        // emoji: depende de fonte colorida e o tamanho rendered varia entre aparelhos.
        private val GLYPHS: Map<Action, String> = mapOf(
            Action.YOUTUBE to "▶",
            Action.CHROME to "◎",
            Action.NEW_WINDOW to "＋",
            Action.RECENTER to "⌖",
            Action.DOCK_SMALLER to "−",
            Action.DOCK_BIGGER to "＋",
            Action.CLOSE_ALL to "✕",
            Action.TOGGLE_DOCK to "⌄",
        )

        private val LABELS: Map<Action, String> = mapOf(
            Action.YOUTUBE to "YouTube",
            Action.CHROME to "Chrome",
            Action.NEW_WINDOW to "Janela",
            Action.RECENTER to "Centrar",
            Action.DOCK_SMALLER to "Menor",
            Action.DOCK_BIGGER to "Maior",
            Action.CLOSE_ALL to "Fechar",
            Action.TOGGLE_DOCK to "Ocultar",
        )

        const val DOCK_WIDTH_PX = 1400
        const val DOCK_HEIGHT_PX = 190

        // A largura do dock em METROS mora em `MrTokens.DOCK_WIDTH_M`, não aqui. Ela já esteve
        // neste companion object, e foi exatamente isso que quebrou `DockPanelHost` e
        // `SpatialInputRouter`: ambos liam `MrTokens.DOCK_WIDTH_M`, que não existia, enquanto o
        // valor real estava aqui. Deixar as duas cópias "por garantia" é o que produz a próxima
        // divergência — 0,34 m é geometria espacial, que é o assunto do MrTokens; aqui ficam só os
        // tamanhos em pixel, que são detalhe de layout desta view.

        const val DOCK_CORNER_PX = 60f
        private const val BUTTON_CORNER_PX = 28f
        private const val PAD_PX = 14

        // ARGB: alpha alto o bastante para o glifo ficar legível sobre o passthrough,
        // baixo o bastante para a cena aparecer através do dock.
        private const val DOCK_COLOR = 0xD9141821.toInt()
        private const val DOCK_BORDER_COLOR = 0x40FFFFFF
        private const val BUTTON_IDLE = 0x00FFFFFF
        private const val BUTTON_HIGHLIGHT = 0x337DD3FC
        private const val GLYPH_COLOR = 0xFFE8F1FA.toInt()
        private const val LABEL_COLOR = 0xFF9FB0C4.toInt()

        // Os glifos e rótulos por ação moram nos mapas `GLYPHS`/`LABELS` acima, junto com
        // `ACTION_ORDER`. Estavam aqui como constantes soltas (`GLYPH_BROWSER`, `LABEL_HIDE`,
        // …) e foi assim que a ordem dos botões acabou duplicada entre esta view e o
        // `SpatialInputRouter`: cada lado listava as ações do seu jeito. Um mapa indexado por
        // `Action` torna impossível adicionar um botão num lugar e esquecer no outro — o
        // compilador exige a entrada nos dois mapas.
    }
}
