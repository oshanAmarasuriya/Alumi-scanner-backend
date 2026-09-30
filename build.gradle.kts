plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "lk.alumex"
version = "0.1.0"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")
    // Reads .env at startup, so settings live in one editable file instead of a shell incantation.
    implementation("me.paulschwarz:spring-dotenv:4.0.0")
    runtimeOnly("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
