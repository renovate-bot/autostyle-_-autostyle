import com.github.vlsi.gradle.properties.dsl.props
import java.util.Locale
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("com.github.autostyle")
    id("com.gradle.plugin-publish") apply false
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.ide")
    id("com.github.vlsi.stage-vote-release")
    kotlin("jvm") apply false
}

val String.v: String get() = rootProject.extra["$this.version"] as String

val buildVersion = "autostyle".v + releaseParams.snapshotSuffix

println("Building Autostyle $buildVersion")

val enableGradleMetadata by props()
val autostyleSelf by props()
val skipAutostyle by props()
val skipJavadoc by props()

releaseParams {
    tlp.set("Autostyle")
    organizationName.set("autostyle")
    componentName.set("Autostyle")
    prefixForProperties.set("gh")
    svnDistEnabled.set(false)
    sitePreviewEnabled.set(false)
    nexus {
        mavenCentral()
    }
    voteText.set {
        """
        ${it.componentName} v${it.version}-rc${it.rc} is ready for preview.

        Git SHA: ${it.gitSha}
        Staging repository: ${it.nexusRepositoryUri}
        """.trimIndent()
    }
}

allprojects {
    group = "com.github.autostyle"
    version = buildVersion

    val javaUsed = file("src/main/java").isDirectory || file("src/test/java").isDirectory
    val kotlinUsed = file("src/main/kotlin").isDirectory || file("src/test/kotlin").isDirectory
    if (javaUsed) {
        apply(plugin = "java-library")
        apply(plugin = "mdoclet")
        dependencies {
            val compileOnly by configurations
            compileOnly("com.github.spotbugs:spotbugs-annotations:4.8.4")
            compileOnly("com.google.code.findbugs:jsr305:3.0.2")
        }
    }
    if (kotlinUsed) {
        apply(plugin = "java-library")
        apply(plugin = "org.jetbrains.kotlin.jvm")
        dependencies {
            val implementation by configurations
            implementation(kotlin("stdlib"))
        }
    }
    if (javaUsed || kotlinUsed) {
        dependencies {
            val implementation by configurations
            implementation(platform(project(":autostyle-bom")))
        }
    }

    val hasTests = file("src/test/java").isDirectory || file("src/test/kotlin").isDirectory
    if (hasTests) {
        // Add default tests dependencies
        dependencies {
            val testImplementation by configurations
            val testRuntimeOnly by configurations
            testImplementation(platform(project(":autostyle-bom-testing")))
            testImplementation("org.junit.jupiter:junit-jupiter-api")
            testImplementation("org.junit.jupiter:junit-jupiter-params")
            testImplementation("org.hamcrest:hamcrest")
            testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirMode = "775".toInt(8)
        fileMode = "664".toInt(8)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            apiVersion = "1.4"
            jvmTarget = "1.8"
            freeCompilerArgs += "-Xjvm-default=all"
            freeCompilerArgs += "-Xjdk-release=1.8"
        }
    }

    if (!skipAutostyle) {
        if (!autostyleSelf) {
            apply(plugin = "com.github.autostyle")
        }
        // Autostyle is already published, so we can always use it, except it is broken :)
        // So there's an option to disable it
        apply(from = "$rootDir/autostyle.gradle.kts")
    }

    plugins.withType<JavaPlugin> {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
            withSourcesJar()
            if (!skipJavadoc) {
                withJavadocJar()
            }
        }

        repositories {
            mavenCentral()
        }

        apply(plugin = "maven-publish")

        if (!enableGradleMetadata) {
            tasks.withType<GenerateModuleMetadata> {
                enabled = false
            }
        }

        tasks {
            withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
                options.release.set(8)
            }

            withType<Jar>().configureEach {
                manifest {
                    attributes["Bundle-License"] = "Apache-2.0"
                    attributes["Implementation-Title"] = "Autostyle"
                    attributes["Implementation-Version"] = project.version
                    attributes["Specification-Vendor"] = "Autostyle"
                    attributes["Specification-Version"] = project.version
                    attributes["Specification-Title"] = "Autostyle"
                    attributes["Implementation-Vendor"] = "Autostyle"
                    attributes["Implementation-Vendor-Id"] = "com.github.vlsi.autostyle"
                }
            }
            withType<Test>().configureEach {
                useJUnitPlatform()
                testLogging {
                    exceptionFormat = TestExceptionFormat.FULL
                    showStandardStreams = true
                }
                // Pass the property to tests
                fun passProperty(name: String, default: String? = null) {
                    val value = System.getProperty(name) ?: default
                    value?.let { systemProperty(name, it) }
                }
                passProperty("junit.jupiter.execution.parallel.enabled", "true")
                passProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
                passProperty("junit.jupiter.execution.timeout.default", "5 m")
            }
            configure<PublishingExtension> {
                if (project.path == ":") {
                    // Do not publish "root" project. Java plugin is applied here for DSL purposes only
                    return@configure
                }
                publications {
                    if (project.path != ":autostyle-plugin-gradle") {
                        create<MavenPublication>(project.name) {
                            artifactId = project.name
                            version = rootProject.version.toString()
                            description = project.description
                            from(project.components.get("java"))
                        }
                    }
                    withType<MavenPublication> {
                        // if (!skipJavadoc) {
                        // Eager task creation is required due to
                        // https://github.com/gradle/gradle/issues/6246
                        //  artifact(sourcesJar.get())
                        //  artifact(javadocJar.get())
                        // }

                        // Use the resolved versions in pom.xml
                        // Gradle might have different resolution rules, so we set the versions
                        // that were used in Gradle build/test.
                        versionMapping {
                            usage(Usage.JAVA_RUNTIME) {
                                fromResolutionResult()
                            }
                            usage(Usage.JAVA_API) {
                                fromResolutionOf("runtimeClasspath")
                            }
                        }
                        pom {
                            withXml {
                                val sb = asString()
                                var s = sb.toString()
                                // <scope>compile</scope> is Maven default, so delete it
                                s = s.replace("<scope>compile</scope>", "")
                                // Cut <dependencyManagement> because all dependencies have the resolved versions
                                s = s.replace(
                                    Regex(
                                        "<dependencyManagement>.*?</dependencyManagement>",
                                        RegexOption.DOT_MATCHES_ALL
                                    ),
                                    ""
                                )
                                sb.setLength(0)
                                sb.append(s)
                                // Re-format the XML
                                asNode()
                            }
                            name.set(
                                (project.findProperty("artifact.name") as? String)
                                    ?: "Autostyle ${project.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
                            )
                            description.set(
                                project.description
                                    ?: "Autostyle ${project.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
                            )
                            developers {
                                developer {
                                    id.set("vlsi")
                                    name.set("Vladimir Sitnikov")
                                    email.set("sitnikov.vladimir@gmail.com")
                                }
                            }
                            inceptionYear.set("2019")
                            url.set("https://github.com/autostyle/autostyle")
                            licenses {
                                license {
                                    name.set("The Apache License, Version 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                    comments.set("A business-friendly OSS license")
                                    distribution.set("repo")
                                }
                            }
                            issueManagement {
                                system.set("GitHub")
                                url.set("https://github.com/autostyle/autostyle/issues")
                            }
                            scm {
                                connection.set("scm:git:https://github.com/autostyle/autostyle.git")
                                developerConnection.set("scm:git:https://github.com/autostyle/autostyle.git")
                                url.set("https://github.com/autostyle/autostyle")
                                tag.set("HEAD")
                            }
                        }
                    }
                }
            }
        }
    }
}
