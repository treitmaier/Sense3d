plugins {
    maven // to build jars & for jitpack releases
    kotlin("jvm") version "1.3.72"
}

group = "xyz.reitmaier"
version = "0.1"

repositories {
    mavenCentral()
    // maven(url = "https://oss.sonatype.org/content/repositories/snapshots") // pi4j-v2 snapshots
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    // Currently using armv7l pi4j-v2 library to address issues:
    // https://github.com/Pi4J/pi4j-v2/issues/26
    // https://github.com/Pi4J/pi4j-v2/issues/15
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // in future switch to
    // implementation("com.pi4j:pi4j-core:2.0-SNAPSHOT")
    // implementation("com.pi4j:pi4j-plugin-raspberrypi:2.0-SNAPSHOT")
    // implementation("com.pi4j:pi4j-plugin-pigpio:2.0-SNAPSHOT")

    // logging
//    implementation("org.apache.logging.log4j:log4j-core:2.11.0")
//    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.11.0")
    implementation("io.github.microutils:kotlin-logging:1.7.10")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
}