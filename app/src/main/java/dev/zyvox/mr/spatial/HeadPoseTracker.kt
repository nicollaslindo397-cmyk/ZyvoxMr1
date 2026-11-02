package dev.zyvox.mr.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.float3

/**
 * Pose de cabeça 3DoF: a orientação do aparelho vira a orientação da câmera da cena.
 *
 * ### Escopo honesto
 * Isto é **rotação apenas**. Sem ARCore não há odometria visual-inercial, então não existe
 * posição no mundo: se o usuário andar para o lado, as janelas acompanham o aparelho em vez
 * de ficarem paradas na sala. Foi uma troca explícita de arquitetura (CameraX-only, para
 * funcionar em qualquer Android 9+ sem depender do Google Play Services for AR) e está
 * documentada em `docs/ARQUITETURA.md`.
 *
 * O que o 3DoF **dá** de bom: ao girar o aparelho, a cena gira junto, o que cria a sensação
 * espacial de olhar em volta — que é a parte da experiência Quest que mais importa para um
 * dock flutuante.
 *
 * ### Por que GAME_ROTATION_VECTOR
 * `TYPE_ROTATION_VECTOR` incorpora o magnetômetro. Perto de eletrônica (que é onde este app
 * será usado: mesa, suporte, perto do PC) o magnetômetro é ruidoso e faz a cena **derivar
 * lentamente** — o dock desliza sozinho. `TYPE_GAME_ROTATION_VECTOR` usa só acelerômetro e
 * giroscópio: não tem referência absoluta de norte, então pode derivar em yaw ao longo de
 * minutos, mas é muito mais estável no curto prazo, e para uma sessão de MR isso é a troca
 * certa. Cai para `ROTATION_VECTOR` se o aparelho não tiver o game variant.
 */
class HeadPoseTracker(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val sensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** `false` quando o aparelho não tem nenhum sensor de rotação utilizável. */
    val isAvailable: Boolean get() = sensor != null

    /** Nome do sensor em uso, para o HUD de debug. */
    val sensorName: String get() = sensor?.name ?: "nenhum"

    private val quaternionValues = FloatArray(4)

    @Volatile
    private var latest: Quaternion = Quaternion()

    /**
     * Orientação atual como quaternion, no sistema de coordenadas da cena.
     *
     * `@Volatile` porque o listener do sensor roda numa thread do sistema e o render lê na
     * main. Um quaternion é imutável o suficiente para esse uso (leitura de referência é
     * atômica), e evitamos lock no caminho de render.
     */
    val orientation: Quaternion get() = latest

    fun start() {
        val s = sensor ?: return
        // SENSOR_DELAY_GAME ≈ 50 Hz. Mais rápido que isso desperdiça bateria sem melhorar
        // nada visível, já que o render roda a 60 fps e interpola.
        sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != sensor?.type) return
        // O event pode ter 3, 4 ou 5 valores dependendo do sensor e da versão do Android.
        // `getQuaternionFromVector` aceita 3+ e é a rota que não depende do tamanho exato.
        // Não chamamos `getRotationMatrixFromVector`: seria trabalho descartado, já que o
        // que a cena quer é o quaternion, não a matriz.
        SensorManager.getQuaternionFromVector(quaternionValues, event.values)

        // getQuaternionFromVector devolve [w, x, y, z] (a partir do Android 4.4), enquanto
        // kotlin-math Quaternion é (x, y, z, w). A troca de ordem aqui é o tipo de detalhe
        // que, errado, produz uma cena que gira ao contrário — e é invisível em código.
        latest = Quaternion(
            quaternionValues[1],
            quaternionValues[2],
            quaternionValues[3],
            quaternionValues[0],
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /**
         * Eixo "para frente" da cena. Usado para posicionar o dock à frente do usuário
         * independentemente de para onde ele está olhando.
         */
        val FORWARD = float3(0f, 0f, -1f)
    }
}
