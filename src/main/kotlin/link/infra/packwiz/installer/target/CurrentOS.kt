package link.infra.packwiz.installer.target

object CurrentOS {
    val current: OS by lazy {
        val n = System.getProperty("os.name").lowercase()
        when {
            "win" in n -> OS.WINDOWS
            "mac" in n || "darwin" in n -> OS.MACOS
            "nux" in n || "nix" in n -> OS.LINUX
            else -> OS.OTHER
        }
    }
    fun asId(os: OS) = when (os) {
        OS.WINDOWS -> "windows"
        OS.LINUX   -> "linux"
        OS.MACOS   -> "macos"
        OS.OTHER   -> "other"
    }
}