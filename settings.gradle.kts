pluginManagement {
	plugins {
		kotlin("plugin.lombok") version "2.4.20"
	}
}

rootProject.name = "Cloth"
includeBuild("_build-logic")
include("Frontend")