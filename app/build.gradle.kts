plugins {
    java
}

group = "com.suiramdev.worldmap"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Hytale Server API - compile only since it's provided at runtime
    compileOnly(files("../server/Server/HytaleServer.jar"))

    // Testing
    testImplementation(libs.junit)
}

tasks.jar {
    // Set the archive name
    archiveBaseName.set("WorldmapHytalePlugin")
}
