import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes.ASM9
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

// 统一签名：signing/keystore.properties 里的密钥；没有该文件时退回 debug 签名
val repositoryRoot = layout.settingsDirectory.asFile
val releaseKeystoreProperties = Properties().apply {
    val file = File(repositoryRoot, "signing/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = releaseKeystoreProperties.getProperty("storeFile") != null

android {
    enableKotlin = false
    namespace = "io.github.vvb2060.packageinstaller"
    defaultConfig {
        versionCode = 14
        versionName = "2.0"
    }
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = File(repositoryRoot, releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
                // 让 AGP 按 minSdk 走默认（v2），再显式要求 v3
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        debug {
            // 可选：-PdevTest 时给 debug 包名加 .devtest，避免和已装的正式版签名冲突
            if (providers.gradleProperty("devTest").isPresent) {
                applicationIdSuffix = ".devtest"
            }
        }
        release {
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
            optimization {
                enable = true
                keepRules {
                    ignoreFromAllExternalDependencies = true
                    includeDefault = false
                }
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    androidResources {
        generateLocaleConfig = true
    }
    packaging {
        resources {
            excludes += "**"
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    compileOnly(projects.stub)
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.lifecycle:lifecycle-livedata:2.11.0")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
}

androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(
        ClassVisitorFactory::class.java, InstrumentationScope.PROJECT
    ) {}
}

abstract class ClassVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return ClassRemapper(nextClassVisitor, object : Remapper(ASM9) {
            override fun map(name: String): String {
                val index = name.indexOf('$')
                if (index != -1) {
                    return map(name.substring(0, index)) + name.substring(index)
                }
                if (name.endsWith("_rename")) {
                    return name.substring(0, name.length - 7)
                }
                return name
            }
        })
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.className.startsWith("io.github.vvb2060.packageinstaller.core.")
    }
}
