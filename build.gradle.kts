plugins {
    id("com.google.gms.google-services") version "4.6.0" apply false
    id("com.android.application") version "9.3.1" apply false
    // AGP 9 uses built-in Kotlin; this raises its KGP dependency without applying it.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
