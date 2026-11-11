plugins {
    application
    id("com.gradleup.shadow")
}

val mainClassName = "runserver.RunServer"
val dockerJavaVersion = "3.7.1"

dependencies {
    implementation(project(":CodeRunnerContract"))

    // Redis (Anexo 1 / Lab 04): RedisClient only exists from Jedis 7.2.0 onwards
    implementation("redis.clients:jedis:7.5.3")

    // Docker (Anexo 3): same versions as docker-java-runner
    implementation("com.github.docker-java:docker-java-core:$dockerJavaVersion")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:$dockerJavaVersion")

    // Logging backend, avoids the SLF4J "no provider" warning
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
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