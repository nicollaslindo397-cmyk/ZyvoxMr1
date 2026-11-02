package dev.zyvox.mr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zyvox.mr.MrViewModel
import dev.zyvox.mr.R

/**
 * HUD 2D por cima da cena.
 *
 * ### Por que 2D e não espacial
 * Status e diagnóstico não têm lugar no mundo: precisam ser legíveis independentemente de
 * para onde o usuário aponta o aparelho. Colocá-los em `ViewNode` faria o aviso de "sem
 * câmera" desaparecer justamente quando o usuário gira o telefone — que é quando ele mais
 * precisa lê-lo.
 *
 * ### Custo de recomposição
 * Só `statusMessage`, `dockVisible` e `windows` são estado observável. A latência e o fps de
 * inferência são campos `@Volatile` no ViewModel e **não** disparam recomposição: lê-los aqui
 * mostra o valor no momento da última recomposição causada por outra coisa, o que para um
 * número de diagnóstico é perfeitamente suficiente — e evita recompor o HUD 30×/s, que
 * competiria com o render pelo frame budget.
 */
@Composable
fun MrHud(viewModel: MrViewModel) {
    val windows = viewModel.windows
    val statusMessage = viewModel.statusMessage
    val handTrackingAvailable = viewModel.handTrackingAvailable

    Box(modifier = Modifier.fillMaxSize()) {
        // ─── Topo: estado da sessão ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(MrTokens.SpaceMd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusPill(
                handTrackingAvailable = handTrackingAvailable,
                handCount = viewModel.latestHandFrame.handCount,
                windowCount = windows.size,
                latencyMs = viewModel.lastLatencyMs,
                inferenceFps = viewModel.lastInferenceFps,
            )

            if (statusMessage != null) {
                Spacer(Modifier.height(MrTokens.SpaceSm))
                WarningPill(statusMessage)
            }

            if (!handTrackingAvailable) {
                Spacer(Modifier.height(MrTokens.SpaceSm))
                WarningPill(stringResource(R.string.status_no_model))
            } else if (viewModel.latestHandFrame.handCount == 0) {
                // O modelo carregou e a inferência roda, mas nenhuma mão foi achada. Antes isto
                // não dizia nada ao usuário — o app ficava em silêncio e a leitura era "quebrou".
                //
                // E há um motivo concreto para a mão não ser achada: a câmera é a TRASEIRA. Quem
                // segura o aparelho olha a tela, e a mão que segura fica do lado da tela — ou
                // seja, atrás do aparelho, onde a câmera não vê. A mão livre precisa estar à
                // frente do celular, entre ele e o usuário, o que é o contrário do instinto.
                // `status_show_hands` já existia em strings.xml e nunca foi exibido em lugar
                // nenhum; uma string não mostrada é orientação que o usuário nunca recebe.
                Spacer(Modifier.height(MrTokens.SpaceSm))
                WarningPill(stringResource(R.string.status_show_hands))
            }
        }

        // ─── Rodapé: janelas minimizadas ────────────────────────────────────────────────
        val minimized = windows.filter { it.minimized }
        if (minimized.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = MrTokens.SpaceLg),
                horizontalArrangement = Arrangement.spacedBy(MrTokens.SpaceSm),
            ) {
                minimized.forEach { window ->
                    MinimizedChip(
                        title = window.title,
                        onClick = { viewModel.minimizeWindow(window.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    handTrackingAvailable: Boolean,
    handCount: Int,
    windowCount: Int,
    latencyMs: Float,
    inferenceFps: Float,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MrTokens.RadiusPill))
            .background(MrTokens.GlassSurface)
            .padding(horizontal = MrTokens.SpaceMd, vertical = MrTokens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MrTokens.SpaceMd),
    ) {
        // Ponto de estado: verde com mão rastreada, âmbar sem mão, vermelho sem modelo.
        // É a forma mais rápida de o usuário saber se o tracking está vivo sem ler número.
        val dotColor = when {
            !handTrackingAvailable -> MrTokens.Danger
            handCount > 0 -> MrTokens.Success
            else -> MrTokens.Warning
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )

        HudText(
            if (handTrackingAvailable) {
                stringResource(R.string.status_tracking, handCount)
            } else {
                "hand tracking inativo"
            }
        )
        HudSeparator()
        HudText("$windowCount janela(s)")
        HudSeparator()
        HudText("%.0f ms".format(latencyMs))
        HudSeparator()
        HudText("%.0f fps".format(inferenceFps))
    }
}

@Composable
private fun WarningPill(message: String) {
    Text(
        text = message,
        color = MrTokens.TextPrimary,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(MrTokens.RadiusMd))
            .background(MrTokens.GlassSurfaceRaised)
            .padding(horizontal = MrTokens.SpaceMd, vertical = MrTokens.SpaceSm),
    )
}

/**
 * Chip de janela minimizada. É a única forma de trazer uma janela de volta sem o dock —
 * importante porque o dock tem um botão "ocultar" e o usuário pode se perder.
 */
@Composable
private fun MinimizedChip(title: String, onClick: () -> Unit) {
    Text(
        text = title.ifBlank { "janela" },
        color = MrTokens.Accent,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(MrTokens.RadiusPill))
            .background(MrTokens.GlassSurface)
            // `clickable` vem do foundation; usado via Modifier para não puxar Material
            // Button, que traria altura mínima e padding que quebram o tamanho do chip.
            .clickable(onClick = onClick)
            .padding(horizontal = MrTokens.SpaceMd, vertical = MrTokens.SpaceSm),
    )
}

@Composable
private fun HudText(text: String) {
    Text(
        text = text,
        color = MrTokens.TextSecondary,
        fontSize = 12.sp,
        // Monoespaçado: os números de latência/fps mudam toda hora e, com fonte proporcional,
        // o pill inteiro "respira" de largura a cada frame. É distração gratuita.
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun HudSeparator() {
    Box(
        Modifier
            .size(width = 1.dp, height = 10.dp)
            .background(Color(0x33FFFFFF))
    )
}
