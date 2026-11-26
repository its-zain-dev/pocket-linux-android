plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pocketlinux"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketlinux"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("x86_64") {
            dimension = "abi"
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += listOf("gz", "tgz")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// AGP's MergedAssetWriter strips '.gz' and decompresses '.gz' files in assets/.
// Restore the raw pre-compressed rootfs-<abi>.tar.gz verbatim after mergeAssets.
val flavorAbiMap = mapOf(
    "Arm64" to ("arm64" to "arm64-v8a"),
    "X86_64" to ("x86_64" to "x86_64")
)

tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("Assets") && !it.name.contains("Test")
}.configureEach {
    val taskName = name
    val match = flavorAbiMap.entries.firstOrNull { taskName.contains(it.key) }
    if (match != null) {
        val (flavorDir, abi) = match.value
        val srcGz = layout.projectDirectory.file("src/$flavorDir/assets/rootfs-$abi.tar.gz").asFile
        inputs.file(srcGz).optional()
        doLast {
            if (srcGz.exists()) {
                val outDir = outputs.files.singleFile
                val strippedTar = File(outDir, "rootfs-$abi.tar")
                if (strippedTar.exists()) {
                    strippedTar.delete()
                }
                srcGz.copyTo(File(outDir, "rootfs-$abi.tar.gz"), overwrite = true)
            }
        }
    }
}
