import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
	java
	distribution
	kotlin("jvm") version "2.4.20" apply false
}
group = "cloth"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(platform("org.junit:junit-bom:5.10.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// Package both commands from the same dependency graph and library snapshot.
evaluationDependsOn(":Compiler")
evaluationDependsOn(":Shuttle")
val compilerProject = project(":Compiler")
val shuttleProject = project(":Shuttle")
compilerProject.extensions.configure<JavaApplication> { applicationName = "cloth" }

abstract class JavaRuntimeExec : Exec() {
	@get:Inject abstract val fileSystemOperations: FileSystemOperations
}

val releaseJdk = javaToolchains.compilerFor(java.toolchain).map { it.metadata.installationPath }
val releasePlatform = releaseJdk.map { jdk ->
	val release = Properties().apply { jdk.file("release").asFile.inputStream().use(::load) }
	val os = when (val name = release.getProperty("OS_NAME")?.trim('"')) {
		"Windows" -> "windows"
		"Linux" -> "linux"
		"Darwin", "Mac OS X", "macOS" -> "macos"
		else -> error("Unsupported release JDK operating system: $name")
	}
	val arch = when (val name = release.getProperty("OS_ARCH")?.trim('"')) {
		"x86_64", "amd64" -> "x64"
		"aarch64", "arm64" -> "arm64"
		else -> error("Unsupported release JDK architecture: $name")
	}
	"$os-$arch"
}
val prepareJavaRuntime = tasks.register<JavaRuntimeExec>("prepareJavaRuntime") {
	group = "distribution"
	description = "Link the Java 25 runtime for the Cloth release host."
	val image = layout.buildDirectory.dir("generated/runtime")
	val jlink = releaseJdk.zip(releasePlatform) { jdk, platform ->
		jdk.file(if (platform.startsWith("windows-")) "bin/jlink.exe" else "bin/jlink")
	}
	val modules = "java.base,java.desktop,java.management,java.scripting,java.sql,jdk.charsets,jdk.unsupported"
	inputs.property("javaModules", modules)
	inputs.file(jlink).withPathSensitivity(PathSensitivity.NONE)
	inputs.files(releaseJdk.map { listOf(it.file("release"), it.dir("jmods"), it.file("lib/modules")) })
		.withPathSensitivity(PathSensitivity.RELATIVE)
	outputs.dir(image)
	doFirst {
		val destination = image.get().asFile
		fileSystemOperations.delete { delete(destination) }
		Files.createDirectories(destination.toPath().parent)
		commandLine(jlink.get().asFile.absolutePath, "--add-modules", modules,
			"--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-9",
			"--output", destination.absolutePath)
	}
}

fun CopySpec.includeJavaRuntime() {
	val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
	into("runtime") {
		from(prepareJavaRuntime)
		if (posix) eachFile {
			if (file.canExecute()) permissions { unix("rwxr-xr-x") }
		}
	}
}

val toolchainContents = layout.buildDirectory.dir("generated/toolchain")
val stageShuttle = shuttleProject.tasks.named<Sync>("stageNativeShuttle")
val shuttleExecutableName = if (System.getProperty("os.name").startsWith("Windows")) "shuttle.exe" else "shuttle"
val prepareToolchain = tasks.register<JavaExec>("prepareToolchain") {
	group = "distribution"
	description = "Validate and export the pinned standard-library sources and release provenance."
	classpath = shuttleProject.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
	dependsOn(stageShuttle)
	mainClass.set("shuttle.packaging.ToolchainBundle")
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	argumentProviders.add(CommandLineArgumentProvider {
		listOf(rootDir.absolutePath, toolchainContents.get().asFile.absolutePath, project.version.toString(),
			stageShuttle.get().destinationDir.resolve("bin/$shuttleExecutableName").absolutePath)
	})
	outputs.dir(toolchainContents)
	// Always recheck the Git pin/cleanliness, even if source timestamps are unchanged.
	outputs.upToDateWhen { false }
}

listOf(compilerProject, shuttleProject).forEach { commandProject ->
	commandProject.tasks.withType<Test>().configureEach {
		systemProperty("cloth.toolchain.home", rootDir.absolutePath)
		systemProperty("cloth.toolchain.layout", "development")
	}
	commandProject.tasks.withType<JavaExec>().configureEach {
		systemProperty("cloth.toolchain.home", rootDir.absolutePath)
		systemProperty("cloth.toolchain.layout", "development")
	}
	commandProject.tasks.withType<CreateStartScripts>().configureEach {
		inputs.property("clothToolchainLauncherFormat", 2)
		doLast {
			val unixAnchor = "        -classpath \"\$CLASSPATH\""
			val unixJavaAnchor = "if [ -n \"\$JAVA_HOME\" ] ; then"
			val unixText = unixScript.readText()
			check(unixText.contains(unixAnchor) && unixText.contains(unixJavaAnchor)) {
				"Unsupported Gradle Unix launcher template"
			}
			val unixBundledJava = """
				if [ -x "${'$'}APP_HOME/runtime/bin/java" ] ; then
				    JAVACMD="${'$'}APP_HOME/runtime/bin/java"
				elif [ -n "${'$'}JAVA_HOME" ] ; then
			""".trimIndent()
			unixScript.writeText(unixText.replace(unixJavaAnchor, unixBundledJava).replace(unixAnchor,
				"        \"-Dcloth.toolchain.home=\$APP_HOME\" \\\n" +
				"        -Dcloth.toolchain.layout=installed \\\n" + unixAnchor))
			val windowsAnchor = "\"%JAVA_EXE%\" %DEFAULT_JVM_OPTS%"
			val windowsJavaAnchor = "if defined JAVA_HOME goto findJavaFromJavaHome"
			val windowsText = windowsScript.readText()
			check(windowsText.contains(windowsAnchor) && windowsText.contains(windowsJavaAnchor)) {
				"Unsupported Gradle Windows launcher template"
			}
			val windowsBundledJava = "set \"JAVA_EXE=%APP_HOME%\\runtime\\bin\\java.exe\"\r\n" +
				"if exist \"%JAVA_EXE%\" goto execute\r\n" + windowsJavaAnchor
			windowsScript.writeText(windowsText.replace(windowsJavaAnchor, windowsBundledJava).replace(windowsAnchor,
				"\"%JAVA_EXE%\" \"-Dcloth.toolchain.home=%APP_HOME%\" " +
				"-Dcloth.toolchain.layout=installed %DEFAULT_JVM_OPTS%"))
		}
	}
	// Keep the existing per-command installDist/distZip/distTar tasks usable too.
	commandProject.extensions.configure<DistributionContainer> {
		named("main") {
			contents {
				from(prepareToolchain)
				includeJavaRuntime()
			}
		}
	}
}

listOf(project, compilerProject, shuttleProject).forEach { distributionProject ->
	listOf("distZip", "distTar").forEach { archive ->
		distributionProject.tasks.named<AbstractArchiveTask>(archive) { archiveClassifier.set(releasePlatform) }
	}
}

distributions {
	main {
		distributionBaseName.set("cloth")
		contents {
			from(prepareToolchain)
			from(stageShuttle)
			includeJavaRuntime()
			into("bin") {
				from(compilerProject.tasks.named("startScripts"))
				filePermissions { unix("rwxr-xr-x") }
			}
		}
	}
}

tasks.test {
	dependsOn(tasks.named("installDist"))
	inputs.dir(layout.buildDirectory.dir("install/cloth"))
	inputs.dir(rootProject.file("examples/StandardLibrary")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/Interfaces") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(rootProject.file("examples/States.co")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/States") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("cloth.test.toolchain", layout.buildDirectory.dir("install/cloth").get().asFile.absolutePath)
	systemProperty("cloth.test.workspace", rootDir.absolutePath)
	systemProperty("java.io.tmpdir", rootProject.file("tmp").absolutePath)
	doFirst { rootProject.file("tmp").mkdirs() }
}
