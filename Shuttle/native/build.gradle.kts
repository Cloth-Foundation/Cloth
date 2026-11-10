import java.security.MessageDigest
import java.util.HexFormat
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
	kotlin("multiplatform")
	kotlin("plugin.serialization")
}

repositories {
	mavenCentral()
}

val tomlktSources = configurations.create("tomlktSources") {
	isCanBeConsumed = false
	isTransitive = false
}
dependencies.add(tomlktSources.name, "dev.eav.tomlkt:tomlkt-mingwx64:0.6.1:sources@jar")

// Apply the strict TOML fixes in the parser itself, where table and string context is available.
val prepareTomlParser = tasks.register<Sync>("prepareTomlParser") {
	from(tomlktSources.elements.map { artifacts -> zipTree(artifacts.single().asFile) })
	include("commonMain/**", "kotlinxMain/**")
	into(layout.buildDirectory.dir("generated/tomlkt"))
	doFirst {
		val digest = MessageDigest.getInstance("SHA-256").digest(tomlktSources.singleFile.readBytes())
		check(HexFormat.of().formatHex(digest) == "63c6c217c2ec72651d535b28c5cc01b2006f27b0707952aa91c4a739fb9d9a4c") {
			"Unexpected tomlkt 0.6.1 source archive"
		}
	}
	doLast {
		fun replace(file: File, original: String, corrected: String) {
			val source = file.readText()
			check(original in source && source.indexOf(original) == source.lastIndexOf(original)) {
				"tomlkt source changed at " + file.name
			}
			file.writeText(source.replace(original, corrected))
		}
		val directory = destinationDir.resolve("commonMain/dev/eav/tomlkt/internal/parser")
		val parser = directory.resolve("TomlElementParser.kt")
		replace(parser, "        val code = reader.read()", listOf(
			"        val code = reader.read()",
			"        if (currentChar == '\\r' && code != '\\n'.code) throwUnexpectedToken('\\r')",
		).joinToString("\n"))
		replace(parser, "            previousChar = currentChar", listOf(
			"            if ((code in 0..31 && code != 9 && code != 10 && code != 13) || code == 127) {",
			"                throwUnexpectedToken(code.toChar())",
			"            }",
			"            previousChar = currentChar",
		).joinToString("\n"))
		replace(parser, "                    justEnded = true\n                    proceed()\n                    break\n                }\n                else -> {\n                    throwUnexpectedTokenIf(current) { path != null }", """
                    justEnded = true
                    proceed()
                    while (!isEof && (getCurrent() == ' ' || getCurrent() == '\t')) proceed()
                    throwUnexpectedTokenIf(getCurrent()) { !isEof && it != '\r' && it != '\n' && it != Comment }
                    break
                }
                else -> {
                    throwUnexpectedTokenIf(current) { path != null }
		""".trim('\n', '\r').trimEnd())
		replace(parser, "                                'u' -> {\n                                    parseNullValue()\n                                }\n", "")
		val tree = directory.resolve("TreeNode.kt")
		replace(tree, "val isLast: Boolean", "var isLast: Boolean")
		replace(tree, "child.isLast.not()", "if (child.isLast) false else { child.isLast = true; true }")
	}
}

kotlin {
	mingwX64()
	linuxX64()
	linuxArm64()
	macosArm64()
	macosX64()
	applyDefaultHierarchyTemplate()
	targets.withType<KotlinNativeTarget>().configureEach {
		binaries.executable(listOf(NativeBuildType.RELEASE)) {
			baseName = "shuttle"
			entryPoint = "shuttle.nativecli.main"
		}
	}
	sourceSets.commonMain {
		kotlin.srcDir("../src/shared/kotlin")
		kotlin.srcDir(prepareTomlParser.map { it.destinationDir.resolve("commonMain") })
		dependencies {
			implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
			implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
		}
	}
	sourceSets.nativeMain {
		kotlin.srcDir(prepareTomlParser.map { it.destinationDir.resolve("kotlinxMain") })
	}
	val posixMain = sourceSets.create("posixMain") { dependsOn(sourceSets.nativeMain.get()) }
	sourceSets.getByName("linuxMain").dependsOn(posixMain)
	sourceSets.getByName("macosMain").dependsOn(posixMain)
	sourceSets.configureEach {
		languageSettings.optIn("kotlin.contracts.ExperimentalContracts")
		languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
		languageSettings.optIn("kotlinx.serialization.InternalSerializationApi")
		languageSettings.optIn("dev.eav.tomlkt.TomlSpecific")
	}
}
