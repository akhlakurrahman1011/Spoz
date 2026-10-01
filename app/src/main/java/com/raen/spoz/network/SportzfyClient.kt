package com.raen.spoz.network

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SportzfyClient {
    private val client = OkHttpClient()
    private const val KEY = "ZESBtSlRTuF4Ac4k757OuasOWOA0W8LcqRn3SFgdInDoMyS8"
    
    var serverTimeOffset = 0L

    data class Event(
        val id: String,
        val parent: String,
        val encParent: String,
        val sport: String,
        val league: String,
        val teamAName: String,
        val teamBName: String,
        val teamALogo: String?,
        val teamBLogo: String?,
        val startsAt: String,
        val isLive: Boolean
    )

    data class Stream(
        val id: String,
        val label: String,
        val streamType: String,
        val streamUrl: String,
        val drmKid: String?,
        val drmKey: String?
    )

    fun fetchEvents(callback: (List<Event>?, Exception?) -> Unit) {
        val request = Request.Builder()
            .url("https://live.sportzfy.life/api/upstream/events")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val dateHeader = response.header("Date")
                    if (dateHeader != null) {
                        try {
                            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                            val serverTime = format.parse(dateHeader)?.time ?: 0L
                            if (serverTime > 0L) {
                                serverTimeOffset = serverTime - System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            // ignore parsing fallback
                        }
                    }

                    val body = response.body?.string() ?: throw IOException("Empty response")
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        throw IOException("API returned error")
                    }
                    val eventsArray = json.getJSONArray("events")
                    val eventsList = ArrayList<Event>()
                    for (i in 0 until eventsArray.length()) {
                        val obj = eventsArray.getJSONObject(i)
                        eventsList.add(
                            Event(
                                id = obj.optString("id"),
                                parent = obj.optString("parent"),
                                encParent = obj.optString("enc_parent"),
                                sport = obj.optString("sport"),
                                league = obj.optString("league"),
                                teamAName = obj.optString("team_a_name"),
                                teamBName = obj.optString("team_b_name"),
                                teamALogo = obj.optString("team_a_logo").takeIf { it.isNotEmpty() },
                                teamBLogo = obj.optString("team_b_logo").takeIf { it.isNotEmpty() },
                                startsAt = obj.optString("starts_at"),
                                isLive = run {
                                    val raw = obj.opt("is_live")
                                    when (raw) {
                                        is Boolean -> raw
                                        is Number -> raw.toInt() == 1
                                        is String -> raw == "1" || raw.lowercase() == "true"
                                        else -> false
                                    }
                                }
                            )
                        )
                    }
                    callback(eventsList, null)
                } catch (e: Exception) {
                    callback(null, e)
                }
            }
        })
    }

    fun fetchPlayback(parent: String, callback: (List<Stream>?, Exception?) -> Unit) {
        val encodedParent = android.net.Uri.encode(parent)
        val request = Request.Builder()
            .url("https://live.sportzfy.life/api/upstream/playback/$encodedParent")
            .addHeader("Accept", "application/json")
            .addHeader("X-Requested-With", "lsp")
            .addHeader("X-LSP-Enc", "1")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string() ?: throw IOException("Empty response")
                    val json = JSONObject(body)
                    
                    val encData = json.optString("enc", "")
                    val bucket = json.optString("bucket", "")
                    
                    val decryptedString = if (encData.isNotEmpty() && bucket.isNotEmpty()) {
                        decrypt(encData, bucket)
                    } else {
                        body
                    }

                    val decryptedJson = JSONObject(decryptedString)
                    val streamsArray = decryptedJson.getJSONArray("streams")
                    val streamsList = ArrayList<Stream>()
                    for (i in 0 until streamsArray.length()) {
                        val obj = streamsArray.getJSONObject(i)
                        streamsList.add(
                            Stream(
                                id = obj.optString("id"),
                                label = obj.optString("label"),
                                streamType = obj.optString("stream_type"),
                                streamUrl = obj.optString("stream_url"),
                                drmKid = obj.optString("drm_kid").takeIf { it.isNotEmpty() && it != "null" },
                                drmKey = obj.optString("drm_key").takeIf { it.isNotEmpty() && it != "null" }
                            )
                        )
                    }
                    callback(streamsList, null)
                } catch (e: Exception) {
                    callback(null, e)
                }
            }
        })
    }

    private fun decrypt(encBase64: String, bucket: String): String {
        // 1. Base64 Decode
        val rawBytes = Base64.decode(encBase64, Base64.DEFAULT)
        val iv = rawBytes.sliceArray(0 until 12)
        val ciphertext = rawBytes.sliceArray(12 until rawBytes.size)

        // 2. Key Derivation: SHA-256(KEY + "|lsp-v1|" + bucket)
        val keyInput = "$KEY|lsp-v1|$bucket".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(keyInput)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // 3. Decrypt AES-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(ciphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
