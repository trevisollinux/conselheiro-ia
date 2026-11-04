plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.trevisolli.conselheirosticker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trevisolli.conselheirosticker"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Mesma chave pública de teste do Consigliere: instala por cima sem desinstalar.
    signingConfigs {
        create("fixed") {
            storeFile = file("../app/agenda.keystore")
            storePassword = "agendawidget"
            keyAlias = "agenda"
            keyPassword = "agendawidget"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
        }
    }

    // O WhatsApp lê os arquivos .webp/.png sem compressão extra.
    androidResources {
        noCompress += listOf("webp", "png")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Recorte automático do fundo, no próprio celular (sem internet, sem custo).
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
