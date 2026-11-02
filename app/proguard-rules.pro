# O Filament e o MediaPipe acessam nativos via JNI com nomes que o R8 não vê.
# Sem essas regras a build release quebra em runtime com UnsatisfiedLinkError.
-keep class com.google.android.filament.** { *; }
-keep class com.google.ar.core.** { *; }
-keep class com.google.mediapipe.** { *; }

# O MediaPipe carrega o modelo por reflection a partir do path em assets.
-keepclassmembers class com.google.mediapipe.tasks.** { *; }

# WebView: os callbacks @JavascriptInterface precisam sobreviver à ofuscação.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# SceneView usa reflection para os nós e para o WindowManager do ViewNode.
-keep class io.github.sceneview.** { *; }

# Mantém stack traces úteis nos crashes de hand tracking.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute ZyvoxMr
