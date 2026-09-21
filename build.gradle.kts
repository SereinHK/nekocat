// AGP 9 起内置 Kotlin 支持，无需再单独应用 org.jetbrains.kotlin.android
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
