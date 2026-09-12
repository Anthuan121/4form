import java.util.Properties

plugins {
    id("com.android.application") // Kotlin embutido desde o AGP 9; sem plugin Kotlin à parte
}

android {
    namespace = "com.mygoll.fourform"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mygoll.fourform"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0-4form"

        // A URL da ponte (com o segredo do caminho) vem do local.properties, que NUNCA e
        // versionado. O repo pode ir publico mostrando a arquitetura inteira sem entregar
        // o segredo; quem clonar compila com string vazia e o app degrada para "sem IA".
        // 🎓 E o padrao da industria: segredo em arquivo local + BuildConfig, nunca em fonte.
        val propsLocais = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "LLM_URL",
            "\"" + (propsLocais.getProperty("FOURFORM_LLM_URL") ?: propsLocais.getProperty("PREENCHE_LLM_URL") ?: "") + "\"",
        )
    }

    // BuildConfig desligado por padrão no AGP 9; a tela principal mostra a versão por ele.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // "Zero runtime dependency" stopped being true on 09/12 (brief 254): a real resume
    // arrives as PDF, and PDF has no text ready to read (it's a compressed stream, and
    // Android's own PdfRenderer only rasterizes a page as an image, it does not extract
    // text). com.tom-roush:pdfbox-android is the deliberate, single dependency of this app.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
}
