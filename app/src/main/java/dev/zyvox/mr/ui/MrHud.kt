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
                // E há um motivo concreto para a mão não ser achada: a câmera é a TRASEIRA, e
                // ela aponta para LONGE de quem segura o aparelho. A mão que segura fica do lado
                // da tela, atrás da câmera, onde ela nunca vê. A mão livre precisa estar com o
                // braço estendido, além do aparelho — o contrário do instinto de trazer a mão
                // para perto do rosto para olhá-la.
                // `status_show_hands` já existia em strings.xml e nunca foi exibido em lugar
                // nenhum; uma string não mostrada é orientação que o usuário nunca recebe.
                Spacer(Modifier.height(MrTokens.SpaceSm))
                WarningPill(stringResource(R.string.status_show_hands))
            }
        }

        // ─── Controles de fallback ──────────────────────────────────────────────────────
        //
        // Existem porque os mesmos recursos são acessíveis por gesto de mão, e o gesto depende
        // de o rastreamento funcionar. Se ele falhar — modelo não carregou, mão fora do alcance
        // da câmera traseira, aparelho sem fôlego para inferência — o usuário ficaria sem
        // nenhuma forma de trocar o fundo ou redimensionar o dock, e sem como saber que esses
        // gestos existem. Um recurso que só funciona quando outra coisa funciona não é
        // testável, e o relato de aparelho mostrou que isso custa um ciclo inteiro de feedback.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(MrTokens.SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MrTokens.SpaceSm),
        ) {
            // É o primeiro da fila de propósito: o painel que abre aqui é o único caminho até a
            // distância, altura, inclinação e tamanho da interface. Sem este botão, toda a seção de
            // interface ajustável existiria no código e não seria alcançável pelo usuário.
            HudButton(stringResource(R.string.hud_settings)) { viewModel.toggleSettingsPanel() }
            HudButton(stringResource(R.string.hud_background)) { viewModel.toggleBackground() }
            HudButton(stringResource(R.string.hud_dock_smaller)) {
                viewModel.adjustDockScale(-MrTokens.DOCK_SCALE_STEP)
            }
            HudButton(stringResource(R.string.hud_dock_bigger)) {
                viewModel.adjustDockScale(+MrTokens.DOCK_SCALE_STEP)
            }
        }

        // ─── Dica de gestos ─────────────────────────────────────────────────────────────
        // Documenta os dois gestos globais da mão direita. São invisíveis por natureza — não há
        // nada na tela que indique que eles existem — então sem esta linha o usuário nunca os
        // descobriria.
        Text(
            text = stringResource(R.string.hud_gesture_hint),
            color = MrTokens.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(MrTokens.SpaceMd)
                .clip(RoundedCornerShape(MrTokens.RadiusMd))
                .background(MrTokens.GlassSurface)
                .padding(horizontal = MrTokens.SpaceMd, vertical = MrTokens.SpaceSm),
        )

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

/**
 * Botão pequeno do HUD, usado pelos controles de fallback.
 *
 * `clickable` via `Modifier` e não Material `Button`: o Button impõe altura mínima e padding
 * próprios, que fariam estes controles competirem por espaço com os chips de janela minimizada
 * no rodapé. O padrão aqui é o mesmo do [MinimizedChip].
 */
@Composable
private fun HudButton(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MrTokens.SpaceXs),
        modifier = Modifier
            .clip(RoundedCornerShape(MrTokens.RadiusPill))
            .background(MrTokens.GlassSurfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = MrTokens.SpaceMd, vertical = MrTokens.SpaceSm),
    ) {
        HudDot()
        Text(
            text = label,
            color = MrTokens.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Ponto indicador. Marca visual de que o controle é de estado, não de ação única. */
@Composable
private fun HudDot(color: Color = MrTokens.Accent) {
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.9f)),
    )
}

@Composable
private fun WarningPill(message: String) {    Text(
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
