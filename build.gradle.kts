import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.2.1")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByType<CloudstreamExtension>().configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.getByType<LibraryExtension>().configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/HatsuneMikuUwU/cloudstream-extensions-uwu")
        authors = listOf("Miku")
    }

    android {
        namespace = "com.miku"
        compileSdk = 35

        defaultConfig {
            minSdk = 21
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_18
            targetCompatibility = JavaVersion.VERSION_18
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_18)

                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")
        
        add("implementation", kotlin("stdlib"))
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.18")
        add("implementation", "com.squareup.okhttp3:okhttp:5.4.0")
        add("implementation", "org.jsoup:jsoup:1.22.2")
        
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
        add("implementation", "com.fasterxml.jackson.core:jackson-databind:2.22.1")
        add("implementation", "com.google.code.gson:gson:2.14.0")
        
        add("implementation", "com.faendir.rhino:rhino-android:1.6.0")
        add("implementation", "app.cash.quickjs:quickjs-android:0.9.2")
        
        add("implementation", "me.xdrop:fuzzywuzzy:1.4.0")
        add("implementation", "androidx.core:core-ktx:1.19.0")
    }

    tasks.matching { it.name == "compileDex" || it.name == "make" || it.name == "makeDebug" || it.name == "makeRelease" }.configureEach {
        enabled = false
    }

    tasks.register("buildManualCs3") {
        dependsOn("compileReleaseKotlin") 

        doLast {
            val projectName = project.name
            val buildDir = layout.buildDirectory.get().asFile
            
            val kotlinCompileTask = tasks.getByName("compileReleaseKotlin") as KotlinJvmCompile
            val classesDir = kotlinCompileTask.destinationDirectory.get().asFile
            
            if (!classesDir.exists() || classesDir.listFiles()?.isEmpty() == true) {
                throw GradleException("Class files not found! Ensure Kotlin compilation succeeded.")
            }

            val outDexFolder = File(buildDir, "custom_dex").apply { mkdirs() }
            
            val androidExt = project.extensions.getByType<com.android.build.api.dsl.LibraryExtension>()
            val sdkDir = androidExt.sdkComponents.sdkDirectory.get().asFile
            val buildToolsDir = File(sdkDir, "build-tools").listFiles()?.maxByOrNull { it.name }
            val d8Jar = File(buildToolsDir, "lib/d8.jar")

            if (!d8Jar.exists()) {
                throw GradleException("d8.jar file not found at ${d8Jar.absolutePath}. Ensure SDK Build-Tools are installed.")
            }
            
            println("-> Running D8 Compiler manually for $projectName...")
            project.exec {
                commandLine(
                    "java",
                    "-cp", d8Jar.absolutePath,
                    "com.android.tools.r8.D8",
                    "--release",
                    "--min-api", "21",
                    "--output", outDexFolder.absolutePath,
                    classesDir.absolutePath
                )
            }

            val dexFile = File(outDexFolder, "classes.dex")
            if (!dexFile.exists()) {
                throw GradleException("Failed to build classes.dex!")
            }

            println("-> Generating manifest.json...")
            val manifestFile = File(outDexFolder, "manifest.json")
            manifestFile.writeText("""
                {
                  "name": "$projectName",
                  "pluginClassName": "com.miku.$projectName",
                  "authors": ["Miku"],
                  "version": 1
                }
            """.trimIndent())

            println("-> Packaging into .cs3 file...")
            val cs3File = File(buildDir, "outputs/$projectName.cs3")
            cs3File.parentFile.mkdirs()

            java.util.zip.ZipOutputStream(cs3File.outputStream()).use { zos ->
                arrayOf(dexFile, manifestFile).forEach { file ->
                    zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                    file.inputStream().copyTo(zos)
                    zos.closeEntry()
                }
            }
            
            println("Extension built successfully at: \n${cs3File.absolutePath}")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
