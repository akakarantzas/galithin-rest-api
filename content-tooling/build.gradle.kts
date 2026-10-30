plugins {
    application
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":sources"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.yaml:snakeyaml:2.5")
}

application {
    mainClass.set("com.galithin.web.content.ContentToolMainKt")
}

tasks.register<JavaExec>("buildBundle") {
    group = "distribution"
    description = "Builds the external Galithin web content bundle."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args = providers.gradleProperty("galithinRoot")
        .map { listOf("--galithin-root", it) }
        .orElse(emptyList())
        .get()
    providers.gradleProperty("output").orNull?.let { args("--output", it) }
}
