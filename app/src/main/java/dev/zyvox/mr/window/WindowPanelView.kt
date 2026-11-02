package dev.zyvox.mr.window

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Constrói a hierarquia de Views **reais** que o `ViewNode` desenha numa textura: uma barra
 * de título com minimizar/fechar, e um `WebView` embaixo.
 *
 * ### Por que Views reais e não Compose
 * O `viewContent` de um `ViewNode` compõe numa janela off-screen própria e **não herda
 * nenhum `CompositionLocal`** do app (nem `MaterialTheme`). Dentro dela é preciso re-aplicar
 * o tema à mão. Construir a hierarquia em View puro evita essa armadilha inteira, dá acesso
 * direto ao `WebView` (necessário para injetar o toque da mão) e não depende de API de
 * material do Filament — menos superfície para erro num projeto que não posso compilar aqui.
 *
 * ### Por que tamanho em pixel explícito
 * Ver [WindowMetrics]: o `WindowManager` mede com `AT_MOST(display)`, então `WRAP_CONTENT`
 * ou `fillMaxWidth` produzem um quad de metros de largura. E um `WindowManager` compartilhado
 * se dimensiona pelo maior filho, arrastando os outros painéis. Daí tamanho fixo e um
 * manager por janela.
 */
class WindowPanelView(context: Context) : FrameLayout(context) {

    /** O WebView, exposto para [dev.zyvox.mr.spatial.SyntheticTouch] injetar gesto da mão. */
    val webView: WebView

    private val titleView: TextView
    private val root: LinearLayout

    /** Chamado quando o usuário toca minimizar. */
    var onMinimize: (() -> Unit)? = null

    /** Chamado quando o usuário toca fechar. */
    var onClose: (() -> Unit)? = null

    /** Chamado quando o WebView resolve um título de página. */
    var onTitle: ((String) -> Unit)? = null

    init {
        // Tamanho explícito e fixo — ver KDoc da classe.
        layoutParams = LayoutParams(
            WindowMetrics.CONTENT_WIDTH_PX,
            WindowMetrics.CONTENT_HEIGHT_PX,
        )
        // O outline arredonda o canto do painel inteiro; sem isto o "vidro" fica com cantos
        // retos e o efeito de recorte não acompanha o fundo desenhado.
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, CORNER_RADIUS_PX)
            }
        }
        background = panelBackground()

        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        titleView = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 30f
            maxLines = 1
            setSingleLine(true)
            // Elipsize no meio preserva o domínio, que é a parte útil de uma URL longa.
            ellipsize = android.text.TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { gravity = Gravity.CENTER_VERTICAL }
            setPadding(PAD_PX, 0, PAD_PX, 0)
        }

        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = titleBarBackground()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowMetrics.TITLE_BAR_PX,
            )
            setPadding(PAD_PX, 0, PAD_PX / 2, 0)
        }

        titleBar.addView(titleView)
        titleBar.addView(iconButton(BUTTON_MINIMIZE, minimizeTint) { onMinimize?.invoke() })
        titleBar.addView(iconButton(BUTTON_CLOSE, closeTint) { onClose?.invoke() })

        webView = createWebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setBackgroundColor(Color.WHITE)
        }

        root.addView(titleBar)
        root.addView(webView)
        addView(root)
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun load(url: String) {
        val normalized = normalizeUrl(url)
        webView.loadUrl(normalized)
    }

    /** Libera o WebView de verdade. Sem isto cada janela fechada vaza dezenas de MB. */
    fun destroyWebView() {
        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }
    }

    /**
     * O WebView é criado off-screen e nunca recebe toque do Android — a interação vem da
     * mão, via [dev.zyvox.mr.spatial.SyntheticTouch]. Então desligamos o que só serve para
     * toque de dedo e ligamos o que a página precisa para funcionar.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Sem isto muito site moderno não renderiza (usa viewport meta).
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                // Sites móveis: sem isto recebemos o layout de desktop, ilegível num painel
                // de 0,64 m visto a 55 cm.
                userAgentString = settings.userAgentString + " ZyvoxMR/0.1"
                cacheMode = WebSettings.LOAD_DEFAULT
                // `usesCleartextTraffic=true` no manifest permite http:// explícito; útil
                // para testar contra um servidor local na rede.
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }

            // Manter a navegação DENTRO do painel. Sem este client, um link abre a
            // Activity do navegador do sistema e a experiência MR sai de foco.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url?.toString() ?: return false
                    // `intent:` e `market:` não têm o que fazer dentro de um painel MR.
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        view.loadUrl(url)
                        return true
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (!title.isNullOrBlank()) onTitle?.invoke(title)
                }
            }
        }
    }

    private fun iconButton(
        symbol: String,
        tint: Int,
        onClick: () -> Unit,
    ): ImageButton {
        // Botão desenhado com texto em vez de ícone vetorial: evita depender de um drawable
        // por controle e mantém o tamanho do painel previsível. O símbolo é um glifo comum
        // (─ e ✕), não um emoji, para não depender de fonte colorida.
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(BUTTON_PX, BUTTON_PX).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = PAD_PX / 2
            }
            background = circleButtonBackground(tint)
            // Um ImageButton sem contentDescription é invisível ao TalkBack.
            contentDescription = symbol
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun panelBackground() = GradientDrawable().apply {
        // Vidro escuro: fundo quase opaco o suficiente para o texto do site ficar legível
        // sobre o passthrough, mas com transparência para a cena aparecer nas bordas.
        setColor(PANEL_COLOR)
        cornerRadius = CORNER_RADIUS_PX
        setStroke(3, BORDER_COLOR)
    }

    private fun titleBarBackground() = GradientDrawable().apply {
        setColor(TITLE_BAR_COLOR)
        cornerRadii = floatArrayOf(
            CORNER_RADIUS_PX, CORNER_RADIUS_PX,
            CORNER_RADIUS_PX, CORNER_RADIUS_PX,
            0f, 0f, 0f, 0f,
        )
    }

    private fun circleButtonBackground(tint: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(tint)
    }

    private companion object {
        const val CORNER_RADIUS_PX = 44f
        const val PAD_PX = 28
        const val BUTTON_PX = 56

        const val BUTTON_MINIMIZE = "minimizar"
        const val BUTTON_CLOSE = "fechar"

        // ARGB. Cores escuras com alpha < 255 = o efeito de vidro pedido na referência.
        const val PANEL_COLOR = 0xE6101418.toInt()
        const val TITLE_BAR_COLOR = 0xF21A1F26.toInt()
        const val BORDER_COLOR = 0x33FFFFFF

        const val minimizeTint = 0xFF3A4250.toInt()
        const val closeTint = 0xFF7A2E36.toInt()
    }
}

/**
 * Normaliza o que o usuário digitou em algo que o WebView aceita.
 *
 * Sem isto, digitar "exemplo.com" falha: o `WebView.loadUrl` trata string sem esquema como
 * caminho relativo. E digitar uma frase deve virar busca, não URL quebrada.
 */
fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "about:blank"
    if (trimmed.startsWith("about:") ||
        trimmed.startsWith("http://") ||
        trimmed.startsWith("https://") ||
        trimmed.startsWith("file:")
    ) {
        return trimmed
    }
    // Tem cara de host (ponto, sem espaço) → assume https.
    val looksLikeHost = !trimmed.contains(' ') && trimmed.contains('.')
    return if (looksLikeHost) {
        "https://$trimmed"
    } else {
        // Frase → busca. DuckDuckGo por padrão: não exige consentimento de cookie na UE e
        // renderiza leve, o que importa num painel pequeno.
        "https://duckduckgo.com/?q=" + android.net.Uri.encode(trimmed)
    }
}
