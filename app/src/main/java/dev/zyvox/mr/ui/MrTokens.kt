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

    /**
     * Posição vertical do dock no mundo. **Zero = centro exato do campo de visão.**
     *
     * Aqui estava `-0.20f`, e o efeito no aparelho foi concreto: para ver o dock o usuário
     * precisava apontar o celular para baixo, porque o dock ficava 20 cm abaixo do centro da
     * visão a 40 cm de distância — cerca de 27° para baixo. O pedido foi explícito ("quero que
     * fique na direção dos olhos"), e num aparelho segurado à frente do rosto o centro do campo
     * de visão É a direção dos olhos.
     *
     * Vale registrar por que "abaixo do centro" parecia certo no papel: é onde o dock do Quest 3
     * fica. Mas lá o headset sabe para onde você olha e o dock acompanha; aqui a câmera é fixa
     * no aparelho, então qualquer deslocamento vertical vira um ângulo que o braço tem de
     * sustentar. Com 3DoF, centrado não é uma preferência estética — é o que não cansa.
     */
    const val DOCK_Y_M = 0.0f

    /**
     * Largura do dock no mundo, em metros.
     *
     * Mora aqui junto com [DOCK_DEPTH_M] e [DOCK_Y_M]: os três descrevem onde o dock fica no
     * espaço, e separá-los é o que fez `DockPanelHost` e `SpatialInputRouter` referenciarem
     * `MrTokens.DOCK_WIDTH_M` quando o valor só existia em `DockPanelView`. O tamanho em PIXEL do
     * dock continua em `DockPanelView.DOCK_WIDTH_PX` — aquele é um detalhe de layout da view; este
     * é a geometria espacial que `pxPerUnits` e o teste de contenção consomem.
     *
     * 0,34 m a 0,40 m de profundidade cobre ~0,7 do campo de visão horizontal: largo o bastante
     * para os ícones serem alvejáveis pelo hand tracking, estreito o bastante para não dominar o
     * passthrough — a referência pede explicitamente um dock que NÃO preencha a tela.
     *
     * Este é o valor **base**: o tamanho real em uso é [DOCK_WIDTH_M] × `dockScale` do
     * `MrViewModel`, porque o dock é redimensionável. Nada deve ler esta constante para decidir
     * onde termina o dock — use a largura efetiva que o `DockPanelHost` expõe, senão o teste de
     * contenção e o mapeamento de toque passam a valer só para a escala 1.0.
     */
    const val DOCK_WIDTH_M = 0.34f

    /** Limites da escala do dock. Abaixo de 0,8 os alvos ficam menores que o cursor; acima de 2,2 o dock come o campo de visão. */
    const val DOCK_SCALE_MIN = 0.8f
    const val DOCK_SCALE_MAX = 2.2f
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
