package link.infra.packwiz.installer

import link.infra.packwiz.installer.metadata.IndexFile
import link.infra.packwiz.installer.metadata.ManifestFile
import link.infra.packwiz.installer.metadata.hash.Hash
import link.infra.packwiz.installer.metadata.hash.HashFormat
import link.infra.packwiz.installer.request.RequestException
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.OS
import link.infra.packwiz.installer.target.Side
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.IOptionDetails
import link.infra.packwiz.installer.util.Log
import link.infra.packwiz.installer.util.OSGetter
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class DownloadTask private constructor(val metadata: IndexFile.File, val index: IndexFile, private val downloadSide: Side) : IOptionDetails {
	var cachedFile: ManifestFile.File? = null
		private set

	private var err: Exception? = null
	val exceptionDetails get() = err?.let { e -> ExceptionDetails(name, e) }

	fun failed() = err != null

	var alreadyUpToDate = false
		private set
	private var metadataRequired = true
	private var invalidated = false
	// If file is new or isOptional changed to true, the option needs to be presented again
	private var newOptional = true
	var completionStatus = CompletionStatus.INCOMPLETE
		private set

	enum class CompletionStatus {
		INCOMPLETE,
		DOWNLOADED,
        DOWNLOADED_IGNORED_OS_FILTER,
		ALREADY_EXISTS_CACHED,
		ALREADY_EXISTS_VALIDATED,
		SKIPPED_DISABLED,
		SKIPPED_WRONG_SIDE,
        SKIPPED_WRONG_OS,
		DELETED_DISABLED,
		DELETED_WRONG_SIDE,
        DELETED_WRONG_OS;
	}

	val isOptional get() = metadata.linkedFile?.option?.optional ?: false

    val currentModFileSide get() = metadata.linkedFile?.side

	fun isNewOptional() = isOptional && newOptional

	fun correctSide() = currentModFileSide?.let { downloadSide.hasSide(it) } ?: true

    fun shouldSkipForOS(): Boolean {
        if (OSGetter.current == OS.UNKNOWN) {
            Log.warn("Couldn't get OS, ignoring OS filtering")
            return false
        }
        val excluded = metadata.linkedFile?.download?.disabledClientPlatforms ?: return false
        return excluded.contains(OSGetter.current)
    }

    override val name get() = metadata.name

	// Ensure that an update is done if it changes from false to true, or from true to false
	override var optionValue: Boolean
		get() = cachedFile?.optionValue ?: true
		set(value) {
			if (value && !optionValue) { // Ensure that an update is done if it changes from false to true, or from true to false
				alreadyUpToDate = false
			}
			cachedFile?.optionValue = value
		}

	override val optionDescription get() = metadata.linkedFile?.option?.description ?: ""

	fun invalidate() {
		invalidated = true
		alreadyUpToDate = false
	}

	fun updateFromCache(cachedFile: ManifestFile.File?) {
		if (err != null) return

		if (cachedFile == null) {
			this.cachedFile = ManifestFile.File()
			return
		}
		this.cachedFile = cachedFile
		if (!invalidated) {
			val currHash = try {
				metadata.getHashObj(index)
			} catch (e: Exception) {
				err = e
				return
			}
			if (currHash == cachedFile.hash) { // Already up to date
				alreadyUpToDate = true
				if (!metadata.metafile) {
					metadataRequired = false
				}
				completionStatus = CompletionStatus.ALREADY_EXISTS_CACHED
			}
		}
		if (cachedFile.isOptional) {
			// Because option selection dialog might set this task to true/false, metadata is always needed to download
			// the file, and to show the description and name
			metadataRequired = true
		}
	}

	fun downloadMetadata(clientHolder: ClientHolder) {
		if (err != null) return

		if (metadataRequired) {
			try {
				// Retrieve the linked metadata file
				metadata.downloadMeta(index, clientHolder)
			} catch (e: Exception) {
				err = e
				return
			}
			cachedFile?.let { cachedFile ->
				val linkedFile = metadata.linkedFile
				if (linkedFile != null) {
					if (linkedFile.option.optional) {
						if (cachedFile.isOptional) {
							// isOptional didn't change
							newOptional = false
						} else {
							// isOptional false -> true, set option to it's default value
							// TODO: preserve previous option value, somehow??
							cachedFile.optionValue = linkedFile.option.defaultValue
						}
					}
				}
				cachedFile.isOptional = isOptional
				cachedFile.onlyOtherSide = !correctSide()
			}
		}
	}

	/**
	 * Check if the file in the destination location is already valid
	 * Must be done after metadata retrieval
	 */
	fun validateExistingFile(packFolder: PackwizFilePath, clientHolder: ClientHolder) {
		if (!alreadyUpToDate) {
			try {
				// TODO: only do this for files that didn't exist before or have been modified since last full update?
				val destPath = metadata.destURI.rebase(packFolder)
				destPath.source(clientHolder).use { src ->
					// TODO: clean up duplicated code
					val hash: Hash<*>
					val fileHashFormat: HashFormat<*>
					val linkedFile = metadata.linkedFile

					if (linkedFile != null) {
						hash = linkedFile.hash
						fileHashFormat = linkedFile.download.hashFormat
					} else {
						hash = metadata.getHashObj(index)
						fileHashFormat = metadata.hashFormat(index)
					}

					val fileSource = fileHashFormat.source(src)
					fileSource.buffer().readAll(blackholeSink())
					if (hash == fileSource.hash) {
						alreadyUpToDate = true
						completionStatus = CompletionStatus.ALREADY_EXISTS_VALIDATED

						// Update the manifest file
						cachedFile = (cachedFile ?: ManifestFile.File()).also {
							try {
								it.hash = metadata.getHashObj(index)
							} catch (e: Exception) {
								err = e
								return
							}
							it.isOptional = isOptional
							it.cachedLocation = metadata.destURI.rebase(packFolder)
							metadata.linkedFile?.let { linked ->
								try {
									it.linkedFileHash = linked.hash
								} catch (e: Exception) {
									err = e
								}
							}
						}
					}
				}
			} catch (e: RequestException) {
				// Ignore exceptions; if the file doesn't exist we'll be downloading it
			} catch (e: IOException) {
				// Ignore exceptions; if the file doesn't exist we'll be downloading it
			}
		}
	}

    sealed class DownloadDecision(val status: CompletionStatus?) {
        class Proceed(status: CompletionStatus? = null) : DownloadDecision(status)
        class Skip(status: CompletionStatus) : DownloadDecision(status)
    }


    fun shouldDownload(): DownloadDecision {
        val wrongSide = !correctSide()
        val wrongOS = shouldSkipForOS()
        val disabled = cachedFile?.let { it.isOptional && !it.optionValue } ?: false

        // --- SERVER: ignore OS filter, but still respect side/disabled ---
        if (downloadSide.hasSide(Side.SERVER)) {
            if (wrongSide) return deleteAndSkip(CompletionStatus.DELETED_WRONG_SIDE, CompletionStatus.SKIPPED_WRONG_SIDE)
            if (disabled) return deleteAndSkip(CompletionStatus.DELETED_DISABLED, CompletionStatus.SKIPPED_DISABLED)

            if (wrongOS) {
                return DownloadDecision.Proceed(CompletionStatus.DOWNLOADED_IGNORED_OS_FILTER)
            }

            return DownloadDecision.Proceed()
        }

        // --- CLIENT: standard filtering rules ---
        if (OSGetter.current == OS.UNKNOWN) {
            Log.warn("Couldn't determine OS, ignoring OS filtering for ${metadata.name}")
            return DownloadDecision.Proceed()
        }

        if (wrongOS) return deleteAndSkip(CompletionStatus.DELETED_WRONG_OS, CompletionStatus.SKIPPED_WRONG_OS)
        if (wrongSide) return deleteAndSkip(CompletionStatus.DELETED_WRONG_SIDE, CompletionStatus.SKIPPED_WRONG_SIDE)
        if (disabled) return deleteAndSkip(CompletionStatus.DELETED_DISABLED, CompletionStatus.SKIPPED_DISABLED)

        return DownloadDecision.Proceed()
    }

    private fun deleteAndSkip(deletedStatus: CompletionStatus, skippedStatus: CompletionStatus): DownloadDecision {
        var deleted = false
        cachedFile?.cachedLocation?.let { loc ->
            try {
                if (Files.deleteIfExists(loc.nioPath)) {
                    Log.info("Deleted cached file for ${metadata.name} (${deletedStatus.name.lowercase()})")
                    deleted = true
                }
            } catch (e: IOException) {
                Log.warn("Failed to delete cached file (${loc})", e)
            }
            cachedFile?.cachedLocation = null
        }
        return DownloadDecision.Skip(if (deleted) deletedStatus else skippedStatus)
    }



    fun download(packFolder: PackwizFilePath, clientHolder: ClientHolder) {
        if (err != null) return

        // Decide if we should download or skip
        when (val decision = shouldDownload()) {
            is DownloadDecision.Skip -> {
                completionStatus = decision.status!!
                return
            }
            is DownloadDecision.Proceed -> {
                decision.status?.let { completionStatus = it }
            }
        }

        if (alreadyUpToDate) return

        val destPath = metadata.destURI.rebase(packFolder)

        // Skip if preserve=true and file already exists
        if (metadata.preserve && destPath.nioPath.toFile().exists()) return

        try {
            var (hash, hashFormat) = metadata.linkedFile?.let { linked ->
                linked.hash to linked.download.hashFormat
            } ?: (metadata.getHashObj(index) to metadata.hashFormat(index))

            val src = metadata.getSource(clientHolder)
            val fileSource = hashFormat.source(src)
            val data = Buffer()

            fileSource.buffer().use { it.readAll(data) }

            if (hash == fileSource.hash) {
                try {
                    Files.createDirectories(destPath.parent.nioPath)
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    if (!Files.isDirectory(destPath.parent.nioPath)) throw e
                }

                Files.copy(data.inputStream(), destPath.nioPath, StandardCopyOption.REPLACE_EXISTING)
                data.clear()
            } else {
                Log.warn("Invalid hash for ${metadata.destURI}")
                Log.warn("Calculated: ${fileSource.hash}")
                Log.warn("Expected:   $hash")

                val sha256 = HashingSink.sha256(blackholeSink())
                data.readAll(sha256)
                Log.warn("SHA256 hash value: ${sha256.hash}")

                err = Exception("Hash invalid for ${metadata.name}")
                data.clear()
                return
            }

            cachedFile?.cachedLocation?.let { old ->
                if (destPath != old) {
                    try {
                        Files.deleteIfExists(old.nioPath)
                    } catch (e: IOException) {
                        Log.warn("Failed to delete old cached file (${old.nioPath})", e)
                    }
                }
            }

            cachedFile = (cachedFile ?: ManifestFile.File()).apply {
                try {
                    hash = metadata.getHashObj(index)
                } catch (e: Exception) {
                    err = e
                    return
                }
                isOptional = isOptional
                cachedLocation = destPath
                metadata.linkedFile?.let { linked ->
                    try {
                        linkedFileHash = linked.hash
                    } catch (e: Exception) {
                        err = e
                    }
                }
            }
            if (completionStatus != CompletionStatus.DOWNLOADED_IGNORED_OS_FILTER) {
                // don't overwrite CompletionStatus.DOWNLOADED_IGNORED_OS_FILTER
                completionStatus = CompletionStatus.DOWNLOADED
            }

        } catch (e: Exception) {
            err = e
        }
    }



    companion object {
		fun createTasksFromIndex(index: IndexFile, downloadSide: Side): MutableList<DownloadTask> {
			val tasks = ArrayList<DownloadTask>()
			for (file in index.files) {
				tasks.add(DownloadTask(file, index, downloadSide))
			}
			return tasks
		}
	}
}