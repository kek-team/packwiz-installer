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
 * Processus isolé : attend que Prism ait fini de toucher mmc-pack.json,
 * remplace le fichier par le JSON fourni (payload), puis (Windows uniquement)
 * tente de relancer l'instance automatiquement. Si l'exécutable Prism
 * n'est pas trouvé, on affiche une popup "Mise à jour terminée".
 *
 * Args:
 *   0 -> chemin vers mmc-pack.json
 *   1 -> chemin vers le fichier temporaire contenant le JSON à écrire
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

        // Recherche du binaire Prism en parallèle pendant l'attente (Windows uniquement)
        val prismExeFuture: CompletableFuture<String?> = startPrismExeLookupAsync()

        // Fenêtre d’attente (JOptionPane-like) pendant la stabilisation
        val loading = showLoadingPane(
            title = "Mise à jour en cours",
            message = "Mise à jour du modloader... veuillez patienter"
        )

        // Attente de stabilité (~5 s) pour laisser Prism finir
        try {
            waitFileStable(mmcPack, stableMillis = 5000, timeoutMillis = 30000)
        } catch (e: Exception) {
            Log.warn("mmc-pack.json did not stabilize in time; attempting write anyway", e)
        }

        // Écriture atomique : copie -> move (pas de .bak)
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
            // Fermer le "loading" s'il est ouvert
            try { EventQueue.invokeAndWait { loading?.dispose() } } catch (_: Exception) {}
            // Popup d'erreur (cohérente)
            safeShowError("Échec de la mise à jour du modloader : $e")
            kotlin.system.exitProcess(1)
        }

        // Fermer la fenêtre d’attente
        try { EventQueue.invokeAndWait { loading?.dispose() } } catch (_: Exception) {}

        // Tente la relance Windows en réutilisant le chemin déjà trouvé (si prêt)
        val preResolvedExe = prismExeFuture.getNow(null) // ne bloque pas si pas encore prêt
        val relaunched = tryRelaunchWindowsWithExe(preResolvedExe)

        if (!relaunched) {
            // On n'a pas pu relancer (ou OS ≠ Windows) -> informer l'utilisateur
            safeShowInfo(
                text = "Mise à jour du modloader terminée.\nVeuillez relancer l'instance.",
                title = "Update terminée"
            )
        }

        // Fin propre
        kotlin.system.exitProcess(0)
    }

    /**
     * Attend que le fichier cesse de changer (taille/mtime stables) pendant `stableMillis`,
     * avec un timeout global `timeoutMillis`.
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
                // Fichier absent/inaccessible → on repart pour un cycle d'attente
                stableSince = System.currentTimeMillis()
                lastSize = null
                lastMtime = null
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) { /* ignore */ }
        }
        // Timeout dépassé -> on continue malgré tout
    }

    /**
     * Crée une fenêtre d'attente basée sur JOptionPane (same look & feel que les autres boîtes).
     * Non-modale, indéterminée, "always on top", et non fermable.
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
                    emptyArray(), // pas de boutons
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
            null // headless ou autre -> pas d'UI
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
                JOptionPane.showMessageDialog(null, text, "Erreur", JOptionPane.ERROR_MESSAGE)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    // ---------- Relance & détection Prism (Windows) ----------

    // Lance la recherche du .exe en parallèle (Windows). Sinon, future immédiat à null.
    private fun startPrismExeLookupAsync(): CompletableFuture<String?> {
        val os = System.getProperty("os.name")?.lowercase() ?: return CompletableFuture.completedFuture(null)
        if (!os.contains("win")) return CompletableFuture.completedFuture(null)
        return CompletableFuture.supplyAsync {
            findPrismExeWindows()
        }
    }

    // Tente de relancer en utilisant un chemin déjà résolu si dispo ; sinon, essaie quand même via PATH.
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
            // Si exe est un chemin absolu, définir le working dir = dossier du .exe (utile pour certaines installs)
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
     * Recherche l'exécutable Prism sur Windows :
     * 1) override via -Dpackwiz.prism.exe
     * 2) Registre Windows (InstallLocation / DisplayIcon pour "Prism Launcher")
     * 3) Emplacements standard (%ProgramFiles%, %ProgramFiles(x86)%, %LocalAppData%)
     * Retourne le chemin .exe si trouvé et existant, sinon null.
     */
    private fun findPrismExeWindows(): String? {
        // 1) override explicite
        val override = System.getProperty("packwiz.prism.exe")
        if (!override.isNullOrBlank() && Files.exists(Paths.get(override))) return override

        // 2) Registre (HKLM/HKCU + WOW6432Node)
        val regPaths = arrayOf(
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        )
        for (root in regPaths) {
            val exeFromReg = findPrismExeFromRegistry(root)
            if (exeFromReg != null) return exeFromReg
        }

        // 3) Emplacements standard
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

        // Dernière chance : s'il est dans le PATH, ProcessBuilder le trouvera
        return "PrismLauncher.exe"
    }

    /**
     * Parcourt le registre sous 'regRoot' pour trouver une clé avec DisplayName "Prism Launcher",
     * puis lit InstallLocation / DisplayIcon pour en déduire PrismLauncher.exe.
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
                    // Chaque bloc ressemble à :
                    // HKEY_...\{GUID}\n    DisplayName    REG_SZ    Prism Launcher
                    if (l.startsWith("HKEY_")) {
                        keys.add(l)
                    } else if (l.contains("DisplayName") && l.contains("Prism Launcher")) {
                        // Clé précédente correspondante
                        val key = keys.lastOrNull() ?: continue
                        // Essayer d'obtenir InstallLocation
                        val install = queryRegValue(key, "InstallLocation")
                        if (!install.isNullOrBlank()) {
                            val exe = Paths.get(install, "PrismLauncher.exe")
                            if (Files.exists(exe)) return exe.toString()
                        }
                        // Sinon DisplayIcon
                        val icon = queryRegValue(key, "DisplayIcon")
                        if (!icon.isNullOrBlank()) {
                            // DisplayIcon peut contenir "C:\...\PrismLauncher.exe,0"
                            val cleaned = icon.trim().trim('"').split(",")[0]
                            val exe = Paths.get(cleaned)
                            if (Files.exists(exe)) return exe.toString()
                            // Si c'est un dossier, tenter PrismLauncher.exe dedans
                            if (Files.isDirectory(exe)) {
                                val guess = exe.resolve("PrismLauncher.exe")
                                if (Files.exists(guess)) return guess.toString()
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // pas de registre ou pas de droits : on ignore
        }
        return null
    }

    /** Lit une valeur du registre Windows pour la clé et le nom donnés. */
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
            // Format attendu : <valueName>    REG_SZ    <valeur>
            val idx = text.indexOf(valueName)
            if (idx >= 0) {
                val tail = text.substring(idx + valueName.length)
                val parts = tail.split(Regex("\\s{2,}")) // séparateurs d'espaces multiples
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
