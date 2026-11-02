package dev.zyvox.mr.spatial

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Transforma o gesto de pinch da mão num stream `DOWN → MOVE → UP` despachado numa View.
 *
 * ### Por que isto existe
 * Um `ViewNode` desenha sua View numa textura; ela **não está na tela**, então o Android
 * nunca despacha toque nela. O SceneView resolve isso para toque de dedo de verdade
 * (convertendo o picking da cena em pixel e encaminhando — `ViewTouchForwarder`). Mas aqui a
 * entrada é a **mão**, não o dedo: não existe `MotionEvent` de origem para encaminhar. Então
 * sintetizamos um.
 *
 * O mecanismo é exatamente o que o `ViewTouchForwarder` do SceneView usa por dentro:
 * `MotionEvent.obtain` → `setLocation(px, py)` → `view.dispatchTouchEvent`. É público e
 * estável, então não dependemos de nada `internal` da biblioteca.
 *
 * ### A máquina de estados é o ponto crítico
 * Despachar `DOWN` duas vezes, ou nunca despachar `UP`, deixa o WebView com um pointer
 * preso: a página fica em estado de arrasto, links disparam sozinhos, o scroll trava. As
 * regras que mantêm isso íntegro:
 *
 * 1. `DOWN` só quando não há stream vivo.
 * 2. Todo stream vivo recebe `UP` ou `CANCEL` antes do próximo `DOWN`.
 * 3. Sair da área da janela enquanto arrasta → `CANCEL` (não `UP`), que é o que o Android
 *    faz quando um dedo desliza para fora de um botão: cancela a ação em vez de confirmá-la.
 * 4. `UP` só se o pointer ainda está dentro dos limites — senão vira `CANCEL`.
 *
 * ### `recycle()`
 * Todo `MotionEvent` obtido é reciclado em `finally`. São objetos de um pool nativo; vazar
 * alguns por segundo esgota o pool e o app passa a receber eventos nulos.
 */
class SyntheticTouch(
    private val view: View,
    /** ID de pointer constante: é sempre "o dedo" da mão que está fazendo pinch. */
    private val pointerId: Int = 0,
) {

    private var streamLive = false

    /** Último pixel despachado, para decidir se um `MOVE` vale a pena. */
    private var lastX = 0f
    private var lastY = 0f

    private val touchSlopPx =
        ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()

    /** Há um gesto em andamento nesta View. */
    val isTouching: Boolean get() = streamLive

    /**
     * Inicia um toque em ([x], [y]), em pixel **da View alvo**.
     *
     * Se já houver stream vivo, ele é cancelado primeiro (regra 2) — pode acontecer se o
     * usuário fez pinch, moveu a mão para fora e voltou sem abrir o gesto.
     */
    fun down(x: Float, y: Float) {
        if (streamLive) cancel()
        dispatch(MotionEvent.ACTION_DOWN, x, y)
        streamLive = true
        lastX = x
        lastY = y
    }

    /**
     * Move o toque. Ignora deslocamentos menores que o touch slop: o WebView trata qualquer
     * `MOVE` como intenção de scroll, e o jitter do landmark (subpixel) faria a página
     * tremer sem o usuário ter movido a mão.
     */
    fun move(x: Float, y: Float) {
        if (!streamLive) return
        val dx = x - lastX
        val dy = y - lastY
        if (dx * dx + dy * dy < touchSlopPx * touchSlopPx) return
        dispatch(MotionEvent.ACTION_MOVE, x, y)
        lastX = x
        lastY = y
    }

    /**
     * Libera o toque, confirmando a ação (clique). Só confirma se o pointer ainda está
     * dentro dos limites da View — fora deles, o Android esperaria um cancelamento.
     */
    fun up(x: Float, y: Float) {
        if (!streamLive) return
        val inside = x >= 0f && y >= 0f && x <= view.width && y <= view.height
        dispatch(if (inside) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, x, y)
        streamLive = false
    }

    /** Cancela sem confirmar. Usado ao sair da janela no meio de um arrasto. */
    fun cancel() {
        if (!streamLive) return
        dispatch(MotionEvent.ACTION_CANCEL, lastX, lastY)
        streamLive = false
    }

    private fun dispatch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply {
            id = pointerId
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            // Pressão e tamanho não-nulos: alguns handlers de página (e o próprio
            // `GestureDetector` do Android) ignoram um pointer com pressão 0.
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 0.02f
        }
        val event = MotionEvent.obtain(
            /* downTime = */ now,
            /* eventTime = */ now,
            action,
            /* pointerCount = */ 1,
            arrayOf(properties),
            arrayOf(coordinates),
            /* metaState = */ 0,
            /* buttonState = */ 0,
            /* xPrecision = */ 1f,
            /* yPrecision = */ 1f,
            /* deviceId = */ 0,
            /* edgeFlags = */ 0,
            /* source = */ android.view.InputDevice.SOURCE_TOUCHSCREEN,
            /* flags = */ 0,
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
