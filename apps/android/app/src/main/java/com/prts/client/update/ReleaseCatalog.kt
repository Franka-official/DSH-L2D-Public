package com.prts.client.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class ReleaseVersion(
    val major: Int, val minor: Int, val patch: Int, val revision: Int? = null,
) : Comparable<ReleaseVersion> {
    val name: String get() = "$major.$minor.$patch"
    val display: String get() = revision?.let { "$name（$it）" } ?: name

    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.revision ?: 0 })

    fun newerThan(installedName: String, installedCode: Int): Boolean {
        val installed = parse(installedName) ?: return false
        if (revision != null && revision <= installedCode) return false
        return this > installed.copy(revision = installedCode)
    }

    companion object {
        private val pattern = Regex("""v?(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})(?:-r([1-9][0-9]{0,8}))?""")

        fun parse(value: String): ReleaseVersion? {
            val fields = pattern.matchEntire(value)?.groupValues ?: return null
            return ReleaseVersion(fields[1].toInt(), fields[2].toInt(), fields[3].toInt(), fields[4].toIntOrNull())
        }
    }
}

internal data class PluginRelease(val version: ReleaseVersion, val url: String)

internal data class PublicRelease(
    val version: ReleaseVersion,
    val notes: String,
    val apk: String?,
    val goPlugin: PluginRelease?,
)

internal data class ReleaseCatalog(val latest: PublicRelease, val history: List<PublicRelease>) {
    val pairedGoPlugin: PluginRelease?
        get() = latest.goPlugin ?: history.firstNotNullOfOrNull {
            it.goPlugin?.takeIf { _ -> it.version <= latest.version }
        }
}

internal object GitHubReleaseCatalog {
    private const val REPO = "Franka-official/DSH-L2D-Public"
    private const val API = "https://api.github.com/repos/$REPO/releases"
    private const val ASSETS = "https://github.com/$REPO/releases/download"
    private const val PAGE_SIZE = 30
    private val goName = Regex("""dsh-go-live2d-(\d+\.\d+\.\d+)-agpl-source\.zip""")
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun load(): ReleaseCatalog {
        val latest = parse(JSONObject(get("$API/latest"))) ?: throw IOException("Invalid latest release")
        val releases = mutableListOf<PublicRelease>()
        var page = 1
        while (true) {
            val data = JSONArray(get("$API?per_page=$PAGE_SIZE&page=$page"))
            for (index in 0 until data.length()) {
                parse(data.optJSONObject(index))?.let(releases::add)
            }
            if (data.length() < PAGE_SIZE) break
            page++
        }
        return ReleaseCatalog(latest, (releases + latest).distinctBy { it.version }.sortedByDescending { it.version })
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DSH-L2D-Android")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty GitHub response")
            val source = body.source()
            if (source.request(2L * 1024 * 1024 + 1)) throw IOException("GitHub response too large")
            source.readUtf8()
        }
    }

    fun parse(json: JSONObject?): PublicRelease? {
        if (json == null || json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name")
        val version = ReleaseVersion.parse(tag) ?: return null
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apk: String? = null
        var plugin: PluginRelease? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (asset.optString("state") != "uploaded" || asset.optLong("size") <= 0 || url != "$ASSETS/$tag/$name") continue
            if (name == "DSH-L2D-${version.name}-local-tts.apk") {
                apk = url
            } else {
                val pluginVersion = goName.matchEntire(name)?.groupValues?.get(1)?.let(ReleaseVersion::parse) ?: continue
                if (plugin == null || pluginVersion > plugin.version) plugin = PluginRelease(pluginVersion, url)
            }
        }
        return PublicRelease(version, json.optString("body", "").take(16_000), apk, plugin)
    }
}
