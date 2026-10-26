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
    }
}
