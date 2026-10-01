package com.raen.spoz.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CodeCloudClient {
    private val client = OkHttpClient()

    data class TempChannel(
        val id: String,
        val slug: String,
        val url: String,
        val name: String,
        val category: String,
        val logo: String?
    )

    data class FixtureInfo(
        val homeTeam: String,
        val awayTeam: String,
        val kickOffUtc: String,
        val isLive: Boolean
    )

    fun fetchEvents(callback: (List<SportzfyClient.Event>?, Exception?) -> Unit) {
        // Step 1: Fetch fixtures list to get kickoff times and live status
        fetchFixtures { fixtures ->
            // Step 2: Fetch channels from homepage
            val request = Request.Builder()
                .url("https://stream.codecloud.bd/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    callback(null, e)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val body = response.body?.string() ?: throw IOException("Empty response")
                        
                        // Parse channels using Regex
                        val chItemRegex = Regex("""<div class="ch-item"[^>]*>""")
                        val matches = chItemRegex.findAll(body)
                        val channelsList = ArrayList<TempChannel>()

                        for (m in matches) {
                            val tag = m.value
                            val id = extractAttr(tag, "data-id")
                            val slug = extractAttr(tag, "data-slug")
                            val url = extractAttr(tag, "data-url")
                            val name = extractAttr(tag, "data-name").replace(" VS ", " ", ignoreCase = true).trim()
                            val cat = extractAttr(tag, "data-cat")
                            val logo = extractAttr(tag, "data-logo").takeIf { it.isNotEmpty() }

                            if (slug.isNotEmpty() && name.isNotEmpty()) {
                                channelsList.add(TempChannel(id, slug, url, name, cat, logo))
                            }
                        }

                        // Group channels by normalized name
                        val grouped = channelsList.groupBy { normalizeName(it.name) }
                        val events = ArrayList<SportzfyClient.Event>()

                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        val nowIso = sdf.format(Date())

                        for ((normName, chs) in grouped) {
                            val first = chs.first()
                            
                            // Parse teams
                            val parts = normName.split(Regex("(?i)\\s+vs\\s+"))
                            val teamA = parts.getOrNull(0) ?: normName
                            val teamB = parts.getOrNull(1) ?: ""

                            // Find matching fixture
                            val matchedFixture = fixtures.find { fix ->
                                (normalizeTeam(fix.homeTeam) == normalizeTeam(teamA) && normalizeTeam(fix.awayTeam) == normalizeTeam(teamB)) ||
                                (normalizeTeam(fix.homeTeam) == normalizeTeam(teamB) && normalizeTeam(fix.awayTeam) == normalizeTeam(teamA))
                            }

                            val isLive = matchedFixture?.isLive ?: chs.any { it.category.contains("Live", ignoreCase = true) || it.name.contains("Live", ignoreCase = true) }
                            val startsAt = matchedFixture?.kickOffUtc ?: nowIso

                            // Serialize stream options in encParent: Name|URL,Name|URL
                            val streamOptions = chs.joinToString(",") { ch ->
                                val label = getStreamLabel(ch.name)
                                "$label|${ch.url}|${ch.slug}|${ch.id}"
                            }

                            events.add(
                                SportzfyClient.Event(
                                    id = first.id.ifEmpty { first.slug },
                                    parent = "codecloud.bd",
                                    encParent = streamOptions,
                                    sport = first.category.ifEmpty { "Sports" },
                                    league = if (first.category.contains("World", ignoreCase = true)) "FIFA World Cup" else first.category,
                                    teamAName = teamA,
                                    teamBName = teamB,
                                    teamALogo = first.logo,
                                    teamBLogo = null,
                                    startsAt = startsAt,
                                    isLive = isLive
                                )
                            )
                        }

                        callback(events, null)
                    } catch (e: Exception) {
                        callback(null, e)
                    }
                }
            })
        }
    }

    fun fetchStreams(encParent: String, callback: (List<SportzfyClient.Stream>?, Exception?) -> Unit) {
        try {
            val streams = ArrayList<SportzfyClient.Stream>()
            val pairs = encParent.split(",")
            for (p in pairs) {
                val parts = p.split("|")
                if (parts.size >= 2) {
                    val label = parts[0]
                    val url = parts[1]
                    val slug = parts.getOrNull(2) ?: ""
                    val id = parts.getOrNull(3) ?: ""
                    streams.add(
                        SportzfyClient.Stream(
                            id = id.ifEmpty { slug },
                            label = label,
                            streamType = if (url.contains(".m3u8")) "hls" else "embed",
                            streamUrl = url,
                            drmKid = null,
                            drmKey = null
                        )
                    )
                }
            }
            if (streams.isEmpty()) {
                callback(null, Exception("No streams found"))
            } else {
                callback(streams, null)
            }
        } catch (e: Exception) {
            callback(null, e)
        }
    }

    private fun fetchFixtures(onComplete: (List<FixtureInfo>) -> Unit) {
        val request = Request.Builder()
            .url("https://stream.codecloud.bd/api/v1/fixtures")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                onComplete(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val panels = json.optJSONObject("panels")
                    val upcomingHtml = panels?.optString("upcoming") ?: ""
                    
                    val list = ArrayList<FixtureInfo>()
                    
                    // Parse upcoming/live cards from panels HTML
                    val cardRegex = Regex("""<article class="fx-card\s+([^"]+)"[^>]*>.*?</article>""", RegexOption.DOT_MATCHES_ALL)
                    val cards = cardRegex.findAll(upcomingHtml)
                    
                    for (c in cards) {
                        val cardClass = c.groupValues[1]
                        val cardHtml = c.value
                        
                        val isLive = cardClass.contains("fx-live")
                        val kickOff = extractAttr(cardHtml, "data-kickoff").ifEmpty {
                            extractAttr(cardHtml, "data-utc")
                        }
                        
                        // Parse home & away team names
                        val nameRegex = Regex("""<span class="fx-name">([^<]+)</span>""")
                        val names = nameRegex.findAll(cardHtml).map { it.groupValues[1].trim() }.toList()
                        
                        if (names.isNotEmpty() && kickOff.isNotEmpty()) {
                            list.add(FixtureInfo(
                                homeTeam = names.getOrNull(0) ?: "",
                                awayTeam = names.getOrNull(1) ?: "",
                                kickOffUtc = kickOff,
                                isLive = isLive
                            ))
                        }
                    }
                    onComplete(list)
                } catch (e: Exception) {
                    onComplete(emptyList())
                }
            }
        })
    }

    private fun extractAttr(html: String, attrName: String): String {
        val regex = Regex("""$attrName=["']([^"']*)["']""")
        return regex.find(html)?.groupValues?.get(1) ?: ""
    }

    private fun normalizeName(name: String): String {
        var cleaned = name.replace(Regex("""\[[^\]]+\]"""), "")
            .replace(Regex("""\([^)]+\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        
        // Remove trailing stream/server/language indicators
        val suffixRegex = Regex("""(?i)\s+(Vietnamese|Spanish|English|Portuguese|Hindi|Bangla|Server\s*\d+|Link\s*\d+|Backup|SD|HD|\d+)$""")
        var prev = ""
        while (cleaned != prev) {
            prev = cleaned
            cleaned = cleaned.replace(suffixRegex, "").trim()
        }
        return cleaned
    }

    private fun normalizeTeam(team: String): String {
        return team.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]"""), "")
            .trim()
    }

    private fun getStreamLabel(fullName: String): String {
        // Extract what's inside brackets or parentheses
        val bracketMatch = Regex("""\[([^\]]+)\]""").find(fullName)
        if (bracketMatch != null) return bracketMatch.groupValues[1]
        
        val parenMatch = Regex("""\(([^)]+)\)""").find(fullName)
        if (parenMatch != null) return parenMatch.groupValues[1]

        // Look for trailing words
        val words = fullName.split(" ")
        if (words.size > 2) {
            val last = words.last()
            if (last.equals("Vietnamese", ignoreCase = true) || last.equals("Spanish", ignoreCase = true) ||
                last.equals("English", ignoreCase = true) || last.contains("Link", ignoreCase = true) ||
                last.contains("Server", ignoreCase = true) || last.toIntOrNull() != null) {
                return last
            }
        }
        return "Main Server"
    }
}
