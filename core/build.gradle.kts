plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Android ships the XmlPull API (kXML) in the platform, so it is compile-only here;
    // the JVM unit tests get a real implementation from kxml2.
    compileOnly("net.sf.kxml:kxml2:2.3.0")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Optional: -PovExtra=/path/to/files renders every file in that folder as a smoke test.
    systemProperty("ov.extra", providers.gradleProperty("ovExtra").getOrElse(""))
    systemProperty("ov.out", layout.buildDirectory.dir("samples-out").get().asFile.absolutePath)
}
