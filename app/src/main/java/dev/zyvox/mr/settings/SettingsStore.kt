package dev.zyvox.mr.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistência de [ZyvoxSettings] em `SharedPreferences`.
 *
 * ### Por que chaves planas e não um JSON
 * Serializar exigiria uma dependência nova (kotlinx.serialization ou Moshi) para gravar ~30
 * valores. Chaves planas têm uma vantagem que pesa mais aqui: quando uma versão futura adicionar
 * um campo, a preferência antiga simplesmente não tem aquela chave e `getFloat(chave, default)`
 * devolve o default — a migração é implícita e não pode falhar. Com um blob JSON, um campo novo
 * contra um blob velho depende de o parser ser tolerante, e um blob corrompido perde **toda** a
 * configuração em vez de um campo.
 *
 * ### Onde isto roda
 * Leitura e gravação são síncronas e baratas (dezenas de valores; o Android mantém o mapa em
 * memória depois do primeiro load). A gravação usa `apply()`, que é assíncrono em disco — nada
 * aqui está no caminho de render.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Carrega e sanitiza.
     *
     * A sanitização acontece **aqui**, uma vez por carregamento, e não a cada leitura. É o ponto
     * em que dados de fora do app entram: versão antiga, arquivo editado, ou um valor que era
     * válido e deixou de ser. Depois daqui o resto do código pode confiar nos limites — inclusive
     * `InterfaceTransform`, que não sanitiza por quadro justamente por causa disto.
     */
    fun load(): ZyvoxSettings {
        val version = prefs.getInt(KEY_SCHEMA_VERSION, ZyvoxSettings.CURRENT_SCHEMA_VERSION)
        // Versão desconhecida (mais nova que a que este build entende, ou corrompida): parte dos
        // defaults em vez de tentar interpretar chaves cujo significado este código não conhece.
        if (version > ZyvoxSettings.CURRENT_SCHEMA_VERSION) return ZyvoxSettings()

        val settings = ZyvoxSettings(
            layout = InterfaceLayout(
                distanceM = prefs.getFloat(KEY_DISTANCE_M, InterfaceLayout.DEFAULT_DISTANCE_M),
                heightM = prefs.getFloat(KEY_HEIGHT_M, 0f),
                offsetXM = prefs.getFloat(KEY_OFFSET_X_M, 0f),
                tiltDeg = prefs.getFloat(KEY_TILT_DEG, 0f),
                scale = prefs.getFloat(KEY_SCALE, 1f),
                pinned = prefs.getBoolean(KEY_PINNED, false),
            ),
            vrBox = VrBoxSettings(
                ipdMm = prefs.getFloat(KEY_IPD_MM, VrBoxSettings.DEFAULT_IPD_MM),
                lensCenterOffsetMm = prefs.getFloat(KEY_LENS_CENTER_MM, 0f),
                screenToLensMm = prefs.getFloat(KEY_SCREEN_TO_LENS_MM, VrBoxSettings.DEFAULT_SCREEN_TO_LENS_MM),
                distortionK = prefs.getFloat(KEY_DISTORTION_K, 0.22f),
                imageScale = prefs.getFloat(KEY_VR_IMAGE_SCALE, 1f),
                fullscreen = prefs.getBoolean(KEY_VR_FULLSCREEN, true),
                swapEyes = prefs.getBoolean(KEY_SWAP_EYES, false),
            ),
            hand = HandSettings(
                enabled = prefs.getBoolean(KEY_HAND_ENABLED, true),
                sensitivity = prefs.getFloat(KEY_HAND_SENSITIVITY, 0.55f),
                smoothing = prefs.getFloat(KEY_HAND_SMOOTHING, 0.40f),
                calibrationX = prefs.getFloat(KEY_HAND_CAL_X, 0f),
                calibrationY = prefs.getFloat(KEY_HAND_CAL_Y, 0f),
                showSkeleton = prefs.getBoolean(KEY_HAND_SKELETON, true),
            ),
            passthrough = PassthroughSettings(
                enabled = prefs.getBoolean(KEY_PT_ENABLED, true),
                brightness = prefs.getFloat(KEY_PT_BRIGHTNESS, 1f),
                // `null` é um valor com significado ("não mexer na exposição"), então não dá para
                // usar um default numérico: o sentinela abaixo distingue "ausente" de "gravado".
                exposureCompensation = if (prefs.contains(KEY_PT_EXPOSURE)) {
                    prefs.getInt(KEY_PT_EXPOSURE, 0)
                } else {
                    null
                },
            ),
            trackingMode = enumOrDefault(prefs.getString(KEY_TRACKING_MODE, null), TrackingMode.AUTO),
            displayMode = enumOrDefault(prefs.getString(KEY_DISPLAY_MODE, null), DisplayMode.MR_PASSTHROUGH),
            environment = enumOrDefault(prefs.getString(KEY_ENVIRONMENT, null), EnvironmentId.MODERN_ROOM),
            graphicsQuality = enumOrDefault(prefs.getString(KEY_QUALITY, null), GraphicsQuality.AUTO),
            preset = enumOrDefault(prefs.getString(KEY_PRESET, null), InterfacePreset.MEDIUM),
            schemaVersion = version,
        )
        return settings.sanitized()
    }

    fun save(settings: ZyvoxSettings) {
        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, ZyvoxSettings.CURRENT_SCHEMA_VERSION)
            // Layout
            .putFloat(KEY_DISTANCE_M, settings.layout.distanceM)
            .putFloat(KEY_HEIGHT_M, settings.layout.heightM)
            .putFloat(KEY_OFFSET_X_M, settings.layout.offsetXM)
            .putFloat(KEY_TILT_DEG, settings.layout.tiltDeg)
            .putFloat(KEY_SCALE, settings.layout.scale)
            .putBoolean(KEY_PINNED, settings.layout.pinned)
            // VR Box
            .putFloat(KEY_IPD_MM, settings.vrBox.ipdMm)
            .putFloat(KEY_LENS_CENTER_MM, settings.vrBox.lensCenterOffsetMm)
            .putFloat(KEY_SCREEN_TO_LENS_MM, settings.vrBox.screenToLensMm)
            .putFloat(KEY_DISTORTION_K, settings.vrBox.distortionK)
            .putFloat(KEY_VR_IMAGE_SCALE, settings.vrBox.imageScale)
            .putBoolean(KEY_VR_FULLSCREEN, settings.vrBox.fullscreen)
            .putBoolean(KEY_SWAP_EYES, settings.vrBox.swapEyes)
            // Mãos
            .putBoolean(KEY_HAND_ENABLED, settings.hand.enabled)
            .putFloat(KEY_HAND_SENSITIVITY, settings.hand.sensitivity)
            .putFloat(KEY_HAND_SMOOTHING, settings.hand.smoothing)
            .putFloat(KEY_HAND_CAL_X, settings.hand.calibrationX)
            .putFloat(KEY_HAND_CAL_Y, settings.hand.calibrationY)
            .putBoolean(KEY_HAND_SKELETON, settings.hand.showSkeleton)
            // Passthrough
            .putBoolean(KEY_PT_ENABLED, settings.passthrough.enabled)
            .putFloat(KEY_PT_BRIGHTNESS, settings.passthrough.brightness)
            .apply()

        // `Int?` não tem representação em `putInt`, então o nulo é uma remoção de chave. Fazer
        // isto no mesmo `edit()` acima exigiria um `putInt` com sentinela numérico, e um valor
        // legítimo de compensação de exposição pode ser qualquer inteiro — inclusive o sentinela.
        //
        // Editor nomeado, sem bloco `apply {}`: o receiver ali dentro seria o próprio
        // `SharedPreferences.Editor`, que também tem um método `apply()`. `prefs.edit().apply {
        // … }.apply()` compila, mas qual dos dois `apply` roda em cada ponto passa a depender de
        // resolução de escopo em vez de estar escrito — e este projeto já pagou duas vezes por
        // essa classe de ambiguidade.
        val editor = prefs.edit()
        val exposure = settings.passthrough.exposureCompensation
        if (exposure == null) {
            editor.remove(KEY_PT_EXPOSURE)
        } else {
            editor.putInt(KEY_PT_EXPOSURE, exposure)
        }
        // Enums por nome, não por `ordinal`: reordenar as constantes de um enum é uma mudança
        // inocente no código que, com ordinal, trocaria silenciosamente a configuração gravada
        // do usuário (quem salvou "Média" acordaria em "Longe").
        editor.putString(KEY_TRACKING_MODE, settings.trackingMode.name)
        editor.putString(KEY_DISPLAY_MODE, settings.displayMode.name)
        editor.putString(KEY_ENVIRONMENT, settings.environment.name)
        editor.putString(KEY_QUALITY, settings.graphicsQuality.name)
        editor.putString(KEY_PRESET, settings.preset.name)
        editor.apply()
    }

    /** Apaga tudo. O próximo [load] devolve os defaults do construtor. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Lê um enum pelo nome, caindo no default.
     *
     * `enumValueOf` lança `IllegalArgumentException` para nome desconhecido — que é o que está
     * gravado se uma versão antiga do app tinha uma constante que foi renomeada ou removida. Tratar
     * isso como default é a diferença entre "a configuração voltou ao padrão" e "o app fecha na
     * partida", e a partida é justamente onde não há como o usuário se recuperar.
     */
    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T {
        if (raw == null) return fallback
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }

    private companion object {
        const val FILE_NAME = "zyvox_settings"

        const val KEY_SCHEMA_VERSION = "schema_version"

        const val KEY_DISTANCE_M = "layout_distance_m"
        const val KEY_HEIGHT_M = "layout_height_m"
        const val KEY_OFFSET_X_M = "layout_offset_x_m"
        const val KEY_TILT_DEG = "layout_tilt_deg"
        const val KEY_SCALE = "layout_scale"
        const val KEY_PINNED = "layout_pinned"

        const val KEY_IPD_MM = "vr_ipd_mm"
        const val KEY_LENS_CENTER_MM = "vr_lens_center_mm"
        const val KEY_SCREEN_TO_LENS_MM = "vr_screen_to_lens_mm"
        const val KEY_DISTORTION_K = "vr_distortion_k"
        const val KEY_VR_IMAGE_SCALE = "vr_image_scale"
        const val KEY_VR_FULLSCREEN = "vr_fullscreen"
        const val KEY_SWAP_EYES = "vr_swap_eyes"

        const val KEY_HAND_ENABLED = "hand_enabled"
        const val KEY_HAND_SENSITIVITY = "hand_sensitivity"
        const val KEY_HAND_SMOOTHING = "hand_smoothing"
        const val KEY_HAND_CAL_X = "hand_cal_x"
        const val KEY_HAND_CAL_Y = "hand_cal_y"
        const val KEY_HAND_SKELETON = "hand_skeleton"

        const val KEY_PT_ENABLED = "pt_enabled"
        const val KEY_PT_BRIGHTNESS = "pt_brightness"
        const val KEY_PT_EXPOSURE = "pt_exposure"

        const val KEY_TRACKING_MODE = "tracking_mode"
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_ENVIRONMENT = "environment"
        const val KEY_QUALITY = "graphics_quality"
        const val KEY_PRESET = "interface_preset"
    }
}
