//
// Copyright (c) 2008-2020 the Urho3D project.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
//

import org.gradle.internal.io.NullOutputStream
import org.gradle.internal.os.OperatingSystem

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("android.extensions")
    `maven-publish`
}

android {
    ndkVersion = ndkSideBySideVersion
    compileSdkVersion(30)
    defaultConfig {
        minSdkVersion(18)
        targetSdkVersion(30)
        versionCode = 1
        versionName = project.version.toString()
        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments.apply {
                    System.getenv("ANDROID_CCACHE")?.let { add("-D ANDROID_CCACHE=$it") }
                    add("-D GRADLE_BUILD_DIR=$buildDir")
                    // In order to get clean module segregation, always exclude player/samples from AAR
                    val excludes = listOf("URHO3D_PLAYER", "URHO3D_SAMPLES")
                    addAll(excludes.map { "-D $it=0" })
                    // Pass along matching Gradle properties (higher precedence) or env-vars as CMake build options
                    val vars = project.file("../../script/.build-options")
                        .readLines()
                        .filterNot { excludes.contains(it) }
                    addAll(vars
                        .filter { project.hasProperty(it) }
                        .map { "-D $it=${project.property(it)}" }
                    )
                    addAll(vars
                        .filterNot { project.hasProperty(it) }
                        .map { variable -> System.getenv(variable)?.let { "-D $variable=$it" } ?: "" }
                    )
                }
                targets.add("Urho3D")
            }
        }
        splits {
            abi {
                isEnable = project.hasProperty("ANDROID_ABI")
                reset()
                include(
                    *(project.findProperty("ANDROID_ABI") as String? ?: "")
                        .split(',')
                        .toTypedArray()
                )
            }
        }
    }
    buildTypes {
        named("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }
    externalNativeBuild {
        cmake {
            version = cmakeVersion
            path = project.file("../../CMakeLists.txt")

            // Make it explicit as one of the task needs to know the exact path and derived from it
            setBuildStagingDirectory(".cxx")
        }
    }
    sourceSets {
        getByName("main") {
            java.srcDir("../../Source/ThirdParty/SDL/android-project/app/src/main/java")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(kotlin("stdlib-jdk8", embeddedKotlinVersion))
    implementation("com.getkeepsafe.relinker:relinker:1.4.1")
    testImplementation("junit:junit:4.13")
    androidTestImplementation("androidx.test:runner:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.3.0")
}

afterEvaluate {
    // Part of the our external native build tree resided in Gradle buildDir
    // When the buildDir is cleaned then we need a way to re-configure that part back
    // It is achieved by ensuring that CMake configuration phase is rerun
    tasks {
        "clean" {
            doLast {
                android.externalNativeBuild.cmake.path?.touch()
            }
        }
    }

    // This is a hack - workaround Android plugin for Gradle not providing way to bundle extra "stuffs"
    android.buildTypes.forEach { buildType ->
        val config = buildType.name.capitalize()
        tasks {
            register<Zip>("zipBuildTree$config") {
                archiveClassifier.set(buildType.name)
                archiveExtension.set("aar")
                dependsOn("zipBuildTreeConfigurer$config", "bundle${config}Aar")
                from(zipTree(getByName("bundle${config}Aar").outputs.files.first()))
            }
            register<Task>("zipBuildTreeConfigurer$config") {
                val externalNativeBuildDir = File(buildDir, "tree/$config")
                doLast {
                    val zipTask = getByName<Zip>("zipBuildTree$config")
                    externalNativeBuildDir.list()?.forEach { abi ->
                        listOf("include", "lib").forEach {
                            zipTask.from(File(externalNativeBuildDir, "$abi/$it")) {
                                into("tree/$config/$abi/$it")
                            }
                        }
                    }
                }
            }
        }
    }
}

tasks {
    register<Jar>("sourcesJar") {
        archiveClassifier.set("sources")
        from(android.sourceSets.getByName("main").java.srcDirs)
    }
    register<Exec>("makeDoc") {
        // Ignore the exit status on Windows host system because Doxygen may not return exit status correctly on Windows
        isIgnoreExitValue = OperatingSystem.current().isWindows
        standardOutput = NullOutputStream.INSTANCE
        args("--build", ".", "--target", "doc")
        dependsOn("makeDocConfigurer")
        mustRunAfter("zipBuildTreeDebug")
    }
    register<Zip>("documentationZip") {
        archiveClassifier.set("documentation")
        dependsOn("makeDoc")
    }
    register<Task>("makeDocConfigurer") {
        doLast {
            val docABI = File(buildDir, "tree/Debug").list()?.first()
            val buildTree = File(android.externalNativeBuild.cmake.buildStagingDirectory, "cmake/debug/$docABI")
            named<Exec>("makeDoc") {
                // This is a hack - expect the first line to contain the path to the CMake executable
                executable = File(buildTree, "build_command.txt").readLines().first().split(":").last().trim()
                workingDir = buildTree
            }
            named<Zip>("documentationZip") {
                from(File(buildTree, "Docs/html")) {
                    into("docs")
                }
            }
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("Urho") {
            groupId = project.group.toString()
            artifactId = "${project.name}-${project.libraryType.toLowerCase()}"
            if (project.hasProperty("ANDROID_ABI")) {
                artifactId = "$artifactId-${(project.property("ANDROID_ABI") as String).replace(',', '-')}"
            }
            afterEvaluate {
                android.buildTypes.forEach {
                    artifact(tasks["zipBuildTree${it.name.capitalize()}"])
                }
            }
            artifact(tasks["sourcesJar"])
            artifact(tasks["documentationZip"])
            pom {
                inceptionYear.set("2008")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/urho3d/Urho3D/blob/master/LICENSE")
                    }
                }
                developers {
                    developer {
                        name.set("Urho3D contributors")
                        url.set("https://github.com/urho3d/Urho3D/graphs/contributors")
                    }
                }
                scm {
                    url.set("https://github.com/urho3d/Urho3D.git")
                    connection.set("scm:git:ssh://git@github.com:urho3d/Urho3D.git")
                    developerConnection.set("scm:git:ssh://git@github.com:urho3d/Urho3D.git")
                }
                withXml {
                    asNode().apply {
                        appendNode("name", "Urho3D")
                        appendNode("description", project.description)
                        appendNode("url", "https://urho3d.github.io/")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/urho3d/Urho3D")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
