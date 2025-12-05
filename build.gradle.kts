// Top-level build file where you can add configuration options common to all sub-projects/modules.

// It's recommended to define versions in a centralized place.
// For top-level build files, you can define them as constants.
val compose_version = "1.5.3" // This seems unused in this file but good to keep for reference

buildscript {
    // Define the version inside the buildscript block
    val kotlin_version = "1.9.10"

    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.2")
        // Now this reference will be resolved correctly
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version")
    }
}

plugins {
    id("com.android.application") version "8.1.2" apply false
    id("com.android.library") version "8.1.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
}
