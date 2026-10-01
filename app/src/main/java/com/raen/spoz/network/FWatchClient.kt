package com.raen.spoz.network

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object FWatchClient {
    private val client = OkHttpClient()

    fun fetchEvents(callback: (List<SportzfyClient.Event>?, Exception?) -> Unit) {
        val request = Request.Builder()
            .url("https://fwatch.tv/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string() ?: throw IOException("Empty response")
                    val cardRegex = Regex("""playChannel\((\{.*?\})\)""")
                    val matches = cardRegex.findAll(body)
                    
                    val events = ArrayList<SportzfyClient.Event>()
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val nowIso = sdf.format(Date())

                    for (m in matches) {
                        val unescapedJson = m.groupValues[1]
                            .replace("&quot;", "\"")
                            .replace("\\/", "/")
                        val obj = JSONObject(unescapedJson)
                        val id = obj.optString("id")
                        val name = obj.optString("name").replace(" VS ", " ", ignoreCase = true).trim()
                        val logo = obj.optString("logo")
                        val streamType = obj.optString("stream_type")
                        val sources = obj.optJSONArray("sources") ?: JSONArray()

                        // Serialize sources list: URL|priority|useProxy (separated by commas)
                        val serializedSources = ArrayList<String>()
                        for (i in 0 until sources.length()) {
                            val src = sources.getJSONObject(i)
                            val url = src.optString("url")
                            val priority = src.optInt("priority", i + 1)
                            val useProxy = src.optInt("use_proxy", 0)
                            serializedSources.add("$url|$priority|$useProxy")
                        }
                        val encParent = serializedSources.joinToString(";")

                        events.add(
                            SportzfyClient.Event(
                                id = id,
                                parent = "fwatch.tv",
                                encParent = encParent,
                                sport = "Live Event",
                                league = "FWatch",
                                teamAName = name,
                                teamBName = "",
                                teamALogo = if (logo.isNotEmpty()) "https://fwatch.tv$logo" else null,
                                teamBLogo = null,
                                startsAt = nowIso,
                                isLive = true
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

    fun fetchStreams(channelId: String, encParent: String, callback: (List<SportzfyClient.Stream>?, Exception?) -> Unit) {
        val rawSources = encParent.split(";")
        val streams = ArrayList<SportzfyClient.Stream>()

        if (rawSources.isEmpty() || encParent.isEmpty()) {
            callback(null, Exception("No streams found"))
            return
        }

        // We fetch keys sequentially/concurrently for DRM-protected streams
        var completed = 0
        for (i in rawSources.indices) {
            val parts = rawSources[i].split("|")
            val url = parts[0]
            val priority = parts.getOrNull(1)?.toIntOrNull() ?: (i + 1)

            val svIndex = i + 1 // sv parameter for keys
            val isDash = url.contains(".mpd")

            if (isDash) {
                // Fetch Widevine Clearkeys
                fetchKeys(channelId, svIndex) { kid, key ->
                    synchronized(streams) {
                        streams.add(
                            SportzfyClient.Stream(
                                id = "${channelId}_$svIndex",
                                label = "Server $priority" + (if (kid != null) " (DRM)" else ""),
                                streamType = "dash",
                                streamUrl = url,
                                drmKid = kid,
                                drmKey = key
                            )
                        )
                        completed++
                        if (completed == rawSources.size) {
                            callback(streams, null)
                        }
                    }
                }
            } else {
                synchronized(streams) {
                    streams.add(
                        SportzfyClient.Stream(
                            id = "${channelId}_$svIndex",
                            label = "Server $priority",
                            streamType = "hls",
                            streamUrl = url,
                            drmKid = null,
                            drmKey = null
                        )
                    )
                    completed++
                    if (completed == rawSources.size) {
                        callback(streams, null)
                    }
                }
            }
        }
    }

    private fun fetchKeys(channelId: String, svIndex: Int, onComplete: (String?, String?) -> Unit) {
        val url = "https://fwatch.tv/get_keys.php?ch=${channelId}&sv=$svIndex"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "https://fwatch.tv/")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                onComplete(null, null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.optBoolean("drm", false)) {
                        val obKid = json.optString("key_id")
                        val obKey = json.optString("key_value")
                        val kid = deobfuscate(obKid)
                        val key = deobfuscate(obKey)
                        if (kid.isNotEmpty() && key.isNotEmpty()) {
                            onComplete(kid, key)
                            return
                        }
                    }
                } catch (e: Exception) {
                    // fallback to no DRM
                }
                onComplete(null, null)
            }
        })
    }

    private fun rot13(str: String): String {
        val result = StringBuilder()
        for (char in str) {
            when (char) {
                in 'a'..'z' -> {
                    val shifted = char + 13
                    if (shifted > 'z') result.append(shifted - 26) else result.append(shifted)
                }
                in 'A'..'Z' -> {
                    val shifted = char + 13
                    if (shifted > 'Z') result.append(shifted - 26) else result.append(shifted)
                }
                else -> result.append(char)
            }
        }
        return result.toString()
    }

    private fun deobfuscate(str: String): String {
        if (str.isEmpty()) return ""
        return try {
            val decodedBytes = Base64.decode(str, Base64.DEFAULT)
            val decodedStr = String(decodedBytes, Charsets.UTF_8)
            rot13(decodedStr).trim()
        } catch (e: Exception) {
            ""
        }
    }
}
