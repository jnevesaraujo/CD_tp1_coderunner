plugins {
    id("com.google.protobuf") version "0.10.0" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

subprojects {
    group = "pt.isel"
    version = "1.0"

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
    }
}