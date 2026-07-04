import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

buildscript {
    dependencies {
        // Used by the patchStubJar task below to rewrite the compile-only stub jar.
        classpath("org.ow2.asm:asm:9.9")
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("momoi.plugin.apkmixin") apply true
}

// Some stub classes in libs/source.jar are constructor-local anonymous classes carrying BOTH an
// InnerClasses (member) attribute and an EnclosingMethod (local) attribute — a combination D8/R8 rejects
// ("a member class cannot also be a non-member local class"), which blocks @Mixin-subclassing them (e.g.
// hooking QQNTC2CWatchActivity$mServiceConnection$1.onServiceConnected). The stub is compile-only and
// never ships in the APK, so we compile against a patched copy with EnclosingMethod stripped — pure member
// classes that dex cleanly, with no runtime effect (real classes come from the patched target APK).
val patchedStubJar = layout.buildDirectory.file("patched-libs/source.jar")
val patchStubJar = tasks.register("patchStubJar") {
    val srcJar = file("libs/source.jar")
    inputs.file(srcJar)
    outputs.file(patchedStubJar)
    doLast {
        val out = patchedStubJar.get().asFile
        out.parentFile.mkdirs()
        ZipFile(srcJar).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val bytes = zip.getInputStream(e).readBytes()
                    val outBytes = if (e.name.endsWith(".class")) {
                        val cr = ClassReader(bytes)
                        val cw = ClassWriter(cr, 0)
                        cr.accept(object : ClassVisitor(Opcodes.ASM9, cw) {
                            // Drop the EnclosingMethod attribute (keep InnerClasses) → pure member class.
                            override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) {}
                        }, 0)
                        cw.toByteArray()
                    } else {
                        bytes
                    }
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write(outBytes)
                    zos.closeEntry()
                }
            }
        }
    }
}

android {
    namespace = "momoi.mod.qqpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tencent.qqlite"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Baked-in build timestamp (local time of the machine that built the APK), shown in the
        // About page and crash/hang reports. Recomputed every build (input changes → not cached).
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":ApkMixin-annotation"))
    compileOnly(libs.androidx.appcompat)
    // Compile against the EnclosingMethod-stripped stub jar (see patchStubJar above) instead of the raw
    // libs/source.jar, so constructor-local anonymous stub classes can be @Mixin-subclassed.
    compileOnly(files(patchStubJar))
    compileOnly(libs.androidx.fragment)
    compileOnly(libs.androidx.constraintlayout)
    compileOnly(libs.androidx.recyclerview)
    compileOnly(libs.androidx.viewpager2)
    compileOnly(libs.androidx.core)
    compileOnly(libs.androidx.navigation.fragment)
}

apkMixin {
    versionName = "M2.5"
    targetApk = "source.apk"
    useProcessorCountAsThreadCount = project.properties["useProcessorCountAsThreadCount"] == "true"

    signing {
        keyFile = file("mixin/testkey.pk8")
        certFile = file("mixin/testkey.x509.pem")
    }

    output {
        signedFileName = "QQMax_${versionName}.apk"
    }
}