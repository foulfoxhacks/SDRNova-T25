import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// JMBE (github.com/DSheirer/jmbe, GPL-3.0) by Dennis Sheirer: IMBE and AMBE+2 voice decoding. Sources are kept as
// upstream wrote them apart from the Android adaptations listed in README.md in this folder.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.slf4j.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
