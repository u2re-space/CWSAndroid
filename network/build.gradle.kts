plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

val networkCoreRoot = file("../app/src/main/kotlin/space/u2re/cws/service/network")

android {
    namespace = "space.u2re.cws.network"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets.getByName("main").java.setSrcDirs(emptyList<String>())
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    setSource(
        fileTree(networkCoreRoot) {
            include("ServerV2NetworkModule.kt")
            include("connect/EndpointConfig.kt")
            include("connect/EndpointIdentity.kt")
            include("connect/EndpointUrl.kt")
            include("http/Codec.kt")
            include("http/ServerV2HttpClient.kt")
            include("http/TokenExt.kt")
            include("http/legacy/HttpClient.kt")
            include("http/routers/ResponsesApi.kt")
            include("socket/ServerV2Packet.kt")
            include("socket/ServerV2SocketClient.kt")
            include("socket/ServerV2WireIdentity.kt")
            include("utils/PeerAssociationStore.kt")
        }
    )
}

dependencies {
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
