package link.infra.packwiz.installer.util

import link.infra.packwiz.installer.target.OS

object OSGetter {
    val current: OS by lazy {
        val n = System.getProperty("os.name").lowercase()
        when {
            "win" in n -> OS.WINDOWS
            "mac" in n || "darwin" in n -> OS.MACOS
            "nux" in n || "nix" in n -> OS.LINUX
            else -> OS.UNKNOWN
        }
    }
}