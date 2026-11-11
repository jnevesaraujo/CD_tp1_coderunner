plugins {
    application
    id("com.gradleup.shadow")
}

val mainClassName = "client.Client"

dependencies {
    implementation(project(":CodeRunnerContract"))
}

application {
    mainClass = mainClassName
}

tasks.shadowJar {
    archiveClassifier = "jar-with-dependencies"
    manifest {
        attributes["Main-Class"] = mainClassName
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}