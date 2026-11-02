import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    // Nem `org.jetbrains.kotlin.android` nem `org.jetbrains.kotlin.plugin.compose` aqui, e o
    // motivo está em gradle.properties. Resumo: estamos no Kotlin embutido do AGP 9, que (a)
    // proíbe explicitamente aplicar `org.jetbrains.kotlin.android` e (b) não traz o KGP para o
    // classpath, e o plugin do compilador Compose é um subplugin do KGP — sem o KGP aplicado
    // ele não tem onde se registrar. A compilação Kotlin e a transformação dos `@Composable`
    // vêm do próprio AGP, habilitadas por `buildFeatures { compose = true }` abaixo.
}

// ─── Modelo do MediaPipe Hand Landmarker ─────────────────────────────────────
// O `.task` é um bundle ZIP que o MediaPipe memory-maps em runtime. Ele não pode ser
// versionado aqui (binário grande) nem comprimido no APK (ver `noCompress` abaixo).
// Então o build o baixa e o registra como asset GERADO, por variant.
//
// Roda tanto no CI quanto na máquina do desenvolvedor. Offline, use
// `scripts/fetch-hand-model.sh`, que escreve no mesmo caminho.
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"

/**
 * Baixa o `hand_landmarker.task` para os assets gerados de um variant.
 *
 * ### Por que uma classe de task tipada, e não `tasks.register { doLast { … } }`
 *
 * Porque o AGP 9 **proíbe** `Provider` no SourceSet API. A forma anterior deste build era
 *
 * ```
 * android.sourceSets["main"].assets.srcDir(handModelDir)   // handModelDir é Provider<Directory>
 * ```
 *
 * e o Gradle abortava na configuração com "You cannot add Provider instances to the Android
 * SourceSet API", apontando para `SourceDirectories.addGeneratedDirectory`. Essa API só aceita
 * uma task tipada com uma propriedade de saída anotada — daí esta classe.
 *
 * Não é só burocracia. O comentário do demo do SceneView (`samples/android-demo/build.gradle`,
 * que faz o mesmo para o CHANGELOG) explica o ganho real: registrando por
 * `addGeneratedSourceDirectory`, o AGP passa a DONO do diretório de saída e declara o produtor
 * como dependência de *todo* consumidor que ele gera. Uma entrada em `srcDirs` é invisível ao
 * grafo de tarefas, então cada consumidor tem que ser ligado à mão — e aquele de que ninguém
 * lembra, `lintVitalAnalyzeRelease`, só existe em release. Foi assim que quebrou o deploy deles
 * na Play Store sem quebrar o CI de PR.
 *
 * A versão anterior deste arquivo tentava resolver isso com
 * `tasks.matching { merge…Assets }.configureEach { dependsOn(…) }`, que é exatamente a
 * ligação manual que o parágrafo acima descreve como frágil.
 */
abstract class FetchHandModelTask : DefaultTask() {
    /** Declarado como `@Input` para o up-to-date check sobreviver a mudança de URL. */
    @get:Input
    abstract val modelUrl: Property<String>

    /**
     * Diretório que vira raiz de assets. O AGP define um padrão via
     * `addGeneratedSourceDirectory`, mas desde o AGP 8.12 isso é só um *default* — o que
     * setamos aqui prevalece. Mantemos o caminho por variant para que `--continue` não veja
     * duas tasks escrevendo no mesmo lugar.
     */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * Modelo colocado à mão em `app/src/main/assets/` por `scripts/fetch-hand-model.sh`.
     *
     * Quando presente, esta task NÃO baixa e NÃO escreve nada em [outputDir]. Sem isso os dois
     * caminhos produzem `hand_landmarker.task` e o merge de assets falha com arquivo duplicado —
     * ou seja, usar o fallback offline quebraria o build em vez de salvá-lo.
     *
     * É `FileCollection` (e não `File`) para não capturar um `File` na configuração: o Gradle
     * trata isso como dependência implícita não declarada.
     */
    @get:InputFile
    @get:Optional
    abstract val manualModel: RegularFileProperty

    @TaskAction
    fun fetch() {
        // ── Fallback offline ──────────────────────────────────────────────────────────────
        if (manualModel.isPresent) {
            val manual = manualModel.get().asFile
            if (manual.exists() && manual.length() > 0L) {
                logger.lifecycle(
                    "hand_landmarker.task já fornecido em ${manual.path} (${manual.length()} bytes); " +
                        "download pulado e o diretório gerado fica vazio de propósito."
                )
                // Garante que o output declarado existe, mesmo vazio: o AGP registra este
                // diretório como raiz de assets e um output inexistente quebra o up-to-date check.
                outputDir.get().asFile.mkdirs()
                return
            }
        }

        val outDir = outputDir.get().asFile
        val target = File(outDir, "hand_landmarker.task")
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")

        logger.lifecycle("Baixando modelo do MediaPipe Hand Landmarker…")
        // `URI(…).toURL()` e não `URL(String)`: o construtor está deprecado desde o Java 20
        // e o build roda em JDK 21.
        val connection = URI.create(modelUrl.get()).toURL().openConnection()
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
                    "URL: ${modelUrl.get()} — verifique a rede ou use scripts/fetch-hand-model.sh."
            )
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        logger.lifecycle("hand_landmarker.task: ${bytes.size} bytes, sha256=$sha")

        target.writeBytes(bytes)
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

// ─── JVM target 21 ────────────────────────────────────────────────────────────────────────
// Não há bloco top-level `kotlin { compilerOptions { jvmTarget.set(...) } }` aqui, e isso é
// deliberado: aquela DSL referencia `org.jetbrains.kotlin.gradle.dsl.JvmTarget`, classe do
// Kotlin Gradle Plugin. Sob o Kotlin embutido do AGP 9 o KGP não está no classpath de build,
// então o script não *compilaria* — e o Gradle morreria na fase de configuração, antes de
// qualquer tarefa, apontando para a primeira tarefa do workflow em vez de apontar para a causa.
//
// O target 21 continua garantido pelo `compileOptions { source/targetCompatibility = VERSION_21 }`
// acima, que o Kotlin embutido respeita. Ele é obrigatório: o SceneView e o `kotlin-math` dele
// publicam funções `inline` em bytecode 21, e um target menor impede o app de chamá-las.

// ─── Registro do modelo como asset gerado, por variant ──────────────────────────────────────
// Substitui `android.sourceSets["main"].assets.srcDir(…)`, que o AGP 9 rejeita quando o
// argumento é um `Provider` — ver a KDoc de [FetchHandModelTask] para o erro exato e para o
// motivo de esta forma ser melhor que a ligação manual por `dependsOn` que havia antes.
//
// `layout` é capturado fora do lambda de propósito: dentro de `register<…> { }` o receptor é a
// task, e resolver `layout` ali depende de a task não ter propriedade homônima. Capturar antes
// remove essa ambiguidade em vez de confiar nela.
val buildDir = layout.buildDirectory
androidComponents {
    onVariants(selector().all()) { variant ->
        val fetch = tasks.register<FetchHandModelTask>(
            "fetch${variant.name.replaceFirstChar { it.uppercase() }}HandModel"
        ) {
            description = "Baixa o hand_landmarker.task do MediaPipe para os assets de ${variant.name}."
            group = "build"
            modelUrl.set(handModelUrl)
            outputDir.set(buildDir.dir("generated/mediapipe-model/${variant.name}"))
            // Fallback offline: se `scripts/fetch-hand-model.sh` já colocou o modelo nos assets
            // estáticos, a task não baixa e não escreve nada — ver a KDoc de `manualModel`.
            manualModel.set(file("src/main/assets/hand_landmarker.task"))
        }
        // `?.` porque `variant.sources.assets` é `SourceDirectories.Layered?` no DSL Kotlin,
        // embora o demo do SceneView (Groovy) o chame sem null-check — Groovy não distingue.
        variant.sources.assets?.addGeneratedSourceDirectory(fetch, FetchHandModelTask::outputDir)
    }
}

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
