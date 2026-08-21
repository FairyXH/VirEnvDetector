plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.fairyxh.VirEnvDetector"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    val gitVersion = GitVersion.getVersion()

    defaultConfig {
        applicationId = "io.github.fairyxh.VirEnvDetector"
        minSdk = 26
        targetSdk = 36
        versionName = gitVersion[0]
        versionCode = gitVersion[1].toInt()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
