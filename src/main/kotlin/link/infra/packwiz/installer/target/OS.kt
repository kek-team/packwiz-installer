package link.infra.packwiz.installer.target

import cc.ekblad.toml.model.TomlValue
import cc.ekblad.toml.tomlMapper
import com.google.gson.annotations.SerializedName
import java.lang.Exception

enum class OS(osName: String) {
    @SerializedName(value = "macos")
    MACOS("macos"),
    @SerializedName(value = "windows")
    WINDOWS("windows"),
    @SerializedName("linux")
    LINUX("linux"),
    @SerializedName(value = "unknown")
    UNKNOWN("unknown");

    private val osName: String


    init {
        this.osName = osName.lowercase()
    }

    override fun toString() = osName

    companion object {
        fun from(name: String): OS? {
            val lower = name.lowercase()
            return values().find { it.osName == lower }
        }

        fun mapper() = tomlMapper {
            encoder { it: OS -> TomlValue.String(it.osName) }
            decoder { it: TomlValue.String ->
                from(it.value) ?: throw Exception("Invalid OS ${it.value}")
            }
        }
    }


}