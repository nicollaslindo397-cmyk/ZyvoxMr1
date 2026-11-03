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

    /**
     * Largura de **fallback** do dock, em metros, para o intervalo entre a criação do dock e a
     * primeira medição do campo de visão.
     *
     * ### Por que isto quase não importa mais
     * A largura real vem da medição do campo de visão vezes [DOCK_WIDTH_FRACTION], recalculada
     * quando a distância configurada muda. Este número só é lido enquanto a medição ainda não
     * aconteceu (primeiro quadro), então ele precisa apenas ser razoável — não certo para todo
     * aparelho, que é impossível sem conhecer o FOV.
     *
     * ### O que foi removido daqui, e por quê
     * `DOCK_Y_M`, `DOCK_DEPTH_M`, `DOCK_SCALE_MIN` e `DOCK_SCALE_MAX` moravam aqui e agora não
     * existem mais. Posição e profundidade passaram a vir de `settings.layout` (distância, altura,
     * deslocamento horizontal e inclinação são ajustáveis pelo usuário), e os limites de escala
     * passaram a vir de `InterfaceLayout.MIN_SCALE`/`MAX_SCALE`, que são os mesmos que a
     * sanitização das configurações aplica.
     *
     * Manter as duas coisas seria ter duas fontes de verdade sobre onde o dock fica e até onde ele
     * pode crescer. Uma constante de posição sobrevivendo ao lado de uma posição configurável é o
     * tipo de coisa que alguém lê, confia, e usa — e o resultado é um teste de contenção que vale
     * para uma posição que o dock não ocupa mais.
     */
    const val DOCK_WIDTH_M = 0.34f

    /**
     * Passo dos botões de aumentar/diminuir.
     *
     * Os limites NÃO estão aqui: são `InterfaceLayout.MIN_SCALE`/`MAX_SCALE`, para que o passo e o
     * clamp descrevam o mesmo intervalo.
     */
    const val DOCK_SCALE_STEP = 0.15f

    /**
     * Fração da **largura visível** que o dock ocupa, na escala 1.0.
     *
     * O dock não tem mais uma largura fixa em metros — ele é medido contra o que a câmera
     * realmente enxerga na profundidade dele. [DOCK_WIDTH_M] virou só o valor de partida antes
     * da primeira medição.
     *
     * O motivo é que metros absolutos não significam nada sem conhecer o campo de visão: o mesmo
     * 0,34 m pode ocupar metade da tela num aparelho e transbordar noutro, e a largura visível
     * depende de FOV, proporção e orientação — nada disso é constante entre celulares. Ancorar
     * numa fração do visível é o que faz o dock ter o mesmo tamanho APARENTE em qualquer
     * aparelho, que é o que o usuário de fato percebe.
     *
     * 0.92 preenche a largura sem tocar as bordas. Não é "preencher a tela": o dock continua
     * sendo uma faixa fina, e o requisito original é que ele não ocupe a tela inteira — o que
     * decide isso é a altura, não a largura.
     */
    const val DOCK_WIDTH_FRACTION = 0.92f
}
