import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

val dusseldorfKtorVersion = "2.1.6.2-6ce5eaa"
val ktorVersion = ext.get("ktorVersion").toString()
val slf4jVersion = ext.get("slf4jVersion").toString()
val amazonawsVersion = "1.12.66"
val tikaVersion = "2.1.0"
val fuelVersion = "2.3.1"
val localstackVersion = "1.16.0"
val mockKVersion = "1.12.0"
val jsonassertVersion = "1.5.0"
val systemRulesVersion = "1.19.0"

val mainClass = "no.nav.helse.K9DokumentKt"

plugins {
    kotlin("jvm") version "1.5.30"
    id("com.github.johnrengelman.shadow") version "7.0.0"
}
buildscript {
    apply("https://raw.githubusercontent.com/navikt/dusseldorf-ktor/6ce5eaa4666595bb6b550fca5ca8bbdc242961a0/gradle/dusseldorf-ktor.gradle.kts")
}
dependencies {
    // Server
    implementation ( "no.nav.helse:dusseldorf-ktor-core:$dusseldorfKtorVersion")
    implementation ( "no.nav.helse:dusseldorf-ktor-jackson:$dusseldorfKtorVersion")
    implementation ( "no.nav.helse:dusseldorf-ktor-metrics:$dusseldorfKtorVersion")
    implementation ( "no.nav.helse:dusseldorf-ktor-health:$dusseldorfKtorVersion")
    implementation ( "no.nav.helse:dusseldorf-ktor-auth:$dusseldorfKtorVersion")
    implementation("com.github.kittinunf.fuel:fuel:$fuelVersion")
    implementation("com.github.kittinunf.fuel:fuel-coroutines:$fuelVersion"){
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }

    // Client
    implementation ( "no.nav.helse:dusseldorf-ktor-client:$dusseldorfKtorVersion")

    // Lagring
    implementation("com.amazonaws:aws-java-sdk-s3:$amazonawsVersion")
    implementation("org.slf4j:jcl-over-slf4j:$slf4jVersion")

    // Sjekke dokumenter
    implementation("org.apache.tika:tika-core:$tikaVersion")

    // Test
    testImplementation ( "no.nav.helse:dusseldorf-test-support:$dusseldorfKtorVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion") {
        exclude(group = "org.eclipse.jetty")
    }
    testImplementation("org.testcontainers:localstack:$localstackVersion")
    testImplementation("io.mockk:mockk:$mockKVersion")
    testImplementation("org.skyscreamer:jsonassert:$jsonassertVersion")
    testImplementation( "com.github.stefanbirkner:system-rules:$systemRulesVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

repositories {
    mavenLocal()

    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/navikt/dusseldorf-ktor")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }

    mavenCentral()
    maven("https://jitpack.io")
}


java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}


tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("app")
    archiveClassifier.set("")
    manifest {
        attributes(
            mapOf(
                "Main-Class" to mainClass
            )
        )
    }
}

tasks.withType<Wrapper> {
    gradleVersion = "7.2"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
