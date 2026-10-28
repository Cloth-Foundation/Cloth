plugins {
    kotlin("jvm") version "2.4.20"
}

group = "cloth"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

	implementation("org.bytedeco:javacpp:1.5.14")
	implementation("org.bytedeco:llvm:22.1.8-1.5.14")
	runtimeOnly("org.bytedeco:llvm-platform:22.1.8-1.5.14")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}