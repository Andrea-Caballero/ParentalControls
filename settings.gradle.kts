pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()

    maven("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
    maven("https://jitpack.io")
  }
}

rootProject.name = "ParentalControl"
include(":app")
