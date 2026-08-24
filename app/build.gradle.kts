import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Base applicationId / namespace; variants derive their own ids from it. */
val baseApplicationId = "de.davidgrieser.container"

/** Dimension holding one flavour per entry of app-variants.yaml. */
val variantDimension = "app"

/**
 * The apps to build out of this container — name, symbol, kiosk config and the
 * behaviour switches — all declared in app-variants.yaml.
 */
val appVariants = AppVariants.load(rootProject.file("app-variants.yaml"), baseApplicationId)

android {
    namespace = baseApplicationId
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += variantDimension

    productFlavors {
        appVariants.forEach { variant ->
            create(variant.id) {
                dimension = variantDimension
                applicationId = variant.applicationId
                isDefault = variant.isDefault
                variant.versionNameSuffix?.let { versionNameSuffix = it }

                // The launcher label. Deliberately not in res/values/strings.xml:
                // every build gets it from its variant.
                resValue("string", "app_name", variant.name)

                buildConfigField("String", "VARIANT_ID", javaStringLiteral(variant.id))
                // Default location of the remotely-controlled kiosk configuration.
                // Can be overridden at runtime from the admin menu. Empty for
                // variants that only ever show their DEFAULT_KIOSK_PATH.
                buildConfigField("String", "DEFAULT_CONFIG_URL", javaStringLiteral(variant.configUrl))
                buildConfigField(
                    "String",
                    "DEFAULT_KIOSK_PATH",
                    javaStringLiteral(variant.defaultKioskPath)
                )
                buildConfigField("boolean", "REQUIRE_PIN", variant.requirePin.toString())
                buildConfigField("boolean", "SHOW_MENU", variant.showMenu.toString())
                // Which system bars this build starts out showing; the admin menu
                // can change it per device.
                buildConfigField("String", "SCREEN_MODE", javaStringLiteral(variant.screenMode))
                // What the bars a screen mode keeps are painted in, per system
                // theme. Fixed per build; the activity picks the one in force.
                buildConfigField(
                    "String",
                    "BAR_COLOR_LIGHT",
                    javaStringLiteral(variant.barColor.light)
                )
                buildConfigField(
                    "String",
                    "BAR_COLOR_DARK",
                    javaStringLiteral(variant.barColor.dark)
                )
                // Initial value of the admin switch; still togglable at runtime.
                buildConfigField(
                    "boolean",
                    "ALLOW_UNVERIFIED_SSL",
                    variant.allowUnverifiedSsl.toString()
                )
                // Whether an off-domain link is handed to the device's default
                // handler instead of being refused. Fixed per build.
                buildConfigField(
                    "boolean",
                    "ALLOW_EXTERNAL_NAVIGATION",
                    variant.allowExternalNavigation.toString()
                )
                // Whether the page may ask for the device's position. Fixed per
                // build, because the location permissions are only in the
                // manifest of a variant that asked for them (see below).
                buildConfigField("boolean", "ALLOW_LOCATION", variant.allowLocation.toString())
            }
        }
    }

    signingConfigs {
        // The release signing config is wired up to read from a keystore.properties
        // file that is intentionally NOT committed. Drop your keystore + a
        // keystore.properties file (see keystore.properties.example) into the
        // project root and release builds will be signed automatically.
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only apply the release signing config when a keystore is present,
            // otherwise the build would fail for anyone without the signing files.
            if (rootProject.file("keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

/**
 * Generates the per-variant pieces of each build: the launcher icon (background
 * colour + symbol) into its own resource directory, so the APKs are told apart
 * on the home screen without any icon assets being checked in, and the manifest
 * entries only some variants get, so an APK declares no permission it cannot
 * use.
 */
androidComponents {
    val variantsById = appVariants.associateBy { it.id }
    onVariants { variant ->
        val flavor = variant.productFlavors
            .firstOrNull { (dimension, _) -> dimension == variantDimension }
            ?.second
        val spec = variantsById[flavor] ?: return@onVariants

        val generateIcons = tasks.register<GenerateLauncherIconsTask>(
            "generate${variant.name.replaceFirstChar(Char::uppercaseChar)}LauncherIcons"
        ) {
            description = "Generates the launcher icon for the ${spec.name} variant."
            background.set(spec.icon.background)
            tint.set(spec.icon.tint)
            spec.icon.glyph?.let { glyphPathData.set(LauncherGlyphs.pathData(it)) }
            spec.icon.vector?.let { vectorFile.set(rootProject.file(it)) }
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            generateIcons,
            GenerateLauncherIconsTask::outputDir
        )

        val generateManifest = tasks.register<GenerateVariantManifestTask>(
            "generate${variant.name.replaceFirstChar(Char::uppercaseChar)}VariantManifest"
        ) {
            description = "Writes the manifest additions for the ${spec.name} variant."
            // Location is the only such entry so far: only a variant that opted
            // in declares the permissions, so the others cannot even ask.
            val location = spec.allowLocation
            permissions.set(
                if (location) GenerateVariantManifestTask.LOCATION_PERMISSIONS else emptyList()
            )
            optionalFeatures.set(
                if (location) GenerateVariantManifestTask.LOCATION_FEATURES else emptyList()
            )
            // manifestFile is wired below: the Android build picks where a
            // generated manifest lives and sets the property itself.
        }
        variant.sources.manifests.addGeneratedManifestFile(
            generateManifest,
            GenerateVariantManifestTask::manifestFile
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
