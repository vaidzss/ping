plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("dev.meshaid.node.MainKt")
}

tasks.named<JavaExec>("run") {
    // Without this, `gradlew run` gives the program a closed stdin: readLine()
    // returns null instantly and the node exits right after the banner.
    standardInput = System.`in`
}

dependencies {
    implementation(project(":core"))
}
