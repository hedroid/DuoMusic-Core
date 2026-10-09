plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.maxrave.netease"
        compileSdk = 37
        minSdk = 26
    }

    val xcfName = "neteaseKit"

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    jvm {
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(projects.ktorExt)
                // api:public API(uploadToCloudDisk 的 CloudUploadBodyFactory)暴露 ktor 的
                // ByteReadChannel,下游 data/repo 模块编译需要它在 classpath
                api(libs.ktor.client.core)
                implementation(libs.ktor.client.encoding)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.logging)

                implementation(libs.common)
                implementation(projects.common)
                implementation(libs.logging)
                implementation(libs.kotlinx.bigint)
                implementation(libs.kotlinx.datetime)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        iosMain {
            dependencies {
            }
        }
    }
}
