package dev.zyvox.mr

import android.app.Application

/**
 * `Application` mínima.
 *
 * Existe por um motivo concreto: o `WebView` precisa ser inicializado no processo do app, e
 * em alguns aparelhos a primeira criação de `WebView` dentro de um `ViewNode` off-screen
 * falha se o provider do WebView ainda não foi carregado. Tocar `WebView` uma vez aqui, na
 * thread principal e no momento certo, evita essa classe de falha intermitente.
 *
 * Se isso vier a causar custo de cold start mensurável, é seguro remover — o restante do app
 * não depende desta classe.
 */
class ZyvoxMrApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Aquece o provider do WebView. `runCatching` porque num aparelho sem WebView
        // (ou com provider desatualizado) isto lança, e o app deve seguir abrindo — só sem
        // as janelas, o que a UI já reporta.
        runCatching { android.webkit.WebView(this).destroy() }
    }
}
