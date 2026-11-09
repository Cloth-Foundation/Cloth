import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
	java
	distribution
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

val toolchainContents = layout.buildDirectory.dir("generated/toolchain")
val prepareToolchain = tasks.register<JavaExec>("prepareToolchain") {
	group = "distribution"
	description = "Validate and export the pinned standard-library sources and release provenance."
	classpath = shuttleProject.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
	mainClass.set("shuttle.packaging.ToolchainBundle")
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	args(rootDir.absolutePath, toolchainContents.get().asFile.absolutePath, project.version.toString())
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
	commandProject.tasks.named<CreateStartScripts>("startScripts") {
		inputs.property("clothToolchainLauncherFormat", 1)
		doLast {
			val unixAnchor = "        -classpath \"\$CLASSPATH\""
			val unixText = unixScript.readText()
			check(unixText.contains(unixAnchor)) { "Unsupported Gradle Unix launcher template" }
			unixScript.writeText(unixText.replace(unixAnchor,
				"        \"-Dcloth.toolchain.home=\$APP_HOME\" \\\n" +
				"        -Dcloth.toolchain.layout=installed \\\n" + unixAnchor))
			val windowsAnchor = "\"%JAVA_EXE%\" %DEFAULT_JVM_OPTS%"
			val windowsText = windowsScript.readText()
			check(windowsText.contains(windowsAnchor)) { "Unsupported Gradle Windows launcher template" }
			windowsScript.writeText(windowsText.replace(windowsAnchor,
				"\"%JAVA_EXE%\" \"-Dcloth.toolchain.home=%APP_HOME%\" " +
				"-Dcloth.toolchain.layout=installed %DEFAULT_JVM_OPTS%"))
		}
	}
	// Keep the existing per-command installDist/distZip/distTar tasks usable too.
	commandProject.extensions.configure<DistributionContainer> {
		named("main") { contents.from(prepareToolchain) }
	}
}

distributions {
	main {
		distributionBaseName.set("cloth")
		contents {
			from(prepareToolchain)
			into("bin") {
				from(compilerProject.tasks.named("startScripts"))
				from(shuttleProject.tasks.named("startScripts"))
				filePermissions { unix("rwxr-xr-x") }
			}
			into("lib") {
				from(shuttleProject.tasks.named("jar"))
				from(shuttleProject.configurations.named("runtimeClasspath"))
				duplicatesStrategy = DuplicatesStrategy.EXCLUDE
			}
		}
	}
}

tasks.test {
	dependsOn(tasks.named("installDist"))
	inputs.dir(layout.buildDirectory.dir("install/cloth"))
	inputs.dir(rootProject.file("examples/StandardLibrary")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/Interfaces") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("cloth.test.toolchain", layout.buildDirectory.dir("install/cloth").get().asFile.absolutePath)
	systemProperty("cloth.test.workspace", rootDir.absolutePath)
	systemProperty("java.io.tmpdir", rootProject.file("tmp").absolutePath)
	doFirst { rootProject.file("tmp").mkdirs() }
}
