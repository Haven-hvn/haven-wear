import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Public configuration only. No key, seed or token ever goes here: BuildConfig ships in the APK.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String, default: String = ""): String = "\"${localProps.getProperty(key, default)}\""

android {
    namespace = "haven.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "haven.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "HAVEN_AOL_CANISTER_ID", prop("haven.aol.canisterId"))
        buildConfigField("String", "HAVEN_AOL_IC_HOST", prop("haven.aol.icHost", "https://ic0.app"))
        buildConfigField("String", "ARKIV_ENDPOINT_URL", prop("arkiv.endpointUrl"))
        buildConfigField("String", "RPC_ETHEREUM", prop("evm.rpc.ethereum"))
        buildConfigField("String", "RPC_BASE", prop("evm.rpc.base"))
        buildConfigField("String", "RPC_ARBITRUM", prop("evm.rpc.arbitrum"))
        buildConfigField("String", "RPC_OPTIMISM", prop("evm.rpc.optimism"))
        buildConfigField("String", "RPC_SEPOLIA", prop("evm.rpc.sepolia"))

        // Watches are ARM. x86_64 stays for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
    }
}

// Builds libhaven_vetkeys.so into src/main/jniLibs before packaging; skips cleanly without an NDK
// (sealed unlock then reports the missing library instead of working).
val buildVetKeys by tasks.registering(Exec::class) {
    commandLine("bash", rootProject.file("tools/build-vetkeys-android.sh").absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(buildVetKeys) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.compose.ui.tooling)

    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.horologist.tiles)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.horologist.media.ui.material3)
    implementation(libs.horologist.media.ui.model)
    implementation(libs.horologist.audio.ui)
    implementation(libs.horologist.audio.ui.material3)
    implementation(libs.horologist.compose.layout)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    implementation(libs.okhttp)
    implementation(libs.timber)

    implementation(libs.ic.agent)
    implementation(libs.ic.kotlin)
    implementation(libs.foc.cache)

    // BIP-39/32 and EIP-712 hashing + secp256k1 signing. The KZG (blob tx) native library and
    // tuweni are only reached by EIP-4844 code this app never calls, so they stay off the watch.
    implementation(libs.web3j.crypto) {
        exclude(group = "io.consensys.protocols", module = "jc-kzg-4844")
        exclude(group = "io.consensys.tuweni")
    }
    implementation(libs.zxing.core)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json on the JVM test classpath: android.jar stubs throw RuntimeException("Stub!").
    testImplementation(libs.org.json)
}
