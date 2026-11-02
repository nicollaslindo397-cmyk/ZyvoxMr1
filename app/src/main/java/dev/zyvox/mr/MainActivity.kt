package dev.zyvox.mr

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zyvox.mr.ui.CameraPermissionGate
import dev.zyvox.mr.ui.MrScreen

/**
 * Activity única da experiência.
 *
 * `ComponentActivity` (e não `AppCompatActivity`): o SceneView resolve o
 * `ViewTreeLifecycleOwner` do `ViewNode.WindowManager` procurando uma `ComponentActivity` no
 * `ContextWrapper`. Usar `AppCompatActivity` também funcionaria (ela deriva de
 * `ComponentActivity`), mas trazer o AppCompat só adicionaria tema e toolbar que esta
 * experiência fullscreen não usa.
 *
 * `singleTask` no manifest + `configChanges` amplo: uma sessão de MR não deve ser destruída
 * por rotação. Recriar a Activity significaria reabrir a câmera, recarregar o modelo de hand
 * tracking (~200 ms) e **destruir todos os WebViews** — o usuário perderia o estado de cada
 * página aberta ao virar o aparelho.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes do setContent: define as janelas como edge-to-edge para o passthrough ocupar
        // a tela inteira, inclusive sob as barras do sistema.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Imersivo de verdade: barras escondidas e que reaparecem só com swipe da borda.
        // Numa experiência de MR, a barra de status flutuando sobre o passthrough quebra a
        // leitura de "estou vendo o mundo real".
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // A tela não pode apagar durante o uso. O SceneView faz isto para o `ARSceneView`;
        // como estamos no `SceneView` 3D comum, precisamos fazer nós mesmos.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            CameraPermissionGate {
                MrScreen()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-aplica o modo imersivo ao voltar o foco. Sem isto, um diálogo do sistema (ou o
        // próprio WebChromeClient abrindo um file picker) faz as barras reaparecerem e
        // permanecerem depois.
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}
