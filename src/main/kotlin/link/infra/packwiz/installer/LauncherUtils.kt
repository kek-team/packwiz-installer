package link.infra.packwiz.installer

import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import link.infra.packwiz.installer.metadata.PackFile
import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.util.Log
import kotlin.io.path.reader
import kotlin.io.path.writeText

class LauncherUtils internal constructor(private val opts: UpdateManager.Options, val ui: IUserInterface) {
	enum class LauncherStatus {
		SUCCESSFUL,
		NO_CHANGES,
		CANCELLED,
		NOT_FOUND, // When there is no mmc-pack.json file found (i.e. MultiMC is not being used)
	}

	fun handleMultiMC(pf: PackFile, gson: Gson): LauncherStatus {
		// MultiMC MC and loader version checker
		val manifestPath = opts.multimcFolder / "mmc-pack.json"

		if (!manifestPath.nioPath.toFile().exists()) {
			return LauncherStatus.NOT_FOUND
		}

		val multimcManifest = manifestPath.nioPath.reader().use {
			try {
				JsonParser.parseReader(it)
			} catch (e: JsonIOException) {
				throw Exception("Cannot read the MultiMC pack file", e)
			} catch (e: JsonSyntaxException) {
				throw Exception("Invalid MultiMC pack file", e)
			}.asJsonObject
		}

		Log.info("Loaded MultiMC config")

		// We only support format 1, if it gets updated in the future we'll have to handle that
		// There's only version 1 for now tho, so that's good
		if (multimcManifest["formatVersion"]?.asInt != 1) {
			throw Exception("Unsupported MultiMC format version ${multimcManifest["formatVersion"]}")
		}

		var manifestModified = false
		val modLoaders = hashMapOf(
			"net.minecraft" to "minecraft",
			"net.minecraftforge" to "forge",
			"net.neoforged" to "neoforge",
			"net.fabricmc.fabric-loader" to "fabric",
			"org.quiltmc.quilt-loader" to "quilt",
			"com.mumfrey.liteloader" to "liteloader"
		)
		// MultiMC requires components to be sorted; this is defined in the MultiMC meta repo, but they seem to
		// be the same for every version so they are just used directly here
		val componentOrders = mapOf(
			"net.minecraft" to -2,
			"org.lwjgl" to -1,
			"org.lwjgl3" to -1,
			"net.minecraftforge" to 5,
			"net.neoforged" to 5,
			"net.fabricmc.fabric-loader" to 10,
			"org.quiltmc.quilt-loader" to 10,
			"com.mumfrey.liteloader" to 10,
			"net.fabricmc.intermediary" to 11
		)
		val modLoadersClasses = modLoaders.entries.associate{(k,v)-> v to k}
		val loaderVersionsFound = HashMap<String, String?>()
		val outdatedLoaders = mutableSetOf<String>()
		val components = multimcManifest["components"]?.asJsonArray ?: throw Exception("Invalid mmc-pack.json: no components key")
		components.removeAll {
			val component = it.asJsonObject

			val version = component["version"]?.asString
			// If we find any of the modloaders we support, we save it and check the version
			if (modLoaders.containsKey(component["uid"]?.asString)) {
				val modLoader = modLoaders.getValue(component["uid"]!!.asString)
				loaderVersionsFound[modLoader] = version
				if (version != pf.versions[modLoader]) {
					outdatedLoaders.add(modLoader)
					true // Delete component; cached metadata is invalid and will be re-added
				} else {
					false // Already up to date; cached metadata is valid
				}
			} else { false } // Not a known loader / MC
		}

		for ((_, loader) in modLoaders
			.filter {
				(!loaderVersionsFound.containsKey(it.value) || outdatedLoaders.contains(it.value)) && pf.versions.containsKey(it.value)
			}
		) {
			manifestModified = true
			components.add(gson.toJsonTree(
				hashMapOf("uid" to modLoadersClasses[loader], "version" to pf.versions[loader]))
			)
		}

		// If inconsistent Intermediary mappings version is found, delete it - MultiMC will add and re-dl the correct one
		components.find { it.isJsonObject && it.asJsonObject["uid"]?.asString == "net.fabricmc.intermediary" }?.let {
			if (it.asJsonObject["version"]?.asString != pf.versions["minecraft"]) {
				components.remove(it)
				manifestModified = true
			}
		}

		if (manifestModified) {
			// Sort manifest by component order
			val sortedComponents = components.sortedWith(nullsLast(compareBy {
				if (it.isJsonObject) {
					componentOrders[it.asJsonObject["uid"]?.asString]
				} else { null }
			}))
			components.removeAll { true }
			sortedComponents.forEach { components.add(it) }

			// The manifest has been modified, so before saving it we'll ask the user
			// if they wanna update it, continue without updating it, or exit
			val oldVers = loaderVersionsFound.map { Pair(it.key, it.value) }
			val newVers = pf.versions.map { Pair(it.key, it.value) }

			when (ui.showUpdateConfirmationDialog(oldVers, newVers)) {
				IUserInterface.UpdateConfirmationResult.CANCELLED -> {
					return LauncherStatus.CANCELLED
				}
				IUserInterface.UpdateConfirmationResult.CONTINUE -> {
					return LauncherStatus.SUCCESSFUL
				}
				else -> {}
			}

			// manifestPath.nioPath.writeText(gson.toJson(multimcManifest))
			// Log.info("Successfully updated mmc-pack.json based on version metadata")

			// --- NOUVEAU : écrire via un process isolé après arrêt de Prism ---
			val jsonStr = gson.toJson(multimcManifest)

			// 1) On stocke le JSON à écrire dans un fichier temporaire
			val tmp = java.nio.file.Files.createTempFile("mmc-pack-update-", ".json")
			java.nio.file.Files.write(tmp, jsonStr.toByteArray(java.nio.charset.StandardCharsets.UTF_8))

			// 2) On lance un process Java qui attend que le fichier soit "stable" puis le remplace
			spawnIsolatedMultiMCUpdater(
				manifestPath.nioPath.toString(),
				tmp.toAbsolutePath().toString()
			)

			Log.info("Spawned isolated updater for mmc-pack.json; exiting with code 100 to let Prism stop cleanly")
			// 3) On stoppe le process courant (Prism annulera le lancement)
			kotlin.system.exitProcess(100)

			// (inatteignable)
			// return LauncherStatus.SUCCESSFUL
		}

		return LauncherStatus.NO_CHANGES
	}

	private fun spawnIsolatedMultiMCUpdater(mmcPackPath: String, payloadPath: String) {
		val javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java"
		// Chemin du JAR courant (shadow + R8), fonctionne aussi en dev
		val jarPath = java.io.File(LauncherUtils::class.java.protectionDomain.codeSource.location.toURI()).absolutePath

		// On lance le main dédié sans bloquer
		val pb = ProcessBuilder(
			javaBin, "-cp", jarPath,
			"link.infra.packwiz.installer.IsolatedMultiMCUpdater",
			mmcPackPath, payloadPath
		)
		pb.inheritIO() // logs visibles si besoin (optionnel)
		pb.start()
	}

}
