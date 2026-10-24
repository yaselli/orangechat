plugins { alias(libs.plugins.android.library) }
android {
    namespace = "me.rerere.terminal"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies { testImplementation(libs.junit) }
