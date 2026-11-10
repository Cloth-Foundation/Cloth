import org.gradle.api.tasks.options.Option
import org.gradle.util.internal.ArgumentsSplitter

plugins {
	distribution
	kotlin("jvm") version "2.4.20"
	kotlin("plugin.serialization") version "2.4.20"
}

group = "cloth"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	implementation(project(":Compiler"))
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	testImplementation(platform("org.junit:junit-bom:5.10.0"))
	testImplementation(kotlin("test-junit5"))
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Execute configuration checks with native backend jars absent from the JVM classpath.
tasks.register<Test>("configurationTest") {
	workingDir(rootProject.projectDir)
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath.filter {
		!it.name.startsWith("llvm-") && !it.name.startsWith("javacpp-")
	}
	useJUnitPlatform()
	filter {
		includeTestsMatching("shuttle.ManifestTest")
		includeTestsMatching("shuttle.ProjectModelTest")
	}
}

tasks.check {
	dependsOn("configurationTest")
}

kotlin {
	jvmToolchain(25)
	sourceSets.main { kotlin.srcDir("src/shared/kotlin") }
}

tasks.jar {
	archiveBaseName.set("cloth-compiler-worker")
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
val nativeFile = project(":Shuttle:native").layout.buildDirectory.file(
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
		from(tasks.jar, configurations.runtimeClasspath)
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
	val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
	doFirst {
		executable = stageExecutable.get()
		environment("JAVA_HOME", jdk.get().metadata.installationPath.asFile.absolutePath)
	}
}

tasks.withType<Test>().configureEach {
	dependsOn(stageNativeShuttle)
	systemProperty("cloth.shuttle.parser", stageExecutable.get())
	systemProperty("shuttle.test.nativeHome", stageHome.get().asFile.absolutePath)
}

tasks.test {
	workingDir(rootProject.projectDir)
	useJUnitPlatform()
	inputs.files(rootProject.fileTree("Bootstrap") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("Standard-Library") { include("**/*.co", "module.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/Collections") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	systemProperty("shuttle.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
