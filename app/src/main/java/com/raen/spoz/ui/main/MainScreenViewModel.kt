package com.raen.spoz.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raen.spoz.network.SportzfyClient
import com.raen.spoz.network.StreamedPkClient
import com.raen.spoz.network.StreamFreeClient
import com.raen.spoz.network.CodeCloudClient
import com.raen.spoz.network.FWatchClient
import com.raen.spoz.network.EBoxClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

sealed interface MainScreenUiState {
    object Loading : MainScreenUiState
    data class Error(val message: String) : MainScreenUiState
    data class Success(
        val events: List<SportzfyClient.Event>,
        val filteredEvents: List<SportzfyClient.Event>,
        val categories: List<String>,
        val selectedCategory: String
    ) : MainScreenUiState
}

class MainScreenViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Loading)
    val uiState: StateFlow<MainScreenUiState> = _uiState

    val selectedSource = MutableStateFlow(1) // 1, 2, or 3

    val playbackStreams = MutableStateFlow<List<SportzfyClient.Stream>?>(null)
    val playbackLoading = MutableStateFlow(false)
    val playbackError = MutableStateFlow<String?>(null)

    private val categories = listOf("Live", "Recent", "Upcoming", "All")

    init {
        refreshEvents()
    }

    fun setSource(source: Int) {
        if (selectedSource.value != source) {
            selectedSource.value = source
            refreshEvents()
        }
    }

    fun refreshEvents() {
        _uiState.value = MainScreenUiState.Loading
        val callback: (List<SportzfyClient.Event>?, Exception?) -> Unit = { events, error ->
            viewModelScope.launch {
                if (error != null) {
                    _uiState.value = MainScreenUiState.Error(error.localizedMessage ?: "Unknown Error")
                } else if (events != null) {
                    val filtered = filterEventsByStatus(events, "Live")
                    val activeSource = selectedSource.value
                    val extraCats = if (activeSource == 4 || activeSource == 6) {
                        events.map { it.sport }.distinct().filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    }
                    _uiState.value = MainScreenUiState.Success(
                        events = events,
                        filteredEvents = filtered,
                        categories = categories + extraCats,
                        selectedCategory = "Live"
                    )
                }
            }
        }

        when (selectedSource.value) {
            1 -> SportzfyClient.fetchEvents(callback)
            2 -> StreamedPkClient.fetchEvents(callback)
            3 -> StreamFreeClient.fetchEvents(callback)
            4 -> CodeCloudClient.fetchEvents(callback)
            5 -> FWatchClient.fetchEvents(callback)
            6 -> EBoxClient.fetchEvents(callback)
        }
    }

    fun selectCategory(category: String) {
        val currentState = _uiState.value as? MainScreenUiState.Success ?: return
        val filtered = filterEventsByStatus(currentState.events, category)
        _uiState.value = currentState.copy(
            selectedCategory = category,
            filteredEvents = filtered
        )
    }

    private fun filterEventsByStatus(events: List<SportzfyClient.Event>, category: String): List<SportzfyClient.Event> {
        val now = System.currentTimeMillis() + SportzfyClient.serverTimeOffset
        val eighteenHoursMs = 18 * 60 * 60 * 1000

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val filtered = events.filter { event ->
            val eventTime = try {
                val cleanDate = event.startsAt.substringBefore(".")
                sdf.parse(cleanDate)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            val isActuallyLive = event.isLive
            val isUpcoming = eventTime > now && !isActuallyLive
            val isRecent = eventTime <= now && !isActuallyLive

            when (category) {
                "Live" -> isActuallyLive
                "Recent" -> isRecent
                "Upcoming" -> isUpcoming
                "All" -> true
                else -> event.sport == category
            }
        }

        val activeSrc = selectedSource.value
        if (activeSrc == 1) {
            return filtered.sortedBy { event ->
                try {
                    val cleanDate = event.startsAt.substringBefore(".")
                    sdf.parse(cleanDate)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
        } else {
            return filtered
        }
    }

    val activeEventName = MutableStateFlow("")

    fun fetchPlayback(event: SportzfyClient.Event) {
        activeEventName.value = event.teamAName + (if (event.teamBName.isNotEmpty()) " vs " + event.teamBName else "")
        playbackLoading.value = true
        playbackError.value = null
        playbackStreams.value = null

        val callback: (List<SportzfyClient.Stream>?, Exception?) -> Unit = { streams, error ->
            viewModelScope.launch {
                playbackLoading.value = false
                if (error != null) {
                    playbackError.value = error.localizedMessage ?: "Failed to load servers"
                } else if (streams != null) {
                    playbackStreams.value = streams
                }
            }
        }

        when (event.parent) {
            "streamed.pk" -> StreamedPkClient.fetchStreams(event.encParent, callback)
            "streamfree.top" -> StreamFreeClient.fetchStreams(event.encParent, event.id, callback)
            "codecloud.bd" -> CodeCloudClient.fetchStreams(event.encParent, callback)
            "fwatch.tv" -> FWatchClient.fetchStreams(event.id, event.encParent, callback)
            "ebox.live" -> EBoxClient.fetchStreams(event.encParent, callback)
            else -> SportzfyClient.fetchPlayback(event.parent, callback)
        }
    }

    fun clearPlayback() {
        playbackStreams.value = null
        playbackError.value = null
        playbackLoading.value = false
    }

    fun switchSourceForEvent(currentEventName: String, targetSource: Int, onResult: (List<SportzfyClient.Stream>?, String?) -> Unit) {
        val callback: (List<SportzfyClient.Event>?, Exception?) -> Unit = { events, error ->
            viewModelScope.launch {
                if (error != null) {
                    onResult(null, error.localizedMessage ?: "Failed to fetch source matches")
                } else if (events != null) {
                    val targetEvent = events.find {
                        val nameA = (it.teamAName + " " + it.teamBName)
                            .lowercase(Locale.US)
                            .replace(" vs ", " ")
                            .replace(" vs. ", " ")
                            .replace(" vs", " ")
                            .replace("-", " ")
                            .replace(Regex("[^a-z0-9]"), "")
                        val nameB = currentEventName
                            .lowercase(Locale.US)
                            .replace(" vs ", " ")
                            .replace(" vs. ", " ")
                            .replace(" vs", " ")
                            .replace("-", " ")
                            .replace(Regex("[^a-z0-9]"), "")
                        nameA.contains(nameB) || nameB.contains(nameA) || (it.teamAName.isNotEmpty() && currentEventName.contains(it.teamAName, ignoreCase = true))
                    }
                    if (targetEvent != null) {
                        val streamCallback: (List<SportzfyClient.Stream>?, Exception?) -> Unit = { streams, streamErr ->
                            viewModelScope.launch {
                                if (streamErr != null) {
                                    onResult(null, streamErr.localizedMessage ?: "Failed to fetch stream servers")
                                } else {
                                    onResult(streams, null)
                                }
                            }
                        }
                        when (targetEvent.parent) {
                            "streamed.pk" -> StreamedPkClient.fetchStreams(targetEvent.encParent, streamCallback)
                            "streamfree.top" -> StreamFreeClient.fetchStreams(targetEvent.encParent, targetEvent.id, streamCallback)
                            "codecloud.bd" -> CodeCloudClient.fetchStreams(targetEvent.encParent, streamCallback)
                            "fwatch.tv" -> FWatchClient.fetchStreams(targetEvent.id, targetEvent.encParent, streamCallback)
                            else -> SportzfyClient.fetchPlayback(targetEvent.parent, streamCallback)
                        }
                    } else {
                        onResult(null, "Match not found in Source $targetSource")
                    }
                }
            }
        }
        when (targetSource) {
            1 -> SportzfyClient.fetchEvents(callback)
            2 -> StreamedPkClient.fetchEvents(callback)
            3 -> StreamFreeClient.fetchEvents(callback)
            4 -> CodeCloudClient.fetchEvents(callback)
            5 -> FWatchClient.fetchEvents(callback)
            6 -> EBoxClient.fetchEvents(callback)
        }
    }

    fun fetchEventsForSource(source: Int, callback: (List<SportzfyClient.Event>?, Exception?) -> Unit) {
        viewModelScope.launch {
            val wrapCallback: (List<SportzfyClient.Event>?, Exception?) -> Unit = { events, error ->
                viewModelScope.launch { callback(events, error) }
            }
            when (source) {
                1 -> SportzfyClient.fetchEvents(wrapCallback)
                2 -> StreamedPkClient.fetchEvents(wrapCallback)
                3 -> StreamFreeClient.fetchEvents(wrapCallback)
                4 -> CodeCloudClient.fetchEvents(wrapCallback)
                5 -> FWatchClient.fetchEvents(wrapCallback)
                6 -> EBoxClient.fetchEvents(wrapCallback)
                else -> callback(null, Exception("Invalid source"))
            }
        }
    }

    fun fetchPlaybackForEvent(event: SportzfyClient.Event, callback: (List<SportzfyClient.Stream>?, Exception?) -> Unit) {
        viewModelScope.launch {
            val wrapCallback: (List<SportzfyClient.Stream>?, Exception?) -> Unit = { streams, error ->
                viewModelScope.launch { callback(streams, error) }
            }
            when (event.parent) {
                "streamed.pk" -> StreamedPkClient.fetchStreams(event.encParent, wrapCallback)
                "streamfree.top" -> StreamFreeClient.fetchStreams(event.encParent, event.id, wrapCallback)
                "codecloud.bd" -> CodeCloudClient.fetchStreams(event.encParent, wrapCallback)
                "fwatch.tv" -> FWatchClient.fetchStreams(event.id, event.encParent, wrapCallback)
                "ebox.live" -> EBoxClient.fetchStreams(event.encParent, wrapCallback)
                else -> SportzfyClient.fetchPlayback(event.parent, wrapCallback)
            }
        }
    }
}
