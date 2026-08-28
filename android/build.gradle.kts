// AGP 8.13 is the first line that supports compileSdk 36 (API 36 needs >= 8.9.1)
// and it requires Gradle 8.13, which the CI workflow pins.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}
