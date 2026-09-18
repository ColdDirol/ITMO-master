plugins {
    id("java")
    id("application")
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.volhv"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val sparkVersion = "4.2.0"

dependencies {
    compileOnly("org.apache.spark:spark-core_2.13:$sparkVersion")
    compileOnly("org.apache.spark:spark-sql_2.13:$sparkVersion")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.volhv.FibonacciApp")
}

tasks.shadowJar {
    archiveBaseName.set("fibonacci-app")
    archiveClassifier.set("")
    archiveVersion.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
