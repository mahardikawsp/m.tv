package com.streamtv.app.data

import java.net.URLEncoder

/**
 * Multi-site support - daftar situs streaming yang bisa dipilih user
 */
object SiteManager {

    data class StreamingSite(
        val name: String,
        val url: String,
        val icon: String = "🎬",
        val searchUrl: String = "",
        val domain: String = ""
    ) {
        fun buildSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val template = searchUrl.ifBlank {
                url.trimEnd('/') + "/?s=%s"
            }
            return if (template.contains("%s")) {
                template.replace("%s", encoded)
            } else {
                template + encoded
            }
        }
    }

    val sites = listOf(
        StreamingSite(
            name = "iDlix",
            url = "https://z2.idlixku.com",
            icon = "🎬",
            searchUrl = "https://z2.idlixku.com/?s=%s",
            domain = "idlix"
        ),
        StreamingSite(
            name = "LK21",
            url = "https://tv10.lk21official.cc",
            icon = "🎥",
            searchUrl = "https://tv10.lk21official.cc/?s=%s",
            domain = "lk21"
        ),
        StreamingSite(
            name = "Rebahin",
            url = "https://rebahinxxi3.beauty/",
            icon = "📺",
            searchUrl = "https://rebahinxxi3.beauty/?s=%s",
            domain = "rebahin"
        ),
        StreamingSite(
            name = "Nobargasi",
            url = "https://www.nobargasi.my.id",
            icon = "🍿",
            searchUrl = "https://www.nobargasi.my.id/?s=%s",
            domain = "nobargasi"
        )
    )

    fun getSiteByName(name: String): StreamingSite? {
        return sites.find { it.name == name }
    }

    /**
     * -1 kalau URL bukan salah satu situs di daftar.
     */
    fun siteIndexForUrl(url: String): Int {
        if (url.isBlank()) return -1
        val lower = url.lowercase()
        return sites.indexOfFirst { site ->
            site.domain.isNotBlank() && lower.contains(site.domain.lowercase())
        }
    }

    fun siteForUrl(url: String): StreamingSite? {
        val index = siteIndexForUrl(url)
        return if (index >= 0) sites[index] else null
    }
}
