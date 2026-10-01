package com.raen.spoz

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Main : NavKey

@Serializable
data class Player(
    val streams: List<StreamItem>,
    val startIndex: Int,
    val eventName: String = ""
) : NavKey

@Serializable
data class StreamItem(
    val url: String,
    val type: String,
    val drmKid: String?,
    val drmKey: String?,
    val label: String
)
