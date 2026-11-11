plugins {
	application
	`java-library`
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
	api("com.opencastsoftware:yvette:0.2.0")
	api("com.opencastsoftware:prettier4j:0.3.1")
	implementation("org.fusesource.jansi:jansi:2.4.3")
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
	// Native Bootstrap fixtures live outside this project's source set.
	inputs.files(rootProject.fileTree("Bootstrap") { include("**/*.co") })
		.withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.dir(rootProject.file("examples/StandardLibrary")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/Collections") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/Interfaces") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(rootProject.file("examples/Interfaces.co")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(rootProject.file("examples/States.co")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/States") { include("**/*.co") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(rootProject.file("examples/LinearCollections.co")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("Standard-Library") { include("**/*.co") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(rootProject.file(".agents/skills/cloth-generics/references/acceptance-fixtures.md")).withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("cloth.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

// Verify import syntax and its current analysis boundary without native backend jars.
tasks.register<Test>("importSyntaxTest") {
	workingDir(rootProject.projectDir)
	systemProperty("cloth.test.nativeFreeImports", true)
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath.filter {
		!it.name.startsWith("llvm-") && !it.name.startsWith("javacpp-")
	}
	useJUnitPlatform()
	filter {
		includeTestsMatching("cloth.ImportParsingTest")
		includeTestsMatching("cloth.ImportStageBoundaryTest")
	}
}

tasks.check {
	dependsOn("importSyntaxTest", "importResolutionTest", "importSemanticsTest")
	dependsOn("genericSyntaxTest")
}

// Generic source identities need JVM API types, but no native backend binaries.
tasks.register<Test>("genericSyntaxTest") {
	workingDir(rootProject.projectDir)
	systemProperty("cloth.test.nativeFreeGenerics", true)
	systemProperty("org.bytedeco.javacpp.loadlibraries", "false")
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath.filter {
		!Regex("(?:llvm|javacpp)-.*-(?:android|ios|linux|macosx|windows)-.*\\.jar").matches(it.name)
	}
	inputs.file(rootProject.file(".agents/skills/cloth-generics/references/acceptance-fixtures.md")).withPathSensitivity(PathSensitivity.RELATIVE)
	useJUnitPlatform()
	filter {
		includeTestsMatching("cloth.GenericParsingTest")
		includeTestsMatching("cloth.GenericNamesTest")
		includeTestsMatching("cloth.GenericBoundsTest")
		includeTestsMatching("cloth.GenericClosedTypesTest")
		includeTestsMatching("cloth.GenericInstantiationLimitsTest")
		includeTestsMatching("cloth.GenericClosedImportsTest")
		includeTestsMatching("cloth.GenericCallableTypingTest")
		includeTestsMatching("cloth.GenericCallableFlowTest")
		includeTestsMatching("cloth.GenericOverloadTypingTest")
		includeTestsMatching("cloth.GenericCallableImportsTest")
		includeTestsMatching("cloth.StandardListImportsTest")
	}
	inputs.files(rootProject.fileTree("Standard-Library") { include("**/*.co") }).withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("importResolutionTest") {
	workingDir(rootProject.projectDir)
	systemProperty("cloth.test.nativeFreeImports", true)
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath.filter {
		!it.name.startsWith("llvm-") && !it.name.startsWith("javacpp-")
	}
	useJUnitPlatform()
	filter { includeTestsMatching("cloth.ImportResolutionTest") }
	filter {
		includeTestsMatching("cloth.StandardLibraryTest")
		includeTestsMatching("cloth.StandardLibraryGraphTest")
		includeTestsMatching("cloth.StandardLibrarySelectionTest")
	}
}

tasks.register<Test>("importSemanticsTest") {
	workingDir(rootProject.projectDir)
	systemProperty("cloth.test.nativeFreeSemantics", true)
	systemProperty("org.bytedeco.javacpp.loadlibraries", "false")
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath.filter {
		// Builtin descriptors reference JVM API types; platform binary jars are unnecessary.
		!Regex("(?:llvm|javacpp)-.*-(?:android|ios|linux|macosx|windows)-.*\\.jar").matches(it.name)
	}
	useJUnitPlatform()
	filter { includeTestsMatching("cloth.ImportSemanticsTest") }
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
