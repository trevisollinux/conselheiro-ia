plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.trevisolli.conselheirosticker"
    compileSdk = 36

    defaultConfig {
        // Nome definitivo na Play Store (não muda depois de publicado).
        applicationId = "com.trevisolli.unusualstickers"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.7"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // APK de teste: chave fixa e pública (repositório aberto), para instalar por cima sem desinstalar.
    // Loja: chave de upload que só existe nos segredos do GitHub (variáveis UPLOAD_*), a mesma do Consigliere.
    val uploadKeystore = System.getenv("UPLOAD_KEYSTORE_PATH")
    signingConfigs {
        create("fixed") {
            storeFile = file("../app/agenda.keystore")
            storePassword = "agendawidget"
            keyAlias = "agenda"
            keyPassword = "agendawidget"
        }
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            // Sem a chave de upload, o release sai sem assinatura (não serve para a loja).
            signingConfig = if (uploadKeystore != null) signingConfigs.getByName("upload") else null
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
    // Backup automático semanal numa pasta escolhida pelo usuário.
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Recorte automático do fundo, no próprio celular (sem internet, sem custo).
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
