plugins {
    `java-library`
}

group = "com.ninja6"
version = "0.1.0-SNAPSHOT"

// paper-api 26.3 is Java 25 bytecode and its Gradle metadata requires a Java 25 consumer,
// so the toolchain and the release target both follow it.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperApi = "io.papermc.paper:paper-api:26.3.build.223-stable"

dependencies {
    compileOnly(paperApi)

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Test scope only, so the tests parse plugin.yml with the classes the server uses.
    // src/main still compiles against paper-api as compileOnly.
    testImplementation(paperApi)

    // Paper 26.3 bundles these two JDBC drivers, so the plugin looks them up at runtime and
    // ships neither; the tests pin the bundled versions.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.2.0")
    // Runs the MySQL statements in H2's MySQL mode; no MySQL server is needed for the suite.
    testRuntimeOnly("com.h2database:h2:2.3.232")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    test {
        useJUnitPlatform()
        // The SQLite driver loads its native library; Paper grants this on the server.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// Kept outside the production JAR; only installed by the local stability harness.
val soak = sourceSets.create("soak") {
    compileClasspath += sourceSets.main.get().output + configurations.testCompileClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.register<Jar>("soakProbeJar") {
    archiveClassifier.set("soak-probe")
    from(soak.output)
}

tasks.check {
    dependsOn(soak.classesTaskName)
}
