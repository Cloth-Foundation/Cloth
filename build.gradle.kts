import java.nio.file.FileSystems
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
	`java-base`
	distribution
	kotlin("jvm") version "2.4.20" apply false
}
group = "cloth"
version = "1.0-SNAPSHOT"

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
val releaseMetadata = releaseJdk.map { jdk ->
	Properties().apply { jdk.file("release").asFile.inputStream().use(::load) }
}
val releasePlatform = releaseMetadata.map { release ->
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
val javaRuntimeImage = layout.buildDirectory.dir("generated/runtime")
val prepareJavaRuntime = tasks.register<JavaRuntimeExec>("prepareJavaRuntime") {
	group = "distribution"
	description = "Link the Java 25 runtime for the Cloth release host."
	val jlink = releaseJdk.zip(releasePlatform) { jdk, platform ->
		jdk.file(if (platform.startsWith("windows-")) "bin/jlink.exe" else "bin/jlink")
	}
	val modules = "java.base,java.desktop,java.management,java.scripting,java.sql,jdk.charsets,jdk.unsupported"
	inputs.property("javaModules", modules)
	inputs.file(jlink).withPathSensitivity(PathSensitivity.NONE)
	inputs.files(releaseJdk.map { listOf(it.file("release"), it.dir("jmods"), it.file("lib/modules")) })
		.withPathSensitivity(PathSensitivity.RELATIVE)
	outputs.dir(javaRuntimeImage)
	doFirst {
		val destination = javaRuntimeImage.get().asFile
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

val windowsHost = System.getProperty("os.name").startsWith("Windows")
val windowsSources = layout.projectDirectory.dir("Launcher/windows")
val windowsScriptSource = windowsSources.file("cloth.bat")
if (windowsHost) compilerProject.tasks.named<CreateStartScripts>("startScripts") {
	// Do not package older generated scripts left behind by an earlier launcher name.
	outputDir = compilerProject.layout.buildDirectory.dir("generated/clothStartScripts").get().asFile
}
val windowsExecutable = layout.buildDirectory.file("generated/bin/cloth-jvm.exe")
val compileLauncherBootstrap = tasks.register<JavaCompile>("compileLauncherBootstrap") {
	source(layout.projectDirectory.dir("Launcher/java"))
	classpath = files()
	javaCompiler.set(javaToolchains.compilerFor(java.toolchain))
	options.release.set(25)
	destinationDirectory.set(layout.buildDirectory.dir("classes/launcherBootstrap"))
}
val launcherBootstrap = tasks.register<Jar>("launcherBootstrap") {
	from(compileLauncherBootstrap.flatMap { it.destinationDirectory })
	archiveFileName.set("cloth-launcher.jar")
	destinationDirectory.set(layout.buildDirectory.dir("generated/launcher"))
}
val buildWindowsLauncher = tasks.register<Exec>("buildWindowsLauncher") {
	dependsOn(prepareJavaRuntime)
	val jli = javaRuntimeImage.map { it.file("bin/jli.dll") }
	val version = releaseMetadata.map { it.getProperty("JAVA_RUNTIME_VERSION", it.getProperty("JAVA_VERSION")).trim('"') }
	val objects = layout.buildDirectory.dir("tmp/buildWindowsLauncher")
	val buildScript = windowsSources.file("build.cmd").asFile
	inputs.files(windowsSources.file("main.cpp"), windowsSources.file("utf8.manifest"), buildScript)
		.withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(releaseJdk.map { listOf(it.file("include/jni.h"), it.file("include/win32/jni_md.h")) })
		.withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(jli).withPathSensitivity(PathSensitivity.NONE)
	inputs.property("platform", releasePlatform)
	inputs.property("javaVersion", version)
	outputs.file(windowsExecutable)
	workingDir(objects)
	commandLine("cmd.exe", "/d", "/c", buildScript.absolutePath)
	doFirst {
		check(releasePlatform.get() == "windows-x64") { "The Windows launcher and native Shuttle require a Windows x64 release JDK" }
		val javaVersion = version.get()
		check(javaVersion.matches(Regex("25(?:[.+-][A-Za-z0-9.+-]+)?"))) { "Unsupported bundled Java runtime version: $javaVersion" }
		val jliBytes = jli.get().asFile.readBytes()
		val digest = MessageDigest.getInstance("SHA-256").digest(jliBytes)
			.joinToString(", ") { "0x%02x".format(it.toInt() and 255) }
		val work = objects.get().asFile.toPath()
		Files.createDirectories(work)
		Files.writeString(work.resolve("bundled_jli.h"), """
			constexpr unsigned char expectedJliHash[] = {$digest};
			constexpr DWORD expectedJliSize = ${jliBytes.size};
			constexpr char bundledJavaVersion[] = "$javaVersion";
		""".trimIndent() + "\n")
		val output = windowsExecutable.get().asFile
		Files.createDirectories(output.toPath().parent)
		environment("CLOTH_LAUNCHER_JDK", releaseJdk.get().asFile.absolutePath)
		environment("CLOTH_LAUNCHER_OUTPUT", output.absolutePath)
	}
}
val verifyWindowsLauncher = tasks.register<Exec>("verifyWindowsLauncher") {
	dependsOn(buildWindowsLauncher, launcherBootstrap)
	// Use the exact image and launcher ABI being packaged. Java's dry run loads the module main.
	args("--cloth-shuttle-worker", "unused")
	doFirst {
		executable = windowsExecutable.get().asFile.absolutePath
		listOf("JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLOTH_SHUTTLE_DEVELOPMENT_HOME")
			.forEach { environment.remove(it) }
		environment("SHUTTLE_OPTS", "--dry-run --show-version")
	}
}
val verifiedWindowsExecutable = files(windowsExecutable).builtBy(verifyWindowsLauncher)

fun CopySpec.includeLaunchers(cloth: Boolean, shuttle: Boolean) {
	into("launcher") { from(launcherBootstrap) }
	if (!windowsHost) return
	into("bin") {
		if (cloth) from(verifiedWindowsExecutable) { rename { "cloth.exe" } }
		if (shuttle) from(verifiedWindowsExecutable)
	}
}

val toolchainContents = layout.buildDirectory.dir("generated/toolchain")
val stageShuttle = shuttleProject.tasks.named<Sync>("stageNativeShuttle")
val shuttleExecutableName = if (windowsHost) "shuttle.exe" else "shuttle"
val prepareToolchain = tasks.register<JavaExec>("prepareToolchain") {
	group = "distribution"
	description = "Validate and export the pinned standard-library sources and release provenance."
	classpath = files(stageShuttle.map { fileTree(it.destinationDir.resolve("lib")) { include("*.jar") } })
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

compilerProject.tasks.withType<Test>().configureEach {
	systemProperty("cloth.toolchain.home", rootDir.absolutePath)
	systemProperty("cloth.toolchain.layout", "development")
}
listOf(compilerProject, shuttleProject).forEach { commandProject ->
	commandProject.tasks.withType<JavaExec>().configureEach {
		systemProperty("cloth.toolchain.home", rootDir.absolutePath)
		systemProperty("cloth.toolchain.layout", "development")
	}
	commandProject.tasks.withType<CreateStartScripts>().configureEach {
		inputs.property("clothToolchainLauncherFormat", 4)
		if (windowsHost) inputs.file(windowsScriptSource).withPathSensitivity(PathSensitivity.NONE)
		doLast {
			val unixAnchor = "        -classpath \"\$CLASSPATH\" \\\n        ${mainClass.get()} \\"
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
			val unixBootstrap = """
				"-Dcloth.toolchain.home=${'$'}APP_HOME" \
				-Dcloth.toolchain.layout=installed \
				--class-path "" \
				--module-path "${'$'}APP_HOME/launcher/cloth-launcher.jar" \
				--module cloth.launcher/cloth.launcher.Bootstrap \
				${mainClass.get()} "${'$'}APP_HOME/lib" \
			""".trimIndent().prependIndent("        ")
			unixScript.writeText(unixText.replace(unixJavaAnchor, unixBundledJava).replace(unixAnchor, unixBootstrap))
			if (windowsHost) {
				windowsScript.writeText(windowsScriptSource.asFile.readText().replace("\r\n", "\n").replace("\n", "\r\n"))
				return@doLast
			}
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
				includeLaunchers(cloth = commandProject == compilerProject, shuttle = commandProject == shuttleProject)
			}
		}
	}
}

listOf(project, compilerProject, shuttleProject).forEach { distributionProject ->
	listOf("distZip", "distTar").forEach { archive ->
		distributionProject.tasks.named<AbstractArchiveTask>(archive) {
			archiveClassifier.set(releasePlatform)
			if (!windowsHost && distributionProject != compilerProject) {
				val shuttleMode = "rwxr-xr-x"
				// Track the per-file rule so existing archives are rebuilt when it changes.
				inputs.property("unixShuttleMode", shuttleMode)
				filesMatching("**/bin/shuttle") { permissions { unix(shuttleMode) } }
			}
		}
	}
}

distributions {
	main {
		distributionBaseName.set("cloth")
		contents {
			from(prepareToolchain)
			from(stageShuttle)
			includeJavaRuntime()
			includeLaunchers(cloth = true, shuttle = true)
			into("bin") {
				from(compilerProject.tasks.named("startScripts"))
				filePermissions { unix("rwxr-xr-x") }
			}
		}
	}
}

val test = tasks.register("test") {
	group = "verification"
	description = "Runs the packaged Cloth toolchain integration tests."
	dependsOn(":Tests:test")
}

tasks.check {
	dependsOn(test)
}
