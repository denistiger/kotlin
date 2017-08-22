
import java.io.File
import proguard.gradle.ProGuardTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

description = "Kotlin Compiler"

buildscript {
    repositories {
        jcenter()
    }

    dependencies {
        classpath("com.github.jengelman.gradle.plugins:shadow:1.2.3")
        classpath("net.sf.proguard:proguard-gradle:5.3.1")
    }
}

plugins {
    `java-base`
}

// Set to false to disable proguard run on kotlin-compiler.jar. Speeds up the build
val shrink = true
val compilerManifestClassPath =
        "kotlin-runtime.jar kotlin-reflect.jar kotlin-script-runtime.jar"

val fatJarContents by configurations.creating
val fatSourcesJarContents by configurations.creating
val proguardLibraryJars by configurations.creating
val fatJar by configurations.creating
val compilerJar by configurations.creating
val archives by configurations

val compilerBaseName: String by rootProject.extra

val outputJar = File(buildDir, "libs", "$compilerBaseName.jar")

val javaHome = System.getProperty("java.home")

val compilerModules: Array<String> by rootProject.extra

val packagesToRelocate =
        listOf("com.intellij",
                "com.google",
                "com.sampullara",
                "org.apache",
                "org.jdom",
                "org.picocontainer",
                "jline",
                "gnu",
                "javax.inject",
                "org.fusesource")

val ideaCoreSdkJars: Array<String> by rootProject.extra
val coreSdkJarsSimple = ideaCoreSdkJars.filterNot { it == "jdom" || it == "log4j" }.toTypedArray()

fun firstFromJavaHomeThatExists(vararg paths: String): File =
        paths.mapNotNull { File(javaHome, it).takeIf { it.exists() } }.firstOrNull()
                ?: throw GradleException("Cannot find under '$javaHome' neither of: ${paths.joinToString()}")

compilerModules.forEach { evaluationDependsOn(it) }

val compiledModulesSources = compilerModules.map {
    project(it).the<JavaPluginConvention>().sourceSets.getByName("main").allSource
}

dependencies {
    compilerModules.forEach {
        fatJarContents(project(it)) { isTransitive = false }
    }
    compiledModulesSources.forEach {
        fatSourcesJarContents(it)
    }

    fatJarContents(project(":core:builtins", configuration = "builtins"))
    fatJarContents(ideaSdkCoreDeps(*coreSdkJarsSimple))
    fatJarContents(ideaSdkDeps("jna-platform"))
    fatJarContents(commonDep("javax.inject"))
    fatJarContents(commonDep("org.jline", "jline"))
    fatJarContents(protobufFull())
    fatJarContents(commonDep("com.github.spullara.cli-parser", "cli-parser"))
    fatJarContents(commonDep("com.google.code.findbugs", "jsr305"))
    fatJarContents(commonDep("io.javaslang", "javaslang"))
    fatJarContents(preloadedDeps("json-org"))

    proguardLibraryJars(files(firstFromJavaHomeThatExists("lib/rt.jar", "../Classes/classes.jar"),
            firstFromJavaHomeThatExists("lib/jsse.jar", "../Classes/jsse.jar"),
            firstFromJavaHomeThatExists("../lib/tools.jar", "../Classes/tools.jar")))
    proguardLibraryJars(project(":kotlin-stdlib", configuration = "mainJar"))
    proguardLibraryJars(project(":kotlin-script-runtime", configuration = "mainJar"))
    proguardLibraryJars(project(":kotlin-reflect", configuration = "mainJar"))
    proguardLibraryJars(preloadedDeps("kotlinx-coroutines-core"))
}

val packCompiler by task<ShadowJar> {
    configurations = listOf(fatJar)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    destinationDir = File(buildDir, "libs")
//    baseName = compilerBaseName
    dependsOn(protobufFullTask)

    setupPublicJar("before-proguard", "")
    from(fatJarContents)
    ideaSdkDeps("jps-model.jar", subdir = "jps").forEach { from(it) { exclude("META-INF/services/**") } }
    ideaSdkDeps("oromatcher").forEach { from(it) { exclude("META-INF/jb/** META-INF/LICENSE") } }
    ideaSdkCoreDeps("jdom", "log4j").forEach { from(it) { exclude("META-INF/jb/** META-INF/LICENSE") } }

    manifest.attributes.put("Class-Path", compilerManifestClassPath)
    manifest.attributes.put("Main-Class", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
}

val proguard by task<ProGuardTask> {
    dependsOn(packCompiler)
    configuration("$rootDir/compiler/compiler.pro")

    val outputJar = File(buildDir, "libs", "$compilerBaseName-after-proguard.jar")

    inputs.files(packCompiler.outputs.files.singleFile)
    outputs.file(outputJar)

    // TODO: remove after dropping compatibility with ant build
    doFirst {
        System.setProperty("kotlin-compiler-jar-before-shrink", packCompiler.outputs.files.singleFile.canonicalPath)
        System.setProperty("kotlin-compiler-jar", outputJar.canonicalPath)
    }

    libraryjars(proguardLibraryJars)
    printconfiguration("$buildDir/compiler.pro.dump")
}

dist {
    if (shrink) {
        from(proguard)
    } else {
        from(packCompiler)
    }
    rename(".*", compilerBaseName + ".jar")
}

runtimeJarArtifactBy(proguard, proguard.outputs.files.singleFile) {
    name = compilerBaseName
    classifier = ""
}
sourcesJar {
    from(fatSourcesJarContents)
}
javadocJar()

publish()
