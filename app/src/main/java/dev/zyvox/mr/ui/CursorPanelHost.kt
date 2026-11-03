package dev.zyvox.mr.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.filament.Engine
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode

/**
 * O cursor da mão, como um pequeno quad 3D que vive no plano de interação.
 *
 * ### Por que 3D e não um overlay 2D em Compose
 * Um cursor desenhado em Compose fica *sempre* por cima de tudo, colado no vidro. Um cursor
 * no plano de interação tem profundidade real: ele fica **sobre** a janela que está apontando
 * e **atrás** de uma janela mais próxima. Essa diferença de oclusão é justamente o que faz a
 * cena ler como espacial em vez de como um HUD — e é o que a referência pede ao exigir
 * "profundidade" e "elementos estáveis na experiência".
 *
 * ### Por que pequeno
 * O requisito é explícito ("cursor pequeno e discreto"), e há um motivo prático: um cursor
 * grande cobre o alvo que o usuário está tentando acertar, o que piora a precisão percebida
 * mesmo quando o tracking é bom. [SIZE_PX] resulta em ~1,6 cm no mundo a 0,45 m.
 *
 * O anel de pinch é maior e aparece só durante o gesto: é a confirmação visual de que o
 * app reconheceu o pinch, sem custar área útil no resto do tempo.
 */
class CursorPanelHost(
    engine: Engine,
    materialLoader: MaterialLoader,
    private val root: Node,
    ownerView: View,
) {
    private val view = CursorView(materialLoader.context)
    private val windowManager = ViewNode.WindowManager(materialLoader.context)

    private val node: ViewNode = ViewNode(
        engine = engine,
        windowManager = windowManager,
        materialLoader = materialLoader,
        view = view,
        // `unlit`: o cursor precisa ter a mesma cor sempre. Se a luz da cena atuasse sobre
        // ele, ficaria escuro ao virar para longe da luz principal e o usuário perderia a
        // referência de onde está apontando.
        unlit = true,
    )

    init {
        // Ordem obrigatória: a janela off-screen precisa existir antes de o nó entrar na
        // cena, senão a textura nunca recebe pixel e o cursor é invisível.
        windowManager.resume(ownerView)
        root.addChildNode(node)
        node.pxPerUnits = PX_PER_UNITS
    }

    /**
     * @param pinching muda o cursor para o estado de gesto ativo (anel maior, cor de sucesso).
     */
    fun update(x: Float, y: Float, z: Float, visible: Boolean, pinching: Boolean) {
        node.isVisible = visible
        if (!visible) return
        node.position = Position(x, y, z)
        view.setPinching(pinching)
    }

    fun destroy() {
        runCatching { root.removeChildNode(node) }
        runCatching { windowManager.pause() }
        runCatching { windowManager.destroy() }
    }

    companion object {
        /** Diâmetro do ponto, em pixels da view off-screen. */
        const val SIZE_PX = 120

        /** Metros que o cursor ocupa no mundo. */
        const val SIZE_M = 0.016f

        private val PX_PER_UNITS = SIZE_PX / SIZE_M
    }
}

/**
 * A view do cursor: um ponto com um anel em volta.
 *
 * Desenhada com `GradientDrawable` em código em vez de XML para que a cor e o tamanho do
 * anel possam mudar por quadro sem inflar/invalidar drawable novo a cada vez. `setPinching`
 * troca só os drawables, que é barato.
 */
private class CursorView(context: Context) : FrameLayout(context) {

    private val dot = View(context)
    private val ring = View(context)

    private var pinching = false

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        ring.apply {
            layoutParams = LayoutParams(RING_PX, RING_PX)
            background = ringDrawable(pinching = false)
        }
        dot.apply {
            layoutParams = LayoutParams(DOT_PX, DOT_PX).apply {
                gravity = android.view.Gravity.CENTER
            }
            background = dotDrawable(pinching = false)
        }
        addView(ring)
        addView(dot)
    }

    fun setPinching(value: Boolean) {
        if (value == pinching) return
        pinching = value
        // Trocar o drawable só quando o estado mudou de fato: isto roda a cada quadro de
        // hand tracking, e alocar dois `GradientDrawable` por quadro aparece no GC.
        ring.background = ringDrawable(value)
        dot.background = dotDrawable(value)
        // A view está off-screen; sem invalidate explícito o `dispatchDraw` do ViewNode não
        // roda de novo e a textura continua mostrando o estado anterior.
        invalidate()
    }

    private fun dotDrawable(pinching: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (pinching) COLOR_PINCH else COLOR_IDLE)
        setStroke(3, COLOR_BORDER)
    }

    private fun ringDrawable(pinching: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x00000000)
        setStroke(if (pinching) 8 else 4, if (pinching) COLOR_PINCH_RING else COLOR_IDLE_RING)
    }

    private companion object {
        const val DOT_PX = 44
        const val RING_PX = 110

        const val COLOR_IDLE = 0xFFE8F1FA.toInt()
        const val COLOR_PINCH = 0xFF4ADE80.toInt()
        const val COLOR_BORDER = 0x66000000
        const val COLOR_IDLE_RING = 0x557DD3FC
        const val COLOR_PINCH_RING = 0x884ADE80.toInt()
    }
}
