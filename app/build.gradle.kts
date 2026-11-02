import java.net.URL
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

// ─── Modelo do MediaPipe Hand Landmarker ─────────────────────────────────────
// O `.task` é um bundle ZIP que o MediaPipe memory-maps em runtime. Ele não pode ser
// versionado aqui (binário grande) nem comprimido no APK (ver `noCompress` abaixo).
// Então o build o materializa num sourceSet de assets gerado, com cache local e
// verificação de integridade.
//
// Roda tanto no CI quanto na máquina do desenvolvedor. Offline, use
// `scripts/fetch-hand-model.sh`, que escreve no mesmo caminho.
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
val handModelDir = layout.buildDirectory.dir("generated/mediapipe-model")
val handModelFile = handModelDir.map { it.file("hand_landmarker.task") }

val fetchHandModel by tasks.registering {
    description = "Baixa o hand_landmarker.task do MediaPipe para os assets gerados."
    group = "build"

    inputs.property("url", handModelUrl)
    // Saída declarada como DIRETÓRIO: é o contrato que `addGeneratedSourceDirectory`
    // espera. O asset final fica em `assets/hand_landmarker.task` dentro do APK.
    outputs.dir(handModelDir)

    // Propriedade nomeada para o wiring do AGP referenciar sem depender da posição do
    // output na lista (`task.outputs.files` muda de ordem entre versões do AGP).
    val outputDir = handModelDir

    onlyIf {
        val f = handModelFile.get().asFile
        // `length() > 0` protege contra download anterior truncado: se ficou vazio, tenta
        // de novo em vez de assumir que está pronto.
        !(f.exists() && f.length() > 0L)
    }

    doLast {
        val f = handModelFile.get().asFile
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")

        logger.lifecycle("Baixando modelo do MediaPipe Hand Landmarker…")
        val connection = URL(handModelUrl).openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.getInputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }

        // O hand_landmarker.task (float16) tem ~7 MB. Algo muito menor indica resposta de
        // erro (proxy corporativo, página HTML, download interrompido). Falhar aqui é bem
        // melhor do que descobrir em runtime com `createFromOptions` estourando.
        val bytes = tmp.readBytes()
        if (bytes.size < 1_000_000) {
            tmp.delete()
            throw GradleException(
                "Download do modelo suspeito: ${bytes.size} bytes (esperado ~7 MB). " +
                    "URL: $handModelUrl — verifique a rede ou use scripts/fetch-hand-model.sh."
            )
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        logger.lifecycle("hand_landmarker.task: ${bytes.size} bytes, sha256=$sha")

        f.writeBytes(bytes)
        tmp.delete()
    }
}

android {
    namespace = "dev.zyvox.mr"

    // Alinha os segmentos ELF PT_LOAD das bibliotecas nativas em 16 KB. Filament e
    // MediaPipe embarcam `.so` pré-compilados; essa propriedade reescreve os cabeçalhos de
    // alinhamento no empacotamento, necessário em aparelhos com page size de 16 KB
    // (Android 15+). Valores aceitos: "4k" (padrão), "16k", "64k".
    experimentalProperties["android.nativeLibraryAlignmentPageSize"] = "16k"

    // Exigido pelo AAR do SceneView 4.65.0 (minCompileSdk = 37).
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.zyvox.mr"
        // 26 = Android 8.0. É o piso que o SceneView declara; na prática o hand tracking
        // só é utilizável em aparelho com GPU decente, mas não bloqueamos a instalação.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            // Debug não minifica: queremos stack traces legíveis enquanto o hand tracking e
            // o pipeline de WebView são ajustados em aparelho real.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Assinatura de release fica para quando houver keystore; o entregável deste
            // projeto é o APK debug.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // `.task` é um ZIP que o MediaPipe memory-maps em runtime: recomprimi-lo no APK
        // quebra o `createFromOptions`. `filamat`/`ktx` já são payloads comprimidos.
        noCompress += listOf("filamat", "ktx", "task")
    }

    packaging {
        resources {
            // Metadados duplicados que vários artifacts AndroidX embarcam cada um.
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/androidx/**",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    // O APK debug precisa ser instalável via `adb install` sem Play Store, então não
    // restringimos ABIs por padrão. Para o seu aparelho, corte com
    // `-PabiFilters=arm64-v8a`: o APK cai de ~150 MB para ~50 MB e o build acelera muito.
    if (project.hasProperty("abiFilters")) {
        splits {
            abi {
                isEnable = true
                reset()
                include(*(project.property("abiFilters") as String).split(",").toTypedArray())
                isUniversalApk = false
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// AGP 9 registra a extensão top-level `kotlin { }` sozinho. O JVM target 21 é obrigatório:
// o SceneView e o `kotlin-math` dele publicam `inline` functions em bytecode 21, e um
// target menor impede o app de chamá-las.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// O modelo baixado entra como assets. Usamos `srcDir` + `dependsOn` em vez de
// `addGeneratedSourceDirectory`: este é um download puro, sem output de task tipado do AGP,
// e o caminho simples tem menos superfície para quebrar entre versões do AGP.
// `fetchHandModel` é incremental (declara outputs e tem `onlyIf`), então não re-baixa.
android.sourceSets["main"].assets.srcDir(handModelDir)
tasks.matching { it.name.contains("merge", ignoreCase = true) && it.name.contains("Assets") }
    .configureEach { dependsOn(fetchHandModel) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    // Viewport 3D + ViewNode + Filament transitivo.
    implementation(libs.sceneview)

    // Passthrough: Preview desenha a câmera traseira; ImageAnalysis entrega os quadros YUV
    // que alimentam o MediaPipe; Lifecycle liga a câmera ao ciclo da Activity.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Hand tracking on-device. Ver a nota sobre `auto-value` no catálogo de versões.
    implementation(libs.mediapipe.tasks.vision) {
        exclude(group = "com.google.auto.value", module = "auto-value")
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
