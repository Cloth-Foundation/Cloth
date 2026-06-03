import org.gradle.kotlin.dsl.dependencies

dependencies {
    // tests
    add("testImplementation", platform("org.junit:junit-bom:6.0.0"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

    // lombok
    add("compileOnly", "org.projectlombok:lombok:1.18.46")
    add("annotationProcessor", "org.projectlombok:lombok:1.18.46")

    // logging
    add("implementation", "org.slf4j:slf4j-api:2.0.13")
    add("implementation", "ch.qos.logback:logback-classic:1.5.32")

    // preconditions
    add("implementation", "com.google.guava:guava:33.4.0-jre")

    // annotations
    add("compileOnly", "org.jetbrains:annotations:26.1.0")
}
