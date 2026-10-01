package com.raen.spoz.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object EBoxClient {
    private val client: OkHttpClient by lazy { getUnsafeOkHttpClient() }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            val builder = OkHttpClient.Builder()
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
            return builder.build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun fetchEvents(callback: (List<SportzfyClient.Event>?, Exception?) -> Unit) {
        val request = Request.Builder()
            .url("https://tv.ebox.live/tv-server")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string() ?: throw IOException("Empty response")
                    val json = JSONObject(body)
                    val events = ArrayList<SportzfyClient.Event>()
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val nowIso = sdf.format(Date())

                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val category = keys.next()
                        val arr = json.optJSONArray(category) ?: continue
                        for (i in 0 until arr.length()) {
                            val ch = arr.getJSONObject(i)
                            if (ch.optBoolean("hidden", false)) continue
                            val name = ch.optString("channel_name").replace(" VS ", " ", ignoreCase = true).trim()
                            val url = ch.optString("url")
                            val rawImage = ch.optString("image")
                            val image = rawImage.replace("http://10.90.90.2", "https://tv.ebox.live")

                            events.add(
                                SportzfyClient.Event(
                                    id = url,
                                    parent = "ebox.live",
                                    encParent = url,
                                    sport = category,
                                    league = "EBox TV",
                                    teamAName = name,
                                    teamBName = "",
                                    teamALogo = if (image.isNotEmpty()) image else null,
                                    teamBLogo = null,
                                    startsAt = nowIso,
                                    isLive = true
                                )
                            )
                        }
                    }
                    // Sort alphabetically by channel name
                    callback(events, null)
                } catch (e: Exception) {
                    callback(null, e)
                }
            }
        })
    }

    fun fetchStreams(url: String, callback: (List<SportzfyClient.Stream>?, Exception?) -> Unit) {
        val streams = listOf(
            SportzfyClient.Stream(
                id = url,
                label = "Primary Server",
                streamType = "hls",
                streamUrl = url,
                drmKid = null,
                drmKey = null
            )
        )
        callback(streams, null)
    }
}
