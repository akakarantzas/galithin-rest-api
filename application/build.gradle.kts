import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    application
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":sources"))

    implementation("io.ktor:ktor-server-core:3.4.0")
    implementation("io.ktor:ktor-server-netty:3.4.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.0")
    implementation("io.ktor:ktor-serialization-jackson:3.4.0")
    implementation("io.ktor:ktor-server-status-pages:3.4.0")
    implementation("io.ktor:ktor-server-cors:3.4.0")
    implementation("io.ktor:ktor-server-compression:3.4.0")
    implementation("io.ktor:ktor-server-call-logging:3.4.0")
    implementation("io.ktor:ktor-server-metrics-micrometer:3.4.0")
    implementation("io.github.reactivecircus.cache4k:cache4k:0.14.0")
    implementation("io.micrometer:micrometer-core:1.16.3")
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.3")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("org.yaml:snakeyaml:2.5")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.microsoft.playwright:playwright:1.60.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation("io.ktor:ktor-server-test-host:3.4.0")
}

application {
    mainClass.set("com.galithin.web.application.MainKt")
}

tasks.named<ShadowJar>("shadowJar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    archiveFileName.set("galithin-rest-api.jar")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest.attributes["Main-Class"] = application.mainClass.get()
}

tasks.processResources {
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
}

tasks.named("build") { dependsOn("shadowJar") }
