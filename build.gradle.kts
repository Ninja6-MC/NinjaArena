plugins {
    `java-library`
}

group = "com.ninja6"

// A release candidate passes the version derived from its tag (v0.5.0-rc.1 -> 0.5.0-rc.1);
// every other build keeps the snapshot version. plugin.yml and the JAR name follow it.
val releaseVersion = providers.gradleProperty("releaseVersion").orNull
val releaseVersionPattern = Regex("""0\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.[1-9][0-9]*)?""")
if (releaseVersion != null && !releaseVersionPattern.matches(releaseVersion)) {
    throw GradleException("releaseVersion must be 0.MINOR.PATCH with an optional -alpha.N, -beta.N or -rc.N suffix")
}
version = releaseVersion ?: "0.1.0-SNAPSHOT"

// The release candidate runs the unit suite a second time against the downloaded JAR in
// place of the compiled classes and resources, so the tests exercise the bytes that ship.
val releaseJar = providers.gradleProperty("releaseJar").orNull?.let { file(it) }

// paper-api 26.3 is Java 25 bytecode and its Gradle metadata requires a Java 25 consumer,
// so the toolchain and the release target both follow it.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/") {
        content {
            includeGroupByRegex("org\\.geysermc.*")
            includeGroupByRegex("org\\.cloudburstmc.*")
        }
    }
    maven("https://repo.helpch.at/releases/") {
        content { includeGroup("me.clip") }
    }
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperApi = "io.papermc.paper:paper-api:26.3.build.223-stable"

dependencies {
    compileOnly(paperApi)
    compileOnly("org.geysermc.geyser:api:2.12.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

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
        if (releaseJar != null) {
            if (releaseVersion == null) {
                throw GradleException("releaseJar needs releaseVersion, the version the JAR must carry")
            }
            if (!releaseJar.isFile) {
                throw GradleException("releaseJar does not exist: $releaseJar")
            }
            inputs.file(releaseJar)
            classpath = files(releaseJar) + (classpath - sourceSets.main.get().output)
            systemProperty("ninjaarena.releaseJar", releaseJar.absolutePath)
            systemProperty("ninjaarena.releaseVersion", releaseVersion)
        }
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
