import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf")
}

val grpcVersion = "1.84.0"
val protocVersion = "3.25.9"

dependencies {
    api("javax.annotation:javax.annotation-api:1.3.2")
    api("io.grpc:grpc-netty-shaded:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protocVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc") { }
            }
        }
    }
}