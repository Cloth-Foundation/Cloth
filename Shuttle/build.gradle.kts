import org.gradle.api.tasks.options.Option
import org.gradle.util.internal.ArgumentsSplitter
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
	`java-base`
	distribution
}

group = "cloth"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val nativeShuttle = evaluationDependsOn(":Shuttle:native")
val jvm = nativeShuttle.extensions.getByType<KotlinMultiplatformExtension>()
	.targets.named<KotlinJvmTarget>("jvm").get()
val workerTests = jvm.compilations.getByName("test")
val workerJar = nativeShuttle.tasks.named<Jar>("jvmJar")

val workerRuntime = configurations.dependencyScope("workerRuntime")
val workerTestRuntime = configurations.dependencyScope("workerTestRuntime")
val workerRuntimeClasspath = configurations.resolvable("workerRuntimeClasspath") {
	extendsFrom(workerRuntime.get())
}
val workerTestRuntimeClasspath = configurations.resolvable("workerTestRuntimeClasspath") {
	extendsFrom(workerTestRuntime.get())
}
listOf(workerRuntimeClasspath, workerTestRuntimeClasspath).forEach { classpath ->
	classpath.configure {
		attributes {
			attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
			attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
		}
	}
}
dependencies {
	add(workerRuntime.name, project(path = nativeShuttle.path, configuration = "jvmRuntimeElements"))
	add(workerTestRuntime.name, project(path = nativeShuttle.path, configuration = "workerTestRuntimeElements"))
}

tasks.register("jar") {
	group = "build"
	description = "Build the Shuttle JVM worker JAR."
	dependsOn(workerJar)
}

val os = System.getProperty("os.name").lowercase()
val windows = os.startsWith("windows")
val arm64 = System.getProperty("os.arch") in setOf("aarch64", "arm64")
val nativeTarget = when {
	windows -> "mingwX64"
	os.startsWith("linux") -> if (arm64) "linuxArm64" else "linuxX64"
	os.startsWith("mac") -> if (arm64) "macosArm64" else "macosX64"
	else -> error("Unsupported Shuttle host: $os")
}
val commandName = if (windows) "shuttle.exe" else "shuttle"
val nativeFile = nativeShuttle.layout.buildDirectory.file(
	"bin/$nativeTarget/releaseExecutable/" + if (windows) commandName else "shuttle.kexe"
)
val stageNativeShuttle = tasks.register<Sync>("stageNativeShuttle") {
	dependsOn(":Shuttle:native:linkReleaseExecutable" + nativeTarget.replaceFirstChar(Char::uppercase))
	from(nativeFile) {
		into("bin")
		rename { commandName }
		filePermissions { unix("rwxr-xr-x") }
	}
	into("lib") {
		from(workerRuntimeClasspath)
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	}
	from("native/licenses") { into("licenses") }
	into(layout.buildDirectory.dir("native-stage"))
}
val stageHome = layout.buildDirectory.dir("native-stage")
val stageExecutable = stageHome.map { it.file("bin/$commandName").asFile.absolutePath }

distributions.main {
	distributionBaseName.set("shuttle")
	contents.from(stageNativeShuttle)
}

abstract class RunNativeShuttle : Exec() {
	@Option(option = "args", description = "Arguments for native Shuttle.")
	fun commandArguments(value: String) { setArgs(ArgumentsSplitter.split(value)) }
}

tasks.register<RunNativeShuttle>("run") {
	dependsOn(stageNativeShuttle)
	workingDir(rootProject.projectDir)
	standardInput = System.`in`
	environment("CLOTH_SHUTTLE_DEVELOPMENT_HOME", rootProject.projectDir.absolutePath)
	doFirst {
		executable = stageExecutable.get()
		environment("JAVA_HOME", jdk.get().metadata.installationPath.asFile.absolutePath)
	}
}

fun Test.useShuttleStage() {
	javaLauncher.set(jdk)
	workingDir(rootProject.projectDir)
	useJUnitPlatform()
	dependsOn(stageNativeShuttle)
	systemProperty("cloth.shuttle.parser", stageExecutable.get())
	systemProperty("shuttle.test.nativeHome", stageHome.get().asFile.absolutePath)
	systemProperty("cloth.toolchain.home", rootDir.absolutePath)
	systemProperty("cloth.toolchain.layout", "development")
}

fun Test.configureIntegrationTest() {
	inputs.files(rootProject.fileTree("Bootstrap") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("Standard-Library") { include("**/*.co", "module.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/Collections") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	doFirst { systemProperty("shuttle.test.runtimeClasspath", classpath.asPath) }
}

val testClasspath = files(workerTests.output.allOutputs, workerTestRuntimeClasspath)
val test = tasks.register<Test>("test") {
	group = "verification"
	testClassesDirs = workerTests.output.classesDirs
	classpath = testClasspath
	useShuttleStage()
	configureIntegrationTest()
}

// Execute configuration checks with native backend jars absent from the JVM classpath.
val configurationTest = tasks.register<Test>("configurationTest") {
	group = "verification"
	testClassesDirs = workerTests.output.classesDirs
	classpath = testClasspath.filter {
		!it.name.startsWith("llvm-") && !it.name.startsWith("javacpp-")
	}
	useShuttleStage()
	filter {
		includeTestsMatching("shuttle.ManifestTest")
		includeTestsMatching("shuttle.ProjectModelTest")
	}
}

nativeShuttle.tasks.named<Test>("jvmTest") {
	useShuttleStage()
	configureIntegrationTest()
}

tasks.check {
	dependsOn(test, configurationTest)
}
