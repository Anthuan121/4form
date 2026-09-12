import java.util.Properties

plugins {
    id("com.android.application") // Kotlin bundled since AGP 9; no separate Kotlin plugin
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

        // The bridge URL (with the path secret) comes from local.properties, which is NEVER
        // versioned. The repo can go public showing the whole architecture without giving
        // away the secret; whoever clones it builds with an empty string and the app degrades to "no AI".
        // 🎓 This is industry standard: secret in a local file + BuildConfig, never in source.
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

    // BuildConfig is off by default in AGP 9; the main screen shows the version through it.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Zero purpose-built runtime dependency: plain Activity + platform theme.
    testImplementation("junit:junit:4.13.2")
}
