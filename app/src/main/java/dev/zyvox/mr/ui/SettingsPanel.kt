package dev.zyvox.mr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zyvox.mr.MrViewModel
import dev.zyvox.mr.settings.DisplayMode
import dev.zyvox.mr.settings.InterfaceLayout
import dev.zyvox.mr.settings.InterfacePreset

/**
 * Centro de configurações (seção 12 do pedido) e o único caminho até a interface ajustável
 * (seção 2).
 *
 * ### Por que 2D e por que em cima de tudo
 * Igual ao [MrHud]: um controle precisa ser legível e alcançável independentemente de para onde o
 * usuário aponta o aparelho. Se o painel fosse um `ViewNode` no mundo, mover a interface para longe
 * moveria junto o painel que serve para trazê-la de volta — o usuário ficaria preso exatamente no
 * estado que o painel existe para corrigir.
 *
 * ### Por que os alvos são grandes
 * O pedido é explícito: nada de menu Android apertado, os elementos precisam ser confortáveis dentro
 * de um VR Box. Um slider de 48 dp de altura que é fácil no telefone vira difícil com lente a 4 cm do
 * olho, então o mínimo aqui é [TARGET_HEIGHT_DP] e o texto não desce de 14 sp.
 *
 * ### O que está aqui, e o que deliberadamente não está
 * Cada controle deste painel faz alguma coisa de verdade: escreve em `viewModel.settings`, que o
 * `MrScreen` aplica por quadro. Não há botão decorativo.
 *
 * Por isso algumas coisas do pedido ainda NÃO aparecem, e estão listadas no rodapé em vez de
 * escondidas: fixar a interface no mundo, IPD e modo VR Box estéreo, sensibilidade e calibração das
 * mãos, qualidade gráfica, brilho do passthrough e ambientes 3D. O modelo de dados já tem campos para
 * tudo isso (`InterfaceLayout.pinned`, `VrBoxSettings`, `HandSettings`, `GraphicsQuality`), mas o
 * código que os consome ainda não existe — mostrar um slider que não muda nada é exatamente o
 * "protótipo visual" que o pedido proíbe.
 */
@Composable
fun SettingsPanel(viewModel: MrViewModel, onClose: () -> Unit) {
    val settings = viewModel.settings
    val layout = settings.layout

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Scrim: escurece a cena para o painel ser legível sobre o passthrough, que é uma
            // imagem clara e cheia de detalhe.
            .background(Color(0xCC000000))
            // `indication = null`: sem ripple. Um Box sem `clickable` não consome toque nenhum, e o
            // toque atravessaria até o `SceneView` — arrastar um slider sobre o dock arrastaria o
            // dock junto. O clique vazio existe só para interceptar.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(MrTokens.SpaceLg)
                .clip(RoundedCornerShape(MrTokens.RadiusLg))
                .background(MrTokens.GlassSurfaceRaised)
                .verticalScroll(rememberScrollState())
                .padding(MrTokens.SpaceLg),
        ) {
            PanelHeader(onClose = onClose)

            SectionTitle("Interface no espaço")
            SectionHint(
                "Muda na hora, sem reiniciar. Os ajustes são salvos e voltam iguais na próxima vez."
            )

            PresetRow(
                selected = settings.preset,
                onSelect = viewModel::applyPreset,
            )

            SettingSlider(
                label = "Distância",
                valueText = "%.2f m".format(layout.distanceM),
                value = layout.distanceM,
                valueRange = InterfaceLayout.MIN_DISTANCE_M..InterfaceLayout.MAX_DISTANCE_M,
                onValueChange = viewModel::setDistance,
            )
            SettingSlider(
                label = "Altura",
                valueText = "%+.2f m".format(layout.heightM),
                value = layout.heightM,
                valueRange = InterfaceLayout.MIN_HEIGHT_M..InterfaceLayout.MAX_HEIGHT_M,
                onValueChange = viewModel::setHeight,
            )
            SettingSlider(
                label = "Posição horizontal",
                valueText = "%+.2f m".format(layout.offsetXM),
                value = layout.offsetXM,
                valueRange = InterfaceLayout.MIN_OFFSET_X_M..InterfaceLayout.MAX_OFFSET_X_M,
                onValueChange = viewModel::setHorizontalOffset,
            )
            SettingSlider(
                label = "Inclinação",
                valueText = "%+.0f°".format(layout.tiltDeg),
                value = layout.tiltDeg,
                valueRange = InterfaceLayout.MIN_TILT_DEG..InterfaceLayout.MAX_TILT_DEG,
                onValueChange = viewModel::setTilt,
            )
            SettingSlider(
                label = "Tamanho",
                valueText = "%.2f×".format(layout.scale),
                value = layout.scale,
                valueRange = InterfaceLayout.MIN_SCALE..InterfaceLayout.MAX_SCALE,
                onValueChange = viewModel::setScale,
            )

            ButtonRow {
                PanelButton("Recentrar") { viewModel.recenterInterface() }
                PanelButton("Restaurar padrões") { viewModel.resetAllSettings() }
            }

            Spacer(Modifier.height(MrTokens.SpaceLg))

            SectionTitle("Fundo")
            SectionHint(
                "Câmera usa a traseira como passthrough. Fundo virtual esconde a câmera, mas ela " +
                    "continua ligada — é dela que sai a imagem que o rastreamento de mão analisa."
            )
            ChoiceRow(
                labels = listOf("Câmera (MR)", "Fundo virtual"),
                selectedIndex = if (settings.displayMode == DisplayMode.MR_PASSTHROUGH) 0 else 1,
                onSelect = { index ->
                    val mode = if (index == 0) DisplayMode.MR_PASSTHROUGH else DisplayMode.VR_FLAT
                    viewModel.updateSettings { it.copy(displayMode = mode) }
                },
            )

            Spacer(Modifier.height(MrTokens.SpaceLg))

            SectionTitle("Dock e janelas")
            ButtonRow {
                PanelButton(if (viewModel.dockVisible) "Ocultar dock" else "Mostrar dock") {
                    viewModel.toggleDock()
                }
                PanelButton("Recentrar janelas") { viewModel.recenterWindows() }
                PanelButton("Fechar todas") { viewModel.closeAllWindows() }
            }

            Spacer(Modifier.height(MrTokens.SpaceLg))

            SectionTitle("Ainda não disponível nesta versão")
            SectionHint(NOT_YET_WIRED)
        }
    }
}

/**
 * Texto do rodapé honesto.
 *
 * Separado em constante porque é a parte que mais vai encolher: cada item sai daqui quando o código
 * que o consome é escrito. Manter a lista num só lugar é o que impede ela de ficar mentindo.
 */
private const val NOT_YET_WIRED =
    "Fixar a interface no mundo (pin), modo VR Box com estéreo e IPD, sensibilidade e calibração do " +
        "rastreamento de mão, qualidade gráfica, brilho do passthrough e ambientes 3D selecionáveis. " +
        "Os ajustes existem no modelo de dados, mas o código que os aplica ainda não está escrito — " +
        "por isso não aparecem como controles aqui."

/** Altura mínima de qualquer alvo tocável deste painel. */
private val TARGET_HEIGHT_DP = 52.dp

@Composable
private fun PanelHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Configurações",
            color = MrTokens.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .heightIn(min = TARGET_HEIGHT_DP)
                .clip(RoundedCornerShape(MrTokens.RadiusPill))
                .background(MrTokens.GlassSurface)
                .clickable(onClick = onClose)
                .padding(horizontal = MrTokens.SpaceLg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Fechar",
                color = MrTokens.Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    Spacer(Modifier.height(MrTokens.SpaceLg))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MrTokens.TextPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(MrTokens.SpaceXs))
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        color = MrTokens.TextSecondary,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(MrTokens.SpaceMd))
}

/**
 * Os três presets de distância.
 *
 * Só três botões, não quatro: `CUSTOM` não é um lugar para onde ir, é o estado em que o painel já
 * está assim que o usuário move qualquer slider. Mostrar "Personalizado" como botão selecionável
 * sugeriria que ele faz algo, e o que ele faria — voltar aos números padrão — já é o botão
 * "Restaurar padrões".
 *
 * O preset ativo aparece com borda e cor de destaque, então o usuário vê qual deles vale agora.
 */
@Composable
private fun PresetRow(selected: InterfacePreset, onSelect: (InterfacePreset) -> Unit) {
    val presets = listOf(
        InterfacePreset.NEAR to "Perto",
        InterfacePreset.MEDIUM to "Médio",
        InterfacePreset.FAR to "Longe",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MrTokens.SpaceSm),
    ) {
        presets.forEach { (preset, label) ->
            val isSelected = selected == preset
            // O número vem do próprio enum, que é quem `withPreset` usa. Escrevê-lo aqui também
            // seria uma segunda fonte de verdade sobre a mesma distância.
            val detail = preset.distanceM?.let { "%.2f m".format(it) } ?: ""
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TARGET_HEIGHT_DP)
                    .clip(RoundedCornerShape(MrTokens.RadiusMd))
                    .background(if (isSelected) MrTokens.AccentDim else MrTokens.GlassSurface)
                    .clickable { onSelect(preset) }
                    .padding(vertical = MrTokens.SpaceSm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) MrTokens.Accent else MrTokens.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    color = MrTokens.TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    Spacer(Modifier.height(MrTokens.SpaceMd))
}

/**
 * Linha de slider: nome, valor atual e a faixa.
 *
 * O valor aparece em texto monoespaçado ao lado do nome. Sem isso o usuário não tem como saber onde
 * parou — 0,78 m e 0,82 m são indistinguíveis no slider, e é essa precisão que faz diferença ao
 * ajustar de dentro de um VR Box.
 *
 * `onValueChange` chama o setter do ViewModel a cada movimento, e o setter sanitiza e persiste.
 * Persistir em cada passo parece desperdício (é um `apply()` de SharedPreferences por pixel de
 * arraste), mas `apply()` grava em disco de forma assíncrona e coalesce, e o custo de NÃO fazer isso
 * é maior: o usuário ajusta, o app morre, e o ajuste se perde — que é o relato "não salva".
 */
@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, color = MrTokens.TextPrimary, fontSize = 15.sp)
            Text(
                text = valueText,
                color = MrTokens.Accent,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .height(TARGET_HEIGHT_DP),
            colors = SliderDefaults.colors(
                thumbColor = MrTokens.Accent,
                activeTrackColor = MrTokens.AccentStrong,
                inactiveTrackColor = MrTokens.GlassBorder,
            ),
        )
    }
}

/** Duas ou mais opções mutuamente exclusivas, como botões de rádio grandes. */
@Composable
private fun ChoiceRow(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MrTokens.SpaceSm),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TARGET_HEIGHT_DP)
                    .clip(RoundedCornerShape(MrTokens.RadiusMd))
                    .background(if (isSelected) MrTokens.AccentDim else MrTokens.GlassSurface)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) MrTokens.Accent else MrTokens.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Linha de botões que dividem o espaço por igual.
 *
 * O `content` tem receiver `RowScope`, e não é detalhe: é o que permite `PanelButton` chamar
 * `weight` dentro deste lambda. Com `@Composable () -> Unit` o código compilaria no `Row` mas não no
 * botão, e o erro ("unresolved reference: weight") apontaria para o lugar errado.
 */
@Composable
private fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MrTokens.SpaceSm),
    ) {
        content()
    }
}

/** Botão de ação do painel. Sempre chamado dentro de [ButtonRow], de onde vem o `RowScope`. */
@Composable
private fun RowScope.PanelButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = TARGET_HEIGHT_DP)
            .clip(RoundedCornerShape(MrTokens.RadiusMd))
            .background(MrTokens.GlassSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = MrTokens.SpaceSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MrTokens.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
        )
    }
}
