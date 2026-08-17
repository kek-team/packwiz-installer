package link.infra.packwiz.installer

import link.infra.packwiz.installer.util.Log
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.EventQueue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.UIManager

/**
 * Isolated process: waits for Prism to finish touching mmc-pack.json,
 * replaces the file with the given JSON (payload), then (Windows only)
 * tries to relaunch the instance automatically. If the Prism executable
 * isn't found, shows an "Update complete" popup instead.
 *
 * Args:
 *   0 -> path to mmc-pack.json
 *   1 -> path to the temp file containing the JSON to write
 */
object IsolatedMultiMCUpdater {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("IsolatedMultiMCUpdater: missing args <mmcPackPath> <payloadPath>")
            return
        }

        val mmcPack: Path = Paths.get(args[0])
        val payload: Path = Paths.get(args[1])

        // Look up the Prism binary in parallel while we wait (Windows only)
        val prismExeFuture: CompletableFuture<String?> = startPrismExeLookupAsync()

        // Loading window (JOptionPane-like) shown while stabilizing
        val loading = showLoadingPane(
            title = "Update in progress",
            message = "Updating mod loader... please wait"
        )

        // Wait for stability (~5s) to let Prism finish
        try {
            waitFileStable(mmcPack, stableMillis = 5000, timeoutMillis = 30000)
        } catch (e: Exception) {
            Log.warn("mmc-pack.json did not stabilize in time; attempting write anyway", e)
        }

        // Atomic write: copy -> move (no .bak)
        try {
            val tmp: Path = if (mmcPack.parent != null)
                Files.createTempFile(mmcPack.parent, "mmc-pack-new-", ".json")
            else
                Files.createTempFile("mmc-pack-new-", ".json")

            Files.copy(payload, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, mmcPack, StandardCopyOption.REPLACE_EXISTING)

            try { Files.deleteIfExists(payload) } catch (_: Exception) { /* best effort */ }

            Log.info("Isolated update complete: ${mmcPack.toAbsolutePath()}")
        } catch (e: Exception) {
            // Close the loading window if it's open
            try { EventQueue.invokeAndWait { loading?.dispose() } } catch (_: Exception) {}
            // Error popup (consistent style)
            safeShowError("Failed to update mod loader: $e")
            kotlin.system.exitProcess(1)
        }

        // Close the loading window
        try { EventQueue.invokeAndWait { loading?.dispose() } } catch (_: Exception) {}

        // Try to relaunch on Windows, reusing the already-resolved path if ready
        val preResolvedExe = prismExeFuture.getNow(null) // doesn't block if not ready yet
        val relaunched = tryRelaunchWindowsWithExe(preResolvedExe)

        if (!relaunched) {
            // Couldn't relaunch (or OS != Windows) -> inform the user
            safeShowInfo(
                text = "Mod loader update complete.\nPlease relaunch the instance.",
                title = "Update complete"
            )
        }

        // Clean exit
        kotlin.system.exitProcess(0)
    }

    /**
     * Waits until the file stops changing (stable size/mtime) for `stableMillis`,
     * with an overall `timeoutMillis` deadline.
     */
    private fun waitFileStable(path: Path, stableMillis: Long, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastSize: Long? = null
        var lastMtime: Long? = null
        var stableSince = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            try {
                val attrs: BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                val size = attrs.size()
                val mtime = attrs.lastModifiedTime().toMillis()

                if (size == lastSize && mtime == lastMtime) {
                    if (System.currentTimeMillis() - stableSince >= stableMillis) return
                } else {
                    lastSize = size
                    lastMtime = mtime
                    stableSince = System.currentTimeMillis()
                }
            } catch (_: Exception) {
                // File missing/inaccessible -> restart the wait cycle
                stableSince = System.currentTimeMillis()
                lastSize = null
                lastMtime = null
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) { /* ignore */ }
        }
        // Timeout exceeded -> continue anyway
    }

    /**
     * Creates a loading window based on JOptionPane (same look & feel as other dialogs).
     * Non-modal, indeterminate, "always on top", and not closable.
     */
    private fun showLoadingPane(title: String, message: String): JDialog? {
        return try {
            var dialog: JDialog? = null
            EventQueue.invokeAndWait {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}

                val panel = JPanel(BorderLayout(12, 12)).apply {
                    add(JLabel(message), BorderLayout.NORTH)
                    val bar = JProgressBar().apply { isIndeterminate = true }
                    add(bar, BorderLayout.CENTER)
                }

                val pane = JOptionPane(
                    panel,
                    JOptionPane.INFORMATION_MESSAGE,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    emptyArray(), // no buttons
                    null
                )

                dialog = pane.createDialog(null, title).apply {
                    modalityType = Dialog.ModalityType.MODELESS
                    isAlwaysOnTop = true
                    isResizable = false
                    defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
                    isVisible = true
                }
            }
            dialog
        } catch (_: Exception) {
            null // headless or other -> no UI
        }
    }

    private fun safeShowInfo(text: String, title: String) {
        try {
            EventQueue.invokeAndWait {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
                JOptionPane.showMessageDialog(null, text, title, JOptionPane.INFORMATION_MESSAGE)
            }
        } catch (_: Exception) { /* headless -> ignore */ }
    }

    private fun safeShowError(text: String) {
        try {
            EventQueue.invokeAndWait {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
                JOptionPane.showMessageDialog(null, text, "Error", JOptionPane.ERROR_MESSAGE)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    // ---------- Relaunch & Prism detection (Windows) ----------

    // Kicks off the .exe lookup in parallel (Windows). Otherwise, an already-completed future of null.
    private fun startPrismExeLookupAsync(): CompletableFuture<String?> {
        val os = System.getProperty("os.name")?.lowercase() ?: return CompletableFuture.completedFuture(null)
        if (!os.contains("win")) return CompletableFuture.completedFuture(null)
        return CompletableFuture.supplyAsync {
            findPrismExeWindows()
        }
    }

    // Tries to relaunch using an already-resolved path if available; otherwise falls back to PATH.
    private fun tryRelaunchWindowsWithExe(preResolvedExe: String?): Boolean {
        val os = System.getProperty("os.name")?.lowercase() ?: return false
        if (!os.contains("win")) return false

        val instId = System.getenv("INST_ID") ?: return false
        val instDir = System.getenv("INST_DIR")
        val dataRoot: String? = try {
            if (instDir != null) {
                val p = Paths.get(instDir)
                p.parent?.parent?.toString()
            } else null
        } catch (_: Exception) { null }

        val exe = preResolvedExe ?: "PrismLauncher.exe"

        val cmd = mutableListOf(exe)
        if (dataRoot != null) cmd += listOf("--dir", dataRoot)
        cmd += listOf("--launch", instId)

        return try {
            val pb = ProcessBuilder(cmd).inheritIO()
            // If exe is an absolute path, set working dir to the .exe's folder (needed by some installs)
            if (preResolvedExe != null) {
                val parent = try { Paths.get(preResolvedExe).parent } catch (_: Exception) { null }
                if (parent != null) pb.directory(parent.toFile())
            }
            pb.start()
            true
        } catch (e: Exception) {
            Log.warn("Failed to relaunch Prism instance", e)
            false
        }
    }

    /**
     * Looks up the Prism executable on Windows:
     * 1) override via -Dpackwiz.prism.exe
     * 2) Windows Registry (InstallLocation / DisplayIcon for "Prism Launcher")
     * 3) Standard locations (%ProgramFiles%, %ProgramFiles(x86)%, %LocalAppData%)
     * Returns the .exe path if found and it exists, otherwise null.
     */
    private fun findPrismExeWindows(): String? {
        // 1) explicit override
        val override = System.getProperty("packwiz.prism.exe")
        if (!override.isNullOrBlank() && Files.exists(Paths.get(override))) return override

        // 2) Registry (HKLM/HKCU + WOW6432Node)
        val regPaths = arrayOf(
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        )
        for (root in regPaths) {
            val exeFromReg = findPrismExeFromRegistry(root)
            if (exeFromReg != null) return exeFromReg
        }

        // 3) Standard locations
        val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val localApp = System.getenv("LocalAppData") ?: "${System.getProperty("user.home")}\\AppData\\Local"

        val candidates = arrayOf(
            "$pf\\PrismLauncher\\PrismLauncher.exe",
            "$pf\\Prism Launcher\\PrismLauncher.exe",
            "$pf86\\PrismLauncher\\PrismLauncher.exe",
            "$pf86\\Prism Launcher\\PrismLauncher.exe",
            "$localApp\\Programs\\PrismLauncher\\PrismLauncher.exe"
        )

        for (c in candidates) {
            val p = Paths.get(c)
            if (Files.exists(p)) return p.toString()
        }

        // Last resort: if it's on PATH, ProcessBuilder will find it
        return "PrismLauncher.exe"
    }

    /**
     * Walks the registry under 'regRoot' to find a key with DisplayName "Prism Launcher",
     * then reads InstallLocation / DisplayIcon to derive PrismLauncher.exe.
     */
    private fun findPrismExeFromRegistry(regRoot: String): String? {
        try {
            val proc = ProcessBuilder("reg", "query", regRoot, "/s", "/v", "DisplayName")
                .redirectErrorStream(true)
                .start()
            BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { br ->
                var line: String?
                val keys = ArrayList<String>()
                while (br.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    // Each block looks like:
                    // HKEY_...\{GUID}\n    DisplayName    REG_SZ    Prism Launcher
                    if (l.startsWith("HKEY_")) {
                        keys.add(l)
                    } else if (l.contains("DisplayName") && l.contains("Prism Launcher")) {
                        // Matching preceding key
                        val key = keys.lastOrNull() ?: continue
                        // Try InstallLocation first
                        val install = queryRegValue(key, "InstallLocation")
                        if (!install.isNullOrBlank()) {
                            val exe = Paths.get(install, "PrismLauncher.exe")
                            if (Files.exists(exe)) return exe.toString()
                        }
                        // Otherwise DisplayIcon
                        val icon = queryRegValue(key, "DisplayIcon")
                        if (!icon.isNullOrBlank()) {
                            // DisplayIcon may contain "C:\...\PrismLauncher.exe,0"
                            val cleaned = icon.trim().trim('"').split(",")[0]
                            val exe = Paths.get(cleaned)
                            if (Files.exists(exe)) return exe.toString()
                            // If it's a folder, try PrismLauncher.exe inside it
                            if (Files.isDirectory(exe)) {
                                val guess = exe.resolve("PrismLauncher.exe")
                                if (Files.exists(guess)) return guess.toString()
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // no registry access or no permissions: ignore
        }
        return null
    }

    /** Reads a value from the Windows registry for the given key and name. */
    private fun queryRegValue(key: String, valueName: String): String? {
        return try {
            val p = ProcessBuilder("reg", "query", key, "/v", valueName)
                .redirectErrorStream(true)
                .start()
            val out = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) out.append(line).append('\n')
            }
            val text = out.toString()
            // Expected format: <valueName>    REG_SZ    <value>
            val idx = text.indexOf(valueName)
            if (idx >= 0) {
                val tail = text.substring(idx + valueName.length)
                val parts = tail.split(Regex("\\s{2,}")) // multi-space separators
                if (parts.size >= 3) {
                    return parts[2].trim()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
