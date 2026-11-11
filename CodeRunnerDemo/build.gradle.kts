plugins {
    application
    id("com.gradleup.shadow")
}

val mainClassName = "demo.Demo"

dependencies {
    implementation(project(":CodeRunnerClient"))
    implementation(project(":CodeRunnerContract"))
}

application {
    mainClass = mainClassName
}

tasks.shadowJar {
    archiveFileName = "Demo.jar"
    manifest {
        attributes["Main-Class"] = mainClassName
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}