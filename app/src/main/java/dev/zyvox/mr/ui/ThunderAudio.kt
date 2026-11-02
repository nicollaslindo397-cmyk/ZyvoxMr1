package dev.zyvox.mr.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.exp
import kotlin.random.Random

/**
 * Trovão **sintetizado**, não um arquivo de áudio.
 *
 * ### Por que gerar em vez de tocar um asset
 * Um trovão gravado custaria um arquivo de centenas de KB no APK e uma licença. O som é
 * ruído de baixa frequência com envelope decrescente — exatamente o que se sintetiza em
 * poucas linhas. O buffer cabe inteiro em memória (~250 KB), então não há stream para
 * gerenciar: gera uma vez, rebobina, toca.
 *
 * ### O que faz soar trovão e não chiado
 * Duas escolhas, e ambas importam:
 *
 * 1. **Ruído marrom, não branco.** Branco tem energia igual por frequência e soa como estática
 *    de TV. Marrom integra o branco (cada amostra = anterior + delta), o que dá decaimento de
 *    ~6 dB/oitava: a energia se concentra nos graves, que é onde mora o estrondo.
 * 2. **Envolve em duas partes.** Ataque de ~40 ms (o estalo inicial) seguido de decaimento
 *    exponencial de ~3,4 s (o rolar do trovão). Sem o decaimento longo soa como um tiro, não
 *    como trovão.
 *
 * Há um `normalize()` no fim: o ruído marrom tem distribuição de amplitude imprevisível e pode
 * sair muito baixo ou clipar. Normalizar pelo pico medido torna o volume consistente entre
 * execuções, o que um gerador aleatório sozinho não garante.
 *
 * ### Falha é silenciosa, não fatal
 * `AudioTrack` falha em aparelho sem saída de áudio, com foco de áudio tomado, ou se o
 * sistema recusar a pista. Tudo está em `runCatching`: a tempestade continua com chuva e
 * relâmpago, só sem som. Um app de MR não pode deixar de renderizar porque o áudio não veio.
 */
class ThunderAudio(
    sampleRate: Int = SAMPLE_RATE,
    durationSeconds: Float = DURATION_S,
) {
    private val buffer: ShortArray
    private var track: AudioTrack? = null

    init {
        val count = (sampleRate * durationSeconds).toInt()
        buffer = synthesize(count, sampleRate)
    }

    /** Dispara o trovão. Chamado no momento do relâmpago. Nunca lança. */
    fun play() {
        runCatching {
            val t = track ?: createTrack()?.also { track = it } ?: return
            // `pause`+`flush` em vez de `stop`: `stop` numa pista em MODE_STATIC espera o
            // buffer terminar, então dois relâmpagos próximos deixariam o segundo atrasado.
            // Flush rebobina e o trovão novo começa imediatamente.
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                t.pause()
                t.flush()
            }
            t.reloadStaticData()
            t.play()
        }.onFailure { Log.w(TAG, "trovão não tocou", it) }
    }

    fun release() {
        runCatching {
            track?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.release()
            }
        }
        track = null
    }

    private fun createTrack(): AudioTrack? = runCatching {
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Em MODE_STATIC o buffer inteiro mora na pista, então o tamanho mínimo precisa caber
        // nele; se o sistema exigir mais, usamos o que ele pediu.
        val sizeBytes = maxOf(minBytes, buffer.size * 2)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA e não USAGE_ALARM: trovão de ambiente virtual é conteúdo,
                    // não alerta. Alarme ignoraria o volume de mídia do usuário.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(sizeBytes)
            .build()
            .also { it.write(buffer, 0, buffer.size) }
    }.onFailure { Log.w(TAG, "AudioTrack indisponível", it) }.getOrNull()

    private fun synthesize(count: Int, sampleRate: Int): ShortArray {
        val random = Random(THUNDER_SEED)
        val out = ShortArray(count)
        val attack = (ATTACK_MS / 1000f) * sampleRate
        val decay = (sampleRate / DECAY_PER_SECOND).toFloat()

        var brown = 0.0
        var peak = 0.0
        for (i in 0 until count) {
            // Ruído marrom: integra o branco. O `* 0.985` é um vazamento que impede o
            // integrador de derivar sem limite — sem ele, `brown` cresce até saturar e o som
            // vira um DC offset, não um estrondo.
            brown = brown * 0.985 + (random.nextDouble() * 2.0 - 1.0)
            val envelope = if (i < attack) {
                i / attack
            } else {
                exp(-(i - attack) / decay).toFloat().toDouble()
            }
            val sample = brown * envelope
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            // Guarda em float por enquanto; normaliza no segundo passo.
            out[i] = (sample * 8000).toInt().coerceIn(-32768, 32767).toShort()
        }

        // Normaliza pelo pico medido. Sem isto a altura do trovão dependeria da sorte da
        // semente, e um trovão inaudível é indistinguível de "o som não funciona".
        if (peak > 1e-9) {
            val gain = (TARGET_PEAK / peak).coerceAtMost(8.0)
            for (i in out.indices) {
                out[i] = (out[i].toInt() * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    private companion object {
        const val TAG = "ZyvoxThunder"
        const val SAMPLE_RATE = 22050
        const val DURATION_S = 3.6f
        const val ATTACK_MS = 40f

        /** Decaimento em amostras por segundo: ~3,4 s até o som cair a 1/e. */
        const val DECAY_PER_SECOND = 0.29

        const val TARGET_PEAK = 26000.0

        /**
         * Semente fixa de propósito. Um trovão diferente a cada relâmpago soaria inconsistente,
         * e semente fixa torna o buffer reproduzível — o que também deixa o custo de memória
         * previsível (~160 KB de `ShortArray`).
         */
        const val THUNDER_SEED = 0x5EED10L
    }
}


