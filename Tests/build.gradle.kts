plugins {
	java
}

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(platform("org.junit:junit-bom:5.10.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val installedToolchain = rootProject.layout.buildDirectory.dir("install/cloth")
val testScratch = rootProject.file("tmp")

tasks.test {
	dependsOn(":installDist")
	useJUnitPlatform()
	workingDir = rootDir
	inputs.dir(installedToolchain)
	inputs.dir(rootProject.file("examples/StandardLibrary")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/Interfaces") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(rootProject.file("examples/States.co")).withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.files(rootProject.fileTree("examples/States") { include("**/*.co", "**/*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("cloth.test.toolchain", installedToolchain.get().asFile.absolutePath)
	systemProperty("cloth.test.workspace", rootDir.absolutePath)
	systemProperty("java.io.tmpdir", testScratch.absolutePath)
	doFirst { testScratch.mkdirs() }
}
