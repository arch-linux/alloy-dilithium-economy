plugins {
    java
}

group = "com.dilithium"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("libs/alloy-api.jar"))
    compileOnly(files("libs/alloy-loader.jar"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.76")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("DilithiumEconomy")
    archiveVersion.set("1.0.0")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    }

    manifest {
        attributes("Implementation-Title" to "DilithiumEconomy")
        attributes("Implementation-Version" to "1.0.0")
    }
}
