plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.lsplugin.jgit)
    alias(libs.plugins.lsplugin.publish)
    alias(libs.plugins.lsplugin.cmaker)
    `maven-publish`
    signing
}

val androidTargetSdkVersion: Int by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra
val androidBuildToolsVersion: String by rootProject.extra
val androidCompileSdkVersion: Int by rootProject.extra
val androidNdkVersion: String by rootProject.extra
val androidCmakeVersion: String by rootProject.extra

android {
    compileSdk = androidCompileSdkVersion
    ndkVersion = androidNdkVersion
    buildToolsVersion = androidBuildToolsVersion

    buildFeatures {
        buildConfig = false
        prefabPublishing = true
        androidResources = false
        prefab = true
    }

    packaging {
        jniLibs {
            excludes += "**.so"
        }
    }

    prefab {
        register("lsplant") {
            headers = "src/main/jni/include"
        }
    }

    defaultConfig {
        minSdk = androidMinSdkVersion
    }

    buildTypes {
        create("standalone") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = androidCmakeVersion
        }
    }
    namespace = "org.lsposed.lsplant"

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
        singleVariant("standalone") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

cmaker {
    default {
        val flags = arrayOf(
            "-Werror",
            "-Wno-gnu-string-literal-operator-template",
        )
        abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64")
        cppFlags += flags
        cFlags += flags
    }
    buildTypes {
        when (it.name) {
            "debug", "release" -> {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
            "standalone" -> {
                arguments += "-DANDROID_STL=none"
                arguments += "-DLSPLANT_STANDALONE=ON"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
        arguments += "-DDEBUG_SYMBOLS_PATH=${project.layout.buildDirectory.file("symbols/${it.name}").get().asFile.absolutePath}"
    }
}

dependencies {
    "standaloneCompileOnly"(libs.cxx)
}

val symbolsReleaseTask = tasks.register<Jar>("generateReleaseSymbolsJar") {
    from(project.layout.buildDirectory.file("symbols/release"))
    exclude("**/dex_builder")
    archiveClassifier = "symbols"
    archiveBaseName = "release"
}

val symbolsStandaloneTask = tasks.register<Jar>("generateStandaloneSymbolsJar") {
    from(project.layout.buildDirectory.file("symbols/standalone"))
    exclude("**/dex_builder")
    archiveClassifier = "symbols"
    archiveBaseName = "standalone"
}

val repo = jgit.repo(true)
val ver = repo?.latestTag?.removePrefix("v") ?: "0.0"
println("${rootProject.name} version: $ver")

publish {
    githubRepo = "LSPosed/LSPlant"
    publications {
        fun MavenPublication.setup() {
            group = "org.lsposed.lsplant"
            version = ver
            pom {
                name = "LSPlant"
                description = "A hook framework for Android Runtime (ART)"
                url = "https://github.com/LSPosed/LSPlant"
                licenses {
                    license {
                        name = "GNU Lesser General Public License v3.0"
                        url = "https://github.com/LSPosed/LSPlant/blob/master/LICENSE"
                    }
                }
                developers {
                    developer {
                        name = "Lsposed"
                        url = "https://lsposed.org"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/LSPosed/LSPlant.git"
                    url = "https://github.com/LSPosed/LSPlant"
                }
            }
        }
        register<MavenPublication>("lsplant") {
            artifactId = "lsplant"
            afterEvaluate {
                from(components.getByName("release"))
                artifact(symbolsReleaseTask)
            }
            setup()
        }
        register<MavenPublication>("lsplantStandalone") {
            artifactId = "lsplant-standalone"
            afterEvaluate {
                from(components.getByName("standalone"))
                artifact(symbolsStandaloneTask)
            }
            setup()
        }
    }
}

// ========== 新增：发布到本地 maven-repo 目录（通过 raw.githubusercontent.com 分发） ==========
val ghVersion = (findProperty("gh.version") as? String) ?: ver

publishing {
    repositories {
        maven {
            name = "LocalRepo"
            url = uri("${rootProject.projectDir}/maven-repo")
        }
    }
    publications {
        register<MavenPublication>("lsplantHotfix") {
            groupId = "com.buildbasesdk"
            artifactId = "sohotfix"
            version = ghVersion
            afterEvaluate {
                from(components.getByName("release"))
                artifact(symbolsReleaseTask)
            }
            pom {
                name = "so-hotfix"
                description = "A hook framework for Android Runtime (ART)"
                url = "https://github.com/buildbasesdk/so-hotfix"
                licenses {
                    license {
                        name = "GNU Lesser General Public License v3.0"
                        url = "https://github.com/LSPosed/LSPlant/blob/master/LICENSE"
                    }
                }
            }
        }
        register<MavenPublication>("lsplantStandaloneHotfix") {
            groupId = "com.buildbasesdk"
            artifactId = "sohotfix-standalone"
            version = ghVersion
            afterEvaluate {
                from(components.getByName("standalone"))
                artifact(symbolsStandaloneTask)
            }
            pom {
                name = "so-hotfix Standalone"
                description = "A hook framework for Android Runtime (ART) - Standalone"
                url = "https://github.com/buildbasesdk/so-hotfix"
                licenses {
                    license {
                        name = "GNU Lesser General Public License v3.0"
                        url = "https://github.com/LSPosed/LSPlant/blob/master/LICENSE"
                    }
                }
            }
        }
    }
}

// ===== 跳过 Javadoc（无 Java 代码） =====
tasks.matching { it.name.contains("javaDoc", ignoreCase = true) }.configureEach {
    enabled = false
}

// ===== 快捷发布 Task（一键发布 sohotfix AAR） =====
tasks.register("publishSohotfixAar") {
    group = "publishing"
    description = "发布 sohotfix AAR 到 maven-repo 目录"

    dependsOn("publishLsplantHotfixPublicationToLocalRepoRepository")

    doLast {
        println("✅ 发布完成: com.buildbasesdk:sohotfix:${ghVersion}")
        println("   输出目录: maven-repo/com/buildbasesdk/sohotfix/${ghVersion}/")
        println("   ⏭  下一步: git add maven-repo/ && git commit && git push hotfix master:so-hotfix")
    }
}
