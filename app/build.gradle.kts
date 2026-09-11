plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // KSP 替代 kapt(Room 官方支持):构建更快、无 kapt stub 阶段。版本前半段必须与 Kotlin
    // 版本严格匹配(1.9.25)。
    id("com.google.devtools.ksp") version "1.9.25-1.0.20"
}

android {
    namespace = "com.einsli.photoroulette"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.einsli.photoroulette"
        minSdk = 36
        targetSdk = 36
        versionCode = 16
        versionName = "1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // MigrationTestHelper 从 androidTest assets 里读导出的 schema(app/schemas 已作为 assets
    // 根,路径 = 数据库类全名/版本号.json),没有这个 sourceSet 迁移测试找不到 6.json/7.json。
    sourceSets {
        getByName("androidTest") { assets.srcDir("$projectDir/schemas") }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Room schema 导出:每次版本变更生成 app/schemas/<数据库类全名>/<version>.json,随迁移测试
// 一起进 git(exportSchema=true 见 PhotoDatabase)。这是防「漏写迁移静默清库」的地基。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // 迁移测试(见 androidTest/.../MigrationTest.kt):room-testing 提供 MigrationTestHelper,
    // ext:junit + runner 提供 AndroidJUnit4 运行器。
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
