import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/*
 * Copyright 2025 Karma Krafts & associates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    `maven-publish`
}

kotlin {
    mingwX64()
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    targets.filterIsInstance<KotlinNativeTarget>().forEach { target ->
        target.apply {
            binaries {
                executable {
                    entryPoint = "$group.main"
                }
            }
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        nativeMain {
            dependencies {
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.clikt)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.curl)
                implementation(libs.ktor.client.contentNegotiation)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.contentNegotiation)
                implementation(libs.ktor.server.statusPages)
                implementation(libs.ktor.serialization.kotlinxJson)
                implementation(libs.skroll)
                implementation(libs.skroll.ktor)
                implementation(libs.annotations)
            }
        }
    }
}

publishing {
    repositories {
        with(CI) { authenticatedPackageRegistry() }
    }
    publications.configureEach {
        if (this is MavenPublication) {
            pom {
                name = project.name
                description = "Repository aggregation daemon for GitLab."
                url = System.getenv("CI_PROJECT_URL")
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "kitsunealex"
                        name = "KitsuneAlex"
                        url = "https://git.karmakrafts.dev/KitsuneAlex"
                    }
                }
                scm {
                    url = this@pom.url
                }
            }
        }
    }
}