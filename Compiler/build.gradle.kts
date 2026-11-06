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
	standardInput = System.`in`
}

tasks.register<JavaExec>("runExample") {
	group = "application"
	description = "Build and run a Cloth example through the compiler's run command."
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set(application.mainClass)
	jvmArgs(application.applicationDefaultJvmArgs)
	workingDir(rootProject.projectDir)
	standardInput = System.`in`
	doFirst { setArgs(listOf("run") + args.orEmpty()) }
}

tasks.test {
	useJUnitPlatform()
	workingDir(rootProject.projectDir)
	systemProperty("cloth.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

tasks.register<JavaExec>("probeIntegerWidths") {
	group = "verification"
	description = "Probe arbitrary integer LLVM lowering in isolated, timed child JVMs."
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass.set("cloth.backend.IntegerBackendProbeKt")
	jvmArgs(application.applicationDefaultJvmArgs)
	workingDir(rootProject.projectDir)
}

tasks.register<JavaExec>("probeIntegerProduction") {
	group = "verification"
	description = "Measure integrated full-width integer source programs in isolated child JVMs."
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass.set("cloth.backend.IntegerProductionProbeKt")
	jvmArgs(application.applicationDefaultJvmArgs)
	workingDir(rootProject.projectDir)
}
