plugins {
    application
    java
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.shilapi.xcertplay.crvsim.CrvSimulatorCli")
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "skipped")
    }
}
