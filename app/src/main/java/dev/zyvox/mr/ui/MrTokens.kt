package dev.zyvox.mr.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tokens visuais do Zyvox MR.
 *
 * Identidade própria: paleta fria escura com um acento ciano. Não reutiliza marca, cor ou
 * tipografia de nenhum outro produto — é um projeto independente.
 *
 * Concentrar aqui (em vez de espalhar literais pelos composables) é o que permite ajustar a
 * aparência inteira sem caçar `Color(0xFF…)` em doze arquivos.
 */
object MrTokens {

    // ─── Superfícies ────────────────────────────────────────────────────────────────────
    /** Fundo base. Preto puro: é o que se vê antes do primeiro quadro da câmera. */
    val Background = Color(0xFF000000)

    /** Painel de vidro: escuro, alpha alto o bastante para texto legível sobre o passthrough. */
    val GlassSurface = Color(0xD9141821)

    /** Variante mais clara, para camadas sobrepostas ao vidro. */
    val GlassSurfaceRaised = Color(0xF21A1F26)

    /** Borda luminosa de 1 px — é o que faz o vidro ler como vidro em vez de retângulo preto. */
    val GlassBorder = Color(0x40FFFFFF)

    // ─── Acento ─────────────────────────────────────────────────────────────────────────
    val Accent = Color(0xFF7DD3FC)
    val AccentDim = Color(0x337DD3FC)
    val AccentStrong = Color(0xFF38BDF8)

    /** Feedback de erro / ação destrutiva. */
    val Danger = Color(0xFFF87171)

    /** Feedback de sucesso (tracking ativo, gesto reconhecido). */
    val Success = Color(0xFF4ADE80)

    /** Aviso (modelo ausente, permissão negada). */
    val Warning = Color(0xFFFBBF24)

    // ─── Texto ──────────────────────────────────────────────────────────────────────────
    val TextPrimary = Color(0xFFE8F1FA)
    val TextSecondary = Color(0xFF9FB0C4)
    val TextDisabled = Color(0xFF5C6B7E)

    // ─── Cursor da mão ──────────────────────────────────────────────────────────────────
    /**
     * O cursor é deliberadamente **pequeno**: 10 dp de diâmetro. A referência pede "cursor
     * pequeno e discreto", e um cursor grande esconde justamente o conteúdo que o usuário
     * está tentando apontar.
     */
    val CursorSize = 10.dp
    val CursorRingSize = 26.dp
    val CursorColor = Accent
    /** Anel de feedback que aparece só durante o pinch — é o que confirma o gesto. */
    val CursorPinchColor = Success

    // ─── Geometria ──────────────────────────────────────────────────────────────────────
    val RadiusSm = 8.dp
    val RadiusMd = 14.dp
    val RadiusLg = 22.dp
    val RadiusPill = 999.dp

    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 14.dp
    val SpaceLg = 22.dp

    // ─── Espaciais ──────────────────────────────────────────────────────────────────────
    /**
     * Profundidade do plano de interação, em metros.
     *
     * 0,45 m é a distância confortável de leitura para um painel de ~0,6 m num telefone
     * segurado com o braço estendido. Perto demais (< 0,3 m) a câmera não foca e o usuário
     * precisa afastar o aparelho; longe demais (> 0,8 m) os alvos ficam pequenos no sensor e
     * o hand tracking perde precisão.
     */
    const val INTERACTION_DEPTH_M = 0.45f

    /** Profundidade do dock: um pouco mais perto que as janelas, para ler "à frente" delas. */
    const val DOCK_DEPTH_M = 0.40f

    /** Posição vertical do dock no mundo (negativo = abaixo do centro do campo de visão). */
    const val DOCK_Y_M = -0.20f
}
