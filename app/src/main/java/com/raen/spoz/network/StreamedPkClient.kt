package com.raen.spoz.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object StreamedPkClient {
    private val client = OkHttpClient()

    data class StreamedEvent(
        val id: String,
        val title: String,
        val category: String,
        val date: Long,
        val popular: Boolean,
        val teamAName: String,
        val teamBName: String,
        val teamALogo: String?,
        val teamBLogo: String?,
        val sources: List<SourceItem>
    )

    data class SourceItem(
        val source: String,
        val id: String
    )

    fun fetchEvents(callback: (List<SportzfyClient.Event>?, Exception?) -> Unit) {
        val request = Request.Builder()
            .url("https://streamed.pk/api/matches/all")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string() ?: throw IOException("Empty response")
                    val array = JSONArray(body)
                    val events = ArrayList<SportzfyClient.Event>()

                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }

                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val id = obj.optString("id")
                        val title = obj.optString("title")
                        val category = obj.optString("category")
                        val dateMs = obj.optLong("date")
                        
                        val teamsObj = obj.optJSONObject("teams")
                        val homeName = teamsObj?.optJSONObject("home")?.optString("name") ?: ""
                        val awayName = teamsObj?.optJSONObject("away")?.optString("name") ?: ""
                        
                        val homeBadge = teamsObj?.optJSONObject("home")?.optString("badge")?.let {
                            if (it.startsWith("http")) it else "https://streamed.pk$it"
                        }
                        val awayBadge = teamsObj?.optJSONObject("away")?.optString("badge")?.let {
                            if (it.startsWith("http")) it else "https://streamed.pk$it"
                        }

                        val startsAtIso = sdf.format(Date(dateMs))

                        // Parse sources list
                        val sourcesArray = obj.optJSONArray("sources")
                        val sourcesList = StringBuilder()
                        if (sourcesArray != null) {
                            for (j in 0 until sourcesArray.length()) {
                                val srcObj = sourcesArray.getJSONObject(j)
                                val srcName = srcObj.optString("source")
                                val srcId = srcObj.optString("id")
                                if (j > 0) sourcesList.append(",")
                                sourcesList.append("$srcName:$srcId")
                            }
                        }

                        events.add(
                            SportzfyClient.Event(
                                id = id,
                                parent = "streamed.pk",
                                encParent = sourcesList.toString(), // We store sources encoded here
                                sport = category.ifEmpty { "Sports" },
                                league = category.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                                teamAName = homeName.ifEmpty { title },
                                teamBName = awayName,
                                teamALogo = homeBadge,
                                teamBLogo = awayBadge,
                                startsAt = startsAtIso,
                                isLive = false // Computed dynamically using date time
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

    fun fetchStreams(encodedSources: String, callback: (List<SportzfyClient.Stream>?, Exception?) -> Unit) {
        if (encodedSources.isEmpty()) {
            callback(emptyList(), null)
            return
        }

        val pairs = encodedSources.split(",")
        val resultStreams = ArrayList<SportzfyClient.Stream>()
        var pendingRequests = pairs.size
        if (pendingRequests == 0) {
            callback(resultStreams, null)
            return
        }
        var hasFinished = false

        for (pair in pairs) {
            val parts = pair.split(":")
            if (parts.size != 2) {
                pendingRequests--
                if (pendingRequests == 0 && !hasFinished) {
                    hasFinished = true
                    callback(resultStreams, null)
                }
                continue
            }
            val source = parts[0]
            val sourceId = parts[1]

            val request = Request.Builder()
                .url("https://streamed.pk/api/stream/$source/$sourceId")
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    synchronized(resultStreams) {
                        pendingRequests--
                        if (pendingRequests == 0 && !hasFinished) {
                            hasFinished = true
                            callback(resultStreams, null)
                        }
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val body = response.body?.string() ?: ""
                        val array = JSONArray(body)
                        synchronized(resultStreams) {
                            for (i in 0 until array.length()) {
                                val obj = array.getJSONObject(i)
                                val embedUrl = obj.optString("embedUrl")
                                val language = obj.optString("language")
                                val hd = obj.optBoolean("hd")
                                val label = "$source - $language " + (if (hd) "(HD)" else "(SD)")
                                resultStreams.add(
                                    SportzfyClient.Stream(
                                        id = obj.optString("id", "${source}_${i}"),
                                        label = label,
                                        streamType = "embed", // Marked as embed
                                        streamUrl = embedUrl,
                                        drmKid = null,
                                        drmKey = null
                                    )
                                )
                            }
                            pendingRequests--
                            if (pendingRequests == 0 && !hasFinished) {
                                hasFinished = true
                                callback(resultStreams, null)
                            }
                        }
                    } catch (e: Exception) {
                        synchronized(resultStreams) {
                            pendingRequests--
                            if (pendingRequests == 0 && !hasFinished) {
                                hasFinished = true
                                callback(resultStreams, null)
                            }
                        }
                    }
                }
            })
        }
    }

    // Attempt to scrape/extract HLS stream URL from the embed page HTML
    fun resolveDirectStreamUrl(embedUrl: String, callback: (String) -> Unit) {
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "https://streamed.pk/")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(embedUrl) // Fallback to embed Url
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val html = response.body?.string() ?: ""
                    // Try to match standard .m3u8 urls in scripts or text
                    val regex = Regex("""https?://[^\s"'`]+\.m3u8[^\s"'`]*""")
                    val match = regex.find(html)
                    if (match != null) {
                        callback(match.value)
                    } else {
                        // Look for base64 encoded streams or secondary frames
                        callback(embedUrl)
                    }
                } catch (e: Exception) {
                    callback(embedUrl)
                }
            }
        })
    }
}
