package dev.zyvox.mr.settings

/**
 * Estado completo e persistível do ZyvoxMr.
 *
 * ### Por que um único objeto imutável
 * Cada ajuste do usuário produz um `ZyvoxSettings` novo via `copy()`. Isso tem três efeitos que
 * importam na prática:
 *
 * 1. **Tempo real sem reiniciar.** O requisito é explícito ("os ajustes devem funcionar em tempo
 *    real"). Como o objeto é observável pelo Compose, mudar um slider recomposição à cena
 *    imediatamente — não há caminho de configuração que só se aplique na próxima partida.
 * 2. **Salvar é atômico.** Persistir um objeto imutável não tem estado intermediário; persistir
 *    oito campos soltos pode gravar metade deles se o processo morrer no meio.
 * 3. **"Restaurar configurações" é `ZyvoxSettings()`.** Não existe código de reset para escrever
 *    errado, porque o reset é o construtor.
 *
 * ### Kotlin puro, sem Android
 * Nenhum import de plataforma neste arquivo. Isso é o que permite testar em JVM os limites, os
 * presets e a sanitização — a lógica onde um erro produz uma interface inutilizável e sem volta
 * aparente.
 */

/** Como o aparelho acompanha o movimento da cabeça. */
enum class TrackingMode {
    /** Só giroscópio/sensor de rotação. Funciona em qualquer Android, mas a cena deriva ao andar. */
    ROTATION_3DOF,

    /** ARCore: posição + orientação. Exige aparelho compatível e Google Play Services for AR. */
    SPATIAL_6DOF,

    /** Usa 6DoF quando disponível e cai para 3DoF quando não. É o padrão. */
    AUTO,
}

/** O que aparece atrás dos painéis. */
enum class DisplayMode {
    /** Câmera traseira em tempo real como fundo, com o mundo virtual sobreposto. */
    MR_PASSTHROUGH,

    /**
     * Tela dividida em dois olhos para óculos VR Box, com fundo de ambiente virtual.
     *
     * A câmera traseira é monocular, então neste modo o passthrough NÃO é usado como fundo: ver
     * [VrBoxSettings] para a explicação completa de por que duplicar a imagem de uma câmera
     * monocular não produz estéreo real.
     */
    VR_BOX,

    /** Ambiente virtual em tela cheia, sem divisão — para usar sem óculos. */
    VR_FLAT,
}

/** Ambientes virtuais selecionáveis. Construídos proceduralmente, ver `env/`. */
enum class EnvironmentId {
    MODERN_ROOM,
    FUTURISTIC,
    GAMER_ROOM,
    MINIMAL,
    DARK_TECH,
}

/** Qualidade gráfica. A efetiva pode ser menor que a pedida: ver `GraphicsQuality.resolve`. */
enum class GraphicsQuality {
    LOW,
    MEDIUM,
    HIGH,

    /**
     * Escolhe a partir do que o aparelho informa. "Automática" não pode significar "alta sempre":
     * o requisito é que a qualidade se adapte às capacidades reais do dispositivo, e um aparelho
     * fraco travando a 60 fps alvo é pior que o mesmo aparelho fluído com menos detalhe.
     */
    AUTO,
}

/** Distâncias prontas da interface. O requisito pede exatamente estas quatro. */
enum class InterfacePreset {
    NEAR,
    MEDIUM,
    FAR,

    /**
     * "Personalizada": mantém os valores que o usuário já ajustou em vez de sobrescrevê-los.
     *
     * Este caso existe porque um preset que sobrescreve o ajuste manual tornaria o ajuste manual
     * inútil — o usuário moveria a interface e qualquer preset seguinte jogaria o trabalho fora.
     */
    CUSTOM,
}

/**
 * Posição, tamanho e orientação da interface no espaço.
 *
 * ### "Altura" e "posição vertical" são o mesmo eixo
 * O requisito lista os dois como itens separados, mas num espaço 3D com uma câmera olhando para
 * -Z existe **um** eixo vertical. Criar dois controles que escrevem no mesmo valor produziria um
 * slider que parece não fazer nada quando o outro foi movido antes — o tipo de defeito que o
 * usuário reporta como "não funciona". Então há um campo só, [heightM], exposto na UI como
 * "Altura / posição vertical".
 *
 * O que é genuinamente separado está separado de fato: [distanceM] (profundidade), [offsetXM]
 * (horizontal), [tiltDeg] (inclinação) e [scale] (tamanho).
 */
data class InterfaceLayout(
    /** Distância da câmera até o plano da interface, em metros. */
    val distanceM: Float = DEFAULT_DISTANCE_M,
    /** Deslocamento vertical, em metros. Positivo = para cima. */
    val heightM: Float = 0f,
    /** Deslocamento horizontal, em metros. Positivo = para a direita de quem olha. */
    val offsetXM: Float = 0f,
    /** Inclinação do plano em graus. Negativo inclina o topo para longe do usuário. */
    val tiltDeg: Float = 0f,
    /** Tamanho da interface. 1.0 = tamanho de referência. */
    val scale: Float = 1.0f,
    /**
     * Quando verdadeiro, a interface fica ancorada no mundo e não segue a rotação da cabeça.
     *
     * É o que permite "fixar a interface em determinada posição": sem isto, virar a cabeça
     * arrastaria a interface junto e não haveria posição fixa possível.
     */
    val pinned: Boolean = false,
) {
    companion object {
        const val MIN_DISTANCE_M = 0.25f
        const val MAX_DISTANCE_M = 3.0f
        const val DEFAULT_DISTANCE_M = 0.80f

        const val MIN_HEIGHT_M = -0.80f
        const val MAX_HEIGHT_M = 0.80f

        const val MIN_OFFSET_X_M = -1.20f
        const val MAX_OFFSET_X_M = 1.20f

        const val MIN_TILT_DEG = -45f
        const val MAX_TILT_DEG = 45f

        const val MIN_SCALE = 0.60f
        const val MAX_SCALE = 2.50f
    }

    /**
     * Devolve uma cópia com todos os valores dentro dos limites seguros.
     *
     * Não é defensismo decorativo. Estes números chegam de SharedPreferences, que o usuário pode
     * ter de uma versão antiga do app, e chegam de sliders que um bug de UI poderia levar além do
     * intervalo. Uma distância de 0,02 m ou uma escala de 40 produzem uma interface impossível de
     * ver e impossível de recuperar pelos próprios controles — o app pareceria quebrado sem
     * lançar exceção nenhuma. Sanitizar na leitura é o que garante que sempre existe um estado
     * utilizável.
     *
     * `isFinite()` é verificado antes de `coerceIn` de propósito: `NaN.coerceIn(a, b)` devolve
     * `NaN`, então um NaN sobreviveria a um clamp simples.
     */
    fun sanitized(): InterfaceLayout {
        fun clamp(value: Float, min: Float, max: Float, fallback: Float): Float =
            if (!value.isFinite()) fallback else value.coerceIn(min, max)

        return InterfaceLayout(
            distanceM = clamp(distanceM, MIN_DISTANCE_M, MAX_DISTANCE_M, DEFAULT_DISTANCE_M),
            heightM = clamp(heightM, MIN_HEIGHT_M, MAX_HEIGHT_M, 0f),
            offsetXM = clamp(offsetXM, MIN_OFFSET_X_M, MAX_OFFSET_X_M, 0f),
            tiltDeg = clamp(tiltDeg, MIN_TILT_DEG, MAX_TILT_DEG, 0f),
            scale = clamp(scale, MIN_SCALE, MAX_SCALE, 1.0f),
            pinned = pinned,
        )
    }

    /**
     * Zera posição e inclinação, mantendo distância e tamanho.
     *
     * "Recentralizar" não pode significar "restaurar tudo": o usuário que ajustou a distância para
     * o próprio conforto perderia esse ajuste ao recentralizar, e passaria a evitar o botão.
     */
    fun recentered(): InterfaceLayout = copy(heightM = 0f, offsetXM = 0f, tiltDeg = 0f)
}

/**
 * Parâmetros ópticos e geométricos do modo VR Box.
 *
 * ### A limitação que este modo não esconde
 * A câmera traseira é **monocular**: ela produz uma imagem de um ponto de vista só. Estéreo real
 * exige duas imagens capturadas de pontos separados pelo IPD. Portanto, neste modo, duplicar a
 * imagem da câmera para os dois olhos NÃO cria profundidade real do ambiente físico — cria duas
 * cópias idênticas, que o cérebro funda numa imagem plana e podem causar desconforto.
 *
 * A consequência de projeto, que é o motivo de [DisplayMode.VR_BOX] não usar passthrough: em VR
 * Box o fundo é o ambiente **virtual**, e aí o estéreo é genuíno — os dois olhos recebem
 * projeções de duas câmeras virtuais separadas pelo IPD, exatamente como num headset. Onde há
 * dados para estéreo (cena virtual), estéreo real; onde não há (câmera monocular), não se finge.
 *
 * O que continua valendo para passthrough em VR Box é a geometria da lente: distância
 * tela-lente, centro óptico e correção de distorção, que dependem da carcaça do óculos e não da
 * câmera.
 */
data class VrBoxSettings(
    /** Distância interpupilar em milímetros. Faixa cobre a variação adulta real. */
    val ipdMm: Float = DEFAULT_IPD_MM,
    /**
     * Deslocamento horizontal do centro de cada lente em relação ao centro da metade da tela,
     * em milímetros. Óculos baratos raramente têm as lentes centradas na metade exata.
     */
    val lensCenterOffsetMm: Float = 0f,
    /** Distância da tela até a lente, em milímetros. Define o campo de visão aparente. */
    val screenToLensMm: Float = DEFAULT_SCREEN_TO_LENS_MM,
    /**
     * Intensidade da correção de distorção em barril, 0 (desligada) a 1 (máxima).
     *
     * Lentes convexas de VR Box introduzem distorção em almofada; pré-distorcer a imagem em
     * barril compensa. Deixar isso sempre ligado seria errado: óculos com lentes melhores não
     * precisam, e a correção desnecessária reduz a área útil e a nitidez.
     */
    val distortionK: Float = 0.22f,
    /** Escala da imagem dentro de cada olho. Útil quando a carcaça corta as bordas. */
    val imageScale: Float = 1.0f,
    /** Esconde a barra de status e a de navegação do Android durante a experiência. */
    val fullscreen: Boolean = true,

    /** Inverte esquerda/direita. Algumas carcaças montam o celular de cabeça para baixo. */
    val swapEyes: Boolean = false,
) {
    companion object {
        const val MIN_IPD_MM = 52f
        const val MAX_IPD_MM = 78f
        const val DEFAULT_IPD_MM = 64f

        const val MIN_SCREEN_TO_LENS_MM = 25f
        const val MAX_SCREEN_TO_LENS_MM = 70f
        const val DEFAULT_SCREEN_TO_LENS_MM = 42f

        const val MIN_LENS_CENTER_OFFSET_MM = -20f
        const val MAX_LENS_CENTER_OFFSET_MM = 20f

        const val MIN_DISTORTION_K = 0f
        const val MAX_DISTORTION_K = 1f

        const val MIN_IMAGE_SCALE = 0.70f
        const val MAX_IMAGE_SCALE = 1.30f
    }

    fun sanitized(): VrBoxSettings {
        fun clamp(value: Float, min: Float, max: Float, fallback: Float): Float =
            if (!value.isFinite()) fallback else value.coerceIn(min, max)

        return VrBoxSettings(
            ipdMm = clamp(ipdMm, MIN_IPD_MM, MAX_IPD_MM, DEFAULT_IPD_MM),
            lensCenterOffsetMm = clamp(
                lensCenterOffsetMm,
                MIN_LENS_CENTER_OFFSET_MM,
                MAX_LENS_CENTER_OFFSET_MM,
                0f,
            ),
            screenToLensMm = clamp(screenToLensMm, MIN_SCREEN_TO_LENS_MM, MAX_SCREEN_TO_LENS_MM, DEFAULT_SCREEN_TO_LENS_MM),
            distortionK = clamp(distortionK, MIN_DISTORTION_K, MAX_DISTORTION_K, 0f),
            imageScale = clamp(imageScale, MIN_IMAGE_SCALE, MAX_IMAGE_SCALE, 1f),
            fullscreen = fullscreen,
            swapEyes = swapEyes,
        )
    }

    /**
     * Separação dos centros das duas câmeras virtuais, em metros de mundo.
     *
     * Metade do IPD para cada lado do centro. É este número que torna o estéreo real: as duas
     * câmeras precisam estar separadas pela distância dos olhos do usuário, nem mais nem menos.
     */
    val eyeSeparationM: Float get() = ipdMm / 1000f
}

/** Rastreamento de mãos: sensibilidade, calibração e limiares de gesto. */
data class HandSettings(
    val enabled: Boolean = true,

    /**
     * Sensibilidade da detecção, 0 (conservadora) a 1 (agressiva).
     *
     * Mapeia para `minHandDetectionConfidence`. Não é exposto como o valor cru do MediaPipe
     * porque "0.35" não significa nada para o usuário, e porque o sentido do eixo é invertido:
     * mais sensibilidade = limiar de confiança MENOR. Deixar o usuário mexer no limiar direto
     * produziria o efeito oposto ao esperado.
     */
    val sensitivity: Float = 0.55f,

    /**
     * Suavização, 0 (resposta imediata, mais tremor) a 1 (muito estável, mais atraso).
     *
     * Existe porque estabilidade e latência são trade-off e o ponto certo depende do aparelho e
     * da mão do usuário. O padrão fica ligeiramente abaixo do meio: com muita suavização a mão
     * "atrasa" e a interação parece quebrada, que foi um defeito relatado em aparelho.
     */
    val smoothing: Float = 0.40f,

    /**
     * Deslocamento de calibração do cursor, em fração da tela.
     *
     * Corrige o caso em que o cursor aparece sistematicamente ao lado do dedo — causado por
     * diferença entre o recorte da imagem de análise e a área exibida. Sem isto, o único recurso
     * do usuário seria aprender a mirar torto.
     */
    val calibrationX: Float = 0f,
    val calibrationY: Float = 0f,

    /** Mostra o esqueleto brilhante da mão. Também serve de diagnóstico: se some, é detecção. */
    val showSkeleton: Boolean = true,
) {
    companion object {
        const val MIN_SENSITIVITY = 0f
        const val MAX_SENSITIVITY = 1f

        const val MIN_CALIBRATION = -0.15f
        const val MAX_CALIBRATION = 0.15f

        /** Limiar de confiança de detecção no extremo conservador (sensibilidade 0). */
        const val DETECTION_CONFIDENCE_HIGH = 0.60f

        /** Limiar no extremo agressivo (sensibilidade 1). */
        const val DETECTION_CONFIDENCE_LOW = 0.20f
    }

    fun sanitized(): HandSettings {
        fun clamp(value: Float, min: Float, max: Float, fallback: Float): Float =
            if (!value.isFinite()) fallback else value.coerceIn(min, max)

        return HandSettings(
            enabled = enabled,
            sensitivity = clamp(sensitivity, MIN_SENSITIVITY, MAX_SENSITIVITY, 0.55f),
            smoothing = clamp(smoothing, 0f, 1f, 0.40f),
            calibrationX = clamp(calibrationX, MIN_CALIBRATION, MAX_CALIBRATION, 0f),
            calibrationY = clamp(calibrationY, MIN_CALIBRATION, MAX_CALIBRATION, 0f),
            showSkeleton = showSkeleton,
        )
    }

    /**
     * Limiar de confiança de detecção derivado da sensibilidade.
     *
     * Eixo invertido de propósito, ver [sensitivity]. Valores fora de 0.20–0.60 não são úteis:
     * abaixo disso o MediaPipe aceita ruído como mão e o cursor aparece sem mão nenhuma; acima,
     * descarta detecções legítimas a distância de braço estendido — que é a distância real de uso
     * com câmera traseira, bem maior que a distância de selfie para a qual o padrão 0.5 foi
     * calibrado.
     */
    val detectionConfidence: Float
        get() {
            val s = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
            return DETECTION_CONFIDENCE_HIGH - s * (DETECTION_CONFIDENCE_HIGH - DETECTION_CONFIDENCE_LOW)
        }
}

/** Passthrough: liga/desliga, brilho e exposição. */
data class PassthroughSettings(
    val enabled: Boolean = true,

    /**
     * Ganho de brilho aplicado à imagem da câmera, 0.3 a 1.5.
     *
     * É pós-processamento, não controle de sensor: funciona em qualquer aparelho. Dentro de um VR
     * Box a imagem passa por lentes que absorvem luz, então o brilho precisa de compensação para
     * cima — e não há como saber quanto sem deixar o usuário ajustar.
     */
    val brightness: Float = 1.0f,

    /**
     * Compensação de exposição do sensor, quando o aparelho oferece.
     *
     * `null` = não mexer. Manter `null` como valor válido importa porque a maioria dos aparelhos
     * expõe uma faixa diferente, e gravar um índice fixo faria o app pedir uma exposição
     * inexistente em outro aparelho. A faixa real é consultada à câmera em tempo de execução.
     */
    val exposureCompensation: Int? = null,
) {
    companion object {
        const val MIN_BRIGHTNESS = 0.3f
        const val MAX_BRIGHTNESS = 1.5f
    }

    fun sanitized(): PassthroughSettings = PassthroughSettings(
        enabled = enabled,
        brightness = if (!brightness.isFinite()) 1.0f else brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        exposureCompensation = exposureCompensation,
    )
}

/** Raiz de toda a configuração. */
data class ZyvoxSettings(
    val layout: InterfaceLayout = InterfaceLayout(),
    val vrBox: VrBoxSettings = VrBoxSettings(),
    val hand: HandSettings = HandSettings(),
    val passthrough: PassthroughSettings = PassthroughSettings(),
    val trackingMode: TrackingMode = TrackingMode.AUTO,
    val displayMode: DisplayMode = DisplayMode.MR_PASSTHROUGH,
    val environment: EnvironmentId = EnvironmentId.MODERN_ROOM,
    val graphicsQuality: GraphicsQuality = GraphicsQuality.AUTO,

    /**
     * Qual preset está ativo. É estado derivado de [layout] na prática, mas guardado porque a UI
     * precisa mostrar qual dos quatro botões está selecionado, e deduzir isso dos números seria
     * ambíguo assim que o usuário movesse um slider (viraria CUSTOM, o que é correto — mas a
     * transição precisa ser explícita, não inferida a cada quadro).
     */
    val preset: InterfacePreset = InterfacePreset.MEDIUM,

    /**
     * Versão do esquema persistido.
     *
     * Serve para migração: quando um campo novo entrar, uma preferência gravada por versão
     * antiga não terá a chave, e o código precisa saber distinguir "campo ausente porque é
     * antigo" de "campo ausente porque foi corrompido". Sem versão, a única opção é assumir
     * defaults em silêncio e perder a configuração do usuário.
     */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }

    /** Devolve uma cópia com todos os sub-objetos sanitizados. */
    fun sanitized(): ZyvoxSettings = copy(
        layout = layout.sanitized(),
        vrBox = vrBox.sanitized(),
        hand = hand.sanitized(),
        passthrough = passthrough.sanitized(),
    )

    /**
     * Aplica um preset de distância, preservando a escala que o usuário escolheu.
     *
     * ### Por que os presets NÃO mexem na escala
     * A primeira versão deste método ajustava distância e escala juntas, partindo da premissa de
     * que interface mais distante fica menor. Essa premissa era verdadeira para uma largura fixa
     * em metros, e deixou de ser quando a largura do dock passou a ser uma fração do campo de
     * visão medido na própria profundidade (ver `MrTokens.DOCK_WIDTH_FRACTION` e
     * `DockPanelHost.applyVisibleWidth`).
     *
     * Com a largura ancorada no campo de visão, o tamanho **angular** é invariante à distância:
     * a 0,45 m, a 0,80 m ou a 1,60 m a interface subtende os mesmos ~46–66°, dependendo só do FOV
     * do aparelho. Foi verificado numericamente, e é exatamente a propriedade que se quer de um
     * "aproximar/afastar".
     *
     * Compensar com escala sobre essa invariância contaria o efeito duas vezes. Pior: a escala
     * multiplica a fração do campo de visão, então `scale = 1.45` produziria 0,92 × 1,45 = 1,33×
     * a largura da tela — a interface transbordaria nos dois lados, que é o defeito oposto ao que
     * o usuário relata quando diz que está pequena.
     *
     * Preservar a escala tem ainda um efeito bom de UX: quem aumentou a interface não perde esse
     * ajuste ao trocar de preset de distância.
     *
     * [InterfacePreset.CUSTOM] devolve a instância inalterada.
     */
    fun withPreset(target: InterfacePreset): ZyvoxSettings = when (target) {
        InterfacePreset.NEAR -> withDistance(0.45f, target)
        InterfacePreset.MEDIUM -> withDistance(InterfaceLayout.DEFAULT_DISTANCE_M, target)
        InterfacePreset.FAR -> withDistance(1.60f, target)
        InterfacePreset.CUSTOM -> copy(preset = target)
    }

    private fun withDistance(distanceM: Float, target: InterfacePreset): ZyvoxSettings = copy(
        layout = layout.copy(distanceM = distanceM).sanitized(),
        preset = target,
    )

    /**
     * Marca o layout como personalizado.
     *
     * Chamado quando o usuário move qualquer controle manual: a partir daí o preset ativo não
     * descreve mais a realidade, e continuar mostrando "Média" selecionado seria mentira na UI.
     */
    fun withLayout(newLayout: InterfaceLayout): ZyvoxSettings = copy(
        layout = newLayout.sanitized(),
        preset = InterfacePreset.CUSTOM,
    )

    /** Restaura tudo para o padrão de fábrica. É literalmente o construtor. */
    fun resetAll(): ZyvoxSettings = ZyvoxSettings()
}
