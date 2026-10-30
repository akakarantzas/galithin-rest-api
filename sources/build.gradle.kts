plugins {
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":domain"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.h2database:h2:2.4.240")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("org.flywaydb:flyway-core:11.20.3")
    implementation("org.flywaydb:flyway-mysql:11.20.3")
    implementation("org.xerial.snappy:snappy-java:1.1.10.8")
    implementation("net.kyori:adventure-nbt:5.1.1")
    implementation("net.kyori:adventure-text-minimessage:5.1.1")
    implementation("net.kyori:adventure-text-serializer-gson:5.1.1")
    implementation("net.kyori:adventure-text-serializer-plain:5.1.1")
    implementation("org.yaml:snakeyaml:2.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.3")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.3")
}
