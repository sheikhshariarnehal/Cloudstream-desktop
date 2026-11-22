import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.Short.reverseBytes as revShort
import java.lang.Integer.reverseBytes as revInt

plugins {
    kotlin("multiplatform") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

group = "com.lagradost.cloudstream"
version = "1.0.3"

kotlin {
    jvm("jvm")

    sourceSets {
        val jvmTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        val jvmMain by getting {
            kotlin.srcDirs(
                "src/jvmMain/kotlin",
                "../nehal-CloudStream/AllWish/src/main/kotlin",
                "../nehal-CloudStream/Aniwatch/src/main/kotlin",
                "../nehal-CloudStream/BdixCircleftp/src/main/kotlin",
                "../nehal-CloudStream/BdixICCFtp/src/main/kotlin",
                "../nehal-CloudStream/CTGMovies/src/main/kotlin",
                "../nehal-CloudStream/CineplexBD/src/main/kotlin",
                "../nehal-CloudStream/DhakaFlix/src/main/kotlin",
                "../nehal-CloudStream/DhakaFlixBDIX/src/main/kotlin",
                "../nehal-CloudStream/DiscoveryFTP/src/main/kotlin",
                "../nehal-CloudStream/FTPBD/src/main/kotlin",
                "../nehal-CloudStream/FmFtp/src/main/kotlin",
                "../nehal-CloudStream/JellyfinBD/src/main/kotlin",
                "../nehal-CloudStream/ShowTimeBD/src/main/kotlin",
                "../nehal-CloudStream/VegaMovies/src/main/kotlin",
                "../nehal-CloudStream/MovieBoxProviderIN/src/main/kotlin"
            )
            // Both Redowan providers include the same tracker; compile one shared copy.
            kotlin.exclude {
                it.file.canonicalFile == file("../nehal-CloudStream/BdixICCFtp/src/main/kotlin/com/redowan/AnalyticsTracker.kt").canonicalFile
            }
            dependencies {
                // CloudStream Core KMP Library
                implementation("com.lagradost.api:library")

                // Compose Desktop UI & Material 3
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)

                // Coroutines & Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

                // Network & Parsing
                implementation("org.jsoup:jsoup:1.17.2")
                implementation("org.mozilla:rhino:1.7.14")
                implementation("com.github.Blatzar:NiceHttp:0.4.11")
                implementation("org.json:json:20231013")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")

                // Desktop Video Player Engine (VLCj for format detection only)
                implementation("uk.co.caprica:vlcj:4.8.2")

                // Coil Image Loader for Desktop
                implementation("io.coil-kt.coil3:coil-compose:3.0.0-rc01")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.0-rc01")
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.0-rc01")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "CloudStream Desktop"
            packageVersion = "1.0.3"
            vendor = "CloudStream Team"
            description = "CloudStream Windows Desktop Application — High Performance Movies, TV Series & Anime Streamer"
            copyright = "© 2026 CloudStream Team"

            // vlcj/JNA callbacks rely on JDK modules beyond Compose Desktop's
            // minimal runtime image. Keep the complete runtime in the MSI so
            // the packaged app has the same capabilities as `gradlew run`.
            includeAllModules = true

            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
                menuGroup = "CloudStream"
                menu = true
                shortcut = true
                upgradeUuid = "a2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e"
                dirChooser = true
                perUserInstall = false
            }
        }

        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    }
}

tasks.register("generateIco") {
    doLast {
        val pngFile = file("src/jvmMain/resources/icon.png")
        val icoFile = file("src/jvmMain/resources/icon.ico")
        val pngBytes = pngFile.readBytes()
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        // ICONDIR header
        dos.writeShort(revShort(0.toShort()).toInt()) // Reserved
        dos.writeShort(revShort(1.toShort()).toInt()) // Type 1 = ICO
        dos.writeShort(revShort(1.toShort()).toInt()) // Count = 1
        // ICONDIRENTRY header
        dos.writeByte(0) // Width 256
        dos.writeByte(0) // Height 256
        dos.writeByte(0) // Color count
        dos.writeByte(0) // Reserved
        dos.writeShort(revShort(1.toShort()).toInt()) // Planes = 1
        dos.writeShort(revShort(32.toShort()).toInt()) // Bit count = 32
        dos.writeInt(revInt(pngBytes.size)) // Bytes in resource
        dos.writeInt(revInt(22)) // Offset = 22
        dos.write(pngBytes)
        dos.flush()
        icoFile.writeBytes(bos.toByteArray())
        println("Generated valid Win32 ICO file (${icoFile.length()} bytes)")
    }
}

tasks.matching { it.name.startsWith("package") || it.name.startsWith("create") }.configureEach {
    dependsOn("generateIco")
}
