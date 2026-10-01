package com.raen.spoz.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object StreamFreeClient {
    private val client = OkHttpClient()

    fun fetchEvents(callback: (List<SportzfyClient.Event>?, Exception?) -> Unit) {
        val request = Request.Builder()
            .url("https://streamfree.top/api/v1/streams")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string() ?: throw IOException("Empty response")
                    val json = JSONObject(body)
                    val array = json.optJSONArray("streams")
                    val events = ArrayList<SportzfyClient.Event>()

                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }

                    if (array != null) {
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            val name = obj.optString("name")
                            val category = obj.optString("category")
                            val league = obj.optString("league")
                            val streamKey = obj.optString("stream_key")
                            val timestampSecs = obj.optLong("match_timestamp")
                            val embedUrl = obj.optString("embed_url")
                            val thumbnailUrl = obj.optString("thumbnail_url")

                            val startsAtIso = sdf.format(Date(timestampSecs * 1000))

                            events.add(
                                SportzfyClient.Event(
                                    id = streamKey,
                                    parent = "streamfree.top",
                                    encParent = embedUrl,
                                    sport = category.ifEmpty { "Sports" },
                                    league = league.ifEmpty { category.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } },
                                    teamAName = name,
                                    teamBName = "",
                                    teamALogo = thumbnailUrl.takeIf { it.isNotEmpty() },
                                    teamBLogo = null,
                                    startsAt = startsAtIso,
                                    isLive = false
                                )
                            )
                        }
                    }
                    callback(events, null)
                } catch (e: Exception) {
                    callback(null, e)
                }
            }
        })
    }

    fun fetchStreams(embedUrl: String, streamKey: String, callback: (List<SportzfyClient.Stream>?, Exception?) -> Unit) {
        resolveDirectStreamUrl(streamKey, embedUrl) { directUrl ->
            val streamList = listOf(
                SportzfyClient.Stream(
                    id = streamKey,
                    label = "Main Stream",
                    streamType = if (directUrl.contains(".m3u8")) "hls" else "embed",
                    streamUrl = directUrl,
                    drmKid = null,
                    drmKey = null
                )
            )
            callback(streamList, null)
        }
    }

    fun resolveDirectStreamUrl(streamKey: String, embedUrl: String, callback: (String) -> Unit) {
        // Step 1: Fetch the embed page HTML to extract _0x token JSON
        val request = Request.Builder()
            .url(embedUrl)
            .header("Referer", "https://streamfree.top/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(embedUrl)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val html = response.body?.string() ?: ""
                    val tokenRegex = Regex("""const\s+_0x\s*=\s*(\{.*?\});""")
                    val match = tokenRegex.find(html)
                    val tokensObj = if (match != null) JSONObject(match.groupValues[1]) else null

                    // Step 2: Determine available quality
                    val statusReq = Request.Builder()
                        .url("https://streamfree.top/api/stream-status/$streamKey")
                        .header("Referer", "https://streamfree.top/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()

                    client.newCall(statusReq).enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: IOException) {
                            fetchStreamKey(streamKey, "720p", tokensObj, embedUrl, callback)
                        }

                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            val quality = try {
                                val body = response.body?.string() ?: ""
                                val json = JSONObject(body)
                                val qualities = json.optJSONObject("qualities")
                                when {
                                    qualities?.optBoolean("1080p") == true -> "1080p"
                                    qualities?.optBoolean("720p") == true -> "720p"
                                    qualities?.optBoolean("540p") == true -> "540p"
                                    else -> "720p"
                                }
                            } catch (e: Exception) { "720p" }
                            fetchStreamKey(streamKey, quality, tokensObj, embedUrl, callback)
                        }
                    })
                } catch (e: Exception) {
                    callback(embedUrl)
                }
            }
        })
    }

    private fun fetchStreamKey(streamKey: String, quality: String, tokensObj: JSONObject?, fallbackUrl: String, callback: (String) -> Unit) {
        val req = Request.Builder()
            .url("https://streamfree.top/get-stream-key/$streamKey")
            .header("Referer", "https://streamfree.top/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(fallbackUrl)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string() ?: throw IOException("empty")
                    val json = JSONObject(body)
                    val isExternal = json.optBoolean("is_external", false)
                    if (isExternal) {
                        val extUrl = json.optString("external_url")
                        if (extUrl.isNotEmpty()) { callback(extUrl); return }
                    }

                    val serverName = json.optString("server_name", "origin")
                    val path = if (serverName != "origin")
                        "/live-cdn/$streamKey$quality/index.m3u8"
                    else
                        "/live/$streamKey$quality/index.m3u8"

                    var finalUrl = "https://streamfree.top$path"

                    // Append tokens if available
                    val qualityToken = tokensObj?.optJSONObject(quality)
                    if (qualityToken != null) {
                        val t = qualityToken.optString("_t")
                        val e = qualityToken.optString("_e")
                        val n = qualityToken.optString("_n")
                        if (t.isNotEmpty() && e.isNotEmpty() && n.isNotEmpty()) {
                            finalUrl += "?_t=$t&_e=$e&_n=$n"
                        }
                    }
                    callback(finalUrl)
                } catch (e: Exception) {
                    callback(fallbackUrl)
                }
            }
        })
    }

    fun resolveDirectStreamUrl(embedUrl: String, callback: (String) -> Unit) {
        val streamKey = embedUrl.substringAfterLast("/")
        resolveDirectStreamUrl(streamKey, embedUrl, callback)
    }
}
