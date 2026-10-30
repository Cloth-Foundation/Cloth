plugins {
    application
    kotlin("jvm") version "2.4.20"
}

group = "cloth"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
	// Tests
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// LLVM
	implementation("org.bytedeco:javacpp:1.5.14")
	implementation("org.bytedeco:llvm:22.1.8-1.5.14")
	runtimeOnly("org.bytedeco:llvm-platform:22.1.8-1.5.14")

	// Errors
	implementation("com.opencastsoftware:yvette:0.2.0")
	implementation("com.opencastsoftware:prettier4j:0.3.1")
	implementation("org.fusesource.jansi:jansi:2.4.0")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("cloth.EntryKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}

tasks.test {
    useJUnitPlatform()
    workingDir(rootProject.projectDir)
}
