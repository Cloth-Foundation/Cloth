// Root is a pure aggregator — it has no sources of its own. Shared dependencies
// live in dependencies.gradle.kts (applied by each module); shared build
// conventions live here.

allprojects {
    group = "cloth"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType(Test::class.java).configureEach {
        useJUnitPlatform()
    }
}
