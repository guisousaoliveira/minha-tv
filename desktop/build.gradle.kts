import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

// Bad Wolf TV para Windows: Compose Desktop + libVLC (vlcj).
// O VLC oficial é baixado no build e vai junto no instalador (a pessoa não precisa instalar o VLC).

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// versão vem do GitHub (1.0.N, o mesmo número do APK da TV); no PC fica 1.0.0
val appVersion = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("uk.co.caprica:vlcj:4.8.3")
    testImplementation(kotlin("test"))
}

// ---------- VLC embutido ----------

val vlcVersion = "3.0.23"
val vlcSha256 = "992d19dbd0b8a7cde9167d2f7780b1ef6f92acc8a71acfa736101a21f35181e1"
val vlcZip = layout.buildDirectory.file("vlc/vlc-$vlcVersion-win64.zip")
val vlcResources = layout.buildDirectory.dir("vlc-recursos")

/** Baixa o VLC oficial (videolan.org) uma vez e confere o hash. */
val baixarVlc by tasks.registering {
    outputs.file(vlcZip)
    doLast {
        val zip = vlcZip.get().asFile
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (zip.exists() && sha(zip) == vlcSha256) return@doLast
        zip.parentFile.mkdirs()
        val url = "https://download.videolan.org/pub/videolan/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip"
        logger.lifecycle("Baixando $url")
        val tmp = File(zip.path + ".tmp")
        uri(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val got = sha(tmp)
        if (got != vlcSha256) { tmp.delete(); throw GradleException("VLC baixado com hash errado: $got") }
        zip.delete(); tmp.renameTo(zip)
    }
}

/** Só o que o player usa: as duas DLLs e os plugins (sem interface, skins, lua e afins). */
val prepararVlc by tasks.registering(Sync::class) {
    dependsOn(baixarVlc)
    from(zipTree(vlcZip)) {
        include("*/libvlc.dll", "*/libvlccore.dll", "*/plugins/**")
        exclude(
            "*/plugins/gui/**", "*/plugins/lua/**", "*/plugins/visualization/**", "*/plugins/control/**",
            "*/plugins/services_discovery/**", "*/plugins/stream_out/**", "*/plugins/access_output/**",
            "*/plugins/mux/**", "*/plugins/video_splitter/**", "*/plugins/plugins.dat",
        )
        eachFile { path = path.substringAfter('/') }
        includeEmptyDirs = false
    }
    into(vlcResources.map { it.dir("windows/vlc") })
}

// ---------- App e instalador ----------

compose.desktop {
    application {
        mainClass = "dev.guisousa.minhatv.desktop.MainKt"
        jvmArgs += listOf("-Xmx1200m", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Bad Wolf TV"
            packageVersion = appVersion
            description = "Bad Wolf TV - filmes, séries e TV ao vivo"
            vendor = "Bad Wolf"
            copyright = "Bad Wolf TV"
            modules("java.naming", "java.management", "jdk.unsupported", "jdk.crypto.ec", "java.net.http", "jdk.httpserver")
            appResourcesRootDir.set(vlcResources)
            windows {
                iconFile.set(project.file("src/main/resources/icone.ico"))
                menuGroup = "Bad Wolf TV"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // fixo: cada instalador novo atualiza o anterior em vez de instalar outro
                upgradeUuid = "6f1c5c2e-8f7b-4d6a-9a63-2b1d0b7e4a51"
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepararVlc) }
