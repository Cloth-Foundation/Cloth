plugins {
    java
    application
    id("org.graalvm.buildtools.native") version "1.1.1"
}

apply(from = rootProject.file("dependencies.gradle.kts"))

application {
    mainClass.set("cloth.ClothProgram")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("cloth")
            buildArgs.add("-O3")
        }
    }
}
