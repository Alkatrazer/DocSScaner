import org.gradle.api.tasks.Copy
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

val modelVersion = "v1.2.0"
val modelFileName = "fairscan-segmentation-model.tflite"
val modelUrl = "https://github.com/pynicolas/fairscan-segmentation-model/releases/download/$modelVersion/$modelFileName"
val fallbackApkUrl = "https://f-droid.org/repo/org.fairscan.app_90.apk"
val modelSha256 = "96E14D7E610DD0C27B768B228FBC553B4EC119EBE68F3A3594029A25400691D2"

// Keep the model outside build/ so `clean` does not force another network download.
val downloadedModelPath = rootProject.layout.projectDirectory.file(".gradle/models/$modelFileName")
val generatedAssetsDir = layout.buildDirectory.dir("generated/assets")

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02X".format(it) }
}

val downloadTFLiteModel = tasks.register("downloadTFLiteModel") {
    val outputFile = downloadedModelPath.asFile
    outputs.file(outputFile)

    doLast {
        outputFile.parentFile.mkdirs()
        if (outputFile.exists() && outputFile.sha256() != modelSha256) {
            println("Cached model checksum is invalid; downloading it again")
            outputFile.delete()
        }

        if (!outputFile.exists()) {
            println("Downloading $modelFileName from $modelUrl")
            val temporaryModel: File = File(outputFile.parentFile, "$modelFileName.part")
            val temporaryApk: File = File(outputFile.parentFile, "fairscan-fdroid.apk.part")
            temporaryModel.delete()
            temporaryApk.delete()

            try {
                try {
                    URL(modelUrl).openStream().use { input ->
                        temporaryModel.outputStream().use(input::copyTo)
                    }
                } catch (githubError: Exception) {
                    println("GitHub download failed; using the verified F-Droid APK fallback")
                    URL(fallbackApkUrl).openStream().use { input ->
                        temporaryApk.outputStream().use(input::copyTo)
                    }
                    ZipFile(temporaryApk.absolutePath).use { apk: ZipFile ->
                        val entry = apk.getEntry("assets/$modelFileName")
                            ?: throw GradleException("The F-Droid APK does not contain $modelFileName")
                        apk.getInputStream(entry).use { input ->
                            temporaryModel.outputStream().use(input::copyTo)
                        }
                    }
                }

                val actualSha256 = temporaryModel.sha256()
                if (actualSha256 != modelSha256) {
                    throw GradleException(
                        "Invalid $modelFileName checksum: expected $modelSha256, got $actualSha256"
                    )
                }
                temporaryModel.copyTo(outputFile, overwrite = true)
            } finally {
                temporaryModel.delete()
                temporaryApk.delete()
            }
        } else {
            println("Verified cached model: ${outputFile.absolutePath}")
        }
    }
}

val copyTFLiteToAssets = tasks.register<Copy>("copyTFLiteToAssets") {
    dependsOn(downloadTFLiteModel)
    from(downloadedModelPath)
    into(generatedAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(copyTFLiteToAssets)
}
