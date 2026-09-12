// AGP 9.0.1 + Gradle 9.1 (combo já em cache nesta máquina). Desde o AGP 9 o Kotlin é
// EMBUTIDO no plugin Android: declarar org.jetbrains.kotlin.android quebra o build.
plugins {
    id("com.android.application") version "9.0.1" apply false
}
