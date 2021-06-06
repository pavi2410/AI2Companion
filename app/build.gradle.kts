plugins {
    id("com.android.application")
}

android {
    compileSdkVersion(30)
    buildToolsVersion("30.0.3")

    defaultConfig {
        minSdkVersion(21)
        targetSdkVersion(30)

        applicationId = "edu.mit.appinventor.aicompanion3"
        versionCode = 1
        versionName = "1.0"

        multiDexEnabled = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
//            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packagingOptions {
        exclude("META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation("androidx.appcompat:appcompat:1.0.0")

//    testImplementation "junit:junit:4.+"
//    androidTestImplementation "androidx.test.ext:junit:1.1.2"
//    androidTestImplementation "androidx.test.espresso:espresso-core:3.3.0"
}

android.applicationVariants.all {
    val outputKawaClasses = File(buildDir, "intermediates/kawa/$name/classes")
    val compileKawaTask = tasks.register<JavaExec>("compile${name.capitalize()}KawaSources") {
        val javaCompile = javaCompileProvider.get()
        dependsOn(javaCompile)
        classpath(configurations["androidApis"])
        classpath(javaCompile.classpath)
        classpath(javaCompile.outputs.files)
        inputs.files(
                sourceSets.flatMap { it.javaDirectories }.map {
                    fileTree(it) {
                        include("**/*.scm")
                        include("**/*.yail")
                    }
                }
        ).skipWhenEmpty()
        outputs.dir(outputKawaClasses)
        mainClass.set("kawa.repl")

        doFirst {
            args("-f", inputs.sourceFiles.filter { "runtime" in it.name }.first(),
                    "-d", outputKawaClasses,
                    "-P", "$applicationId.",
                    "-C", *inputs.sourceFiles.map { it.path }.toTypedArray())
        }
        doFirst { outputKawaClasses.deleteRecursively() }
    }
    registerPostJavacGeneratedBytecode(files(outputKawaClasses).builtBy(compileKawaTask))
}
