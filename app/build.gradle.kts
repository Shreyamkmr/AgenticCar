plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.agenticcar"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.agenticcar"
        // Ktor/Netty rely on java.lang.invoke APIs that need API 26+.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // cloudflared is run as a process, not loaded: it must be extracted to nativeLibraryDir,
            // the one app location Android allows executing from.
            useLegacyPackaging = true
        }
        resources {
            // Netty and Ktor ship duplicate metadata files that collide in the APK.
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

configurations.configureEach {
    // ktor-server-netty pulls desktop native binaries (Linux/macOS/Windows QUIC, epoll,
    // kqueue). They cannot load on Android; Netty falls back to its NIO transport.
    exclude(group = "io.netty", module = "netty-codec-native-quic")
    exclude(group = "io.netty", module = "netty-transport-native-epoll")
    exclude(group = "io.netty", module = "netty-transport-native-kqueue")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    // Time-series storage
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Embedded MCP server over Ktor SSE
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    // Pure-Java halves of the excluded native transports: Ktor calls Epoll/KQueue.isAvailable()
    // before falling back to NIO, so these classes must exist even though they report false.
    implementation(libs.netty.transport.classes.epoll)
    implementation(libs.netty.transport.classes.kqueue)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
