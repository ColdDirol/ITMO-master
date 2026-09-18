plugins {
    id("java")
    id("application")
}

group = "com.volhv"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

application {
    mainClass = "com.volhv.Main"
    // Словарь фраз обучается в памяти на всём корпусе — нужна крупная куча.
    applicationDefaultJvmArgs = listOf("-Xmx8g")
}

/** Полный цикл: кодирование, декодирование, побайтовая проверка. */
tasks.register<JavaExec>("roundtrip") {
    group = "application"
    description = "encode + decode + sha256-проверка датасета"
    mainClass = "com.volhv.Main"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf("-Xmx8g")
    args = listOf(
        "roundtrip",
        project.findProperty("input")?.toString() ?: "dataset.jsonl",
        layout.buildDirectory.file("dataset.hcf").get().asFile.path,
        layout.buildDirectory.file("dataset.decoded.jsonl").get().asFile.path,
    ) + (project.findProperty("opts")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
}
