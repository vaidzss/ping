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

dependencies {
    implementation(project(":core"))
}
