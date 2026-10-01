package com.raen.spoz.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.raen.spoz.Player
import com.raen.spoz.network.SportzfyClient

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val streams by viewModel.playbackStreams.collectAsStateWithLifecycle()
    val loadingStreams by viewModel.playbackLoading.collectAsStateWithLifecycle()
    val errorStreams by viewModel.playbackError.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val firstItemFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

    // Automatically navigate to player when streams are loaded successfully
    LaunchedEffect(streams, loadingStreams) {
        if (!loadingStreams && !streams.isNullOrEmpty()) {
            val list = streams ?: emptyList()
            val streamItems = list.map {
                com.raen.spoz.StreamItem(
                    url = it.streamUrl,
                    type = it.streamType,
                    drmKid = it.drmKid,
                    drmKey = it.drmKey,
                    label = it.label
                )
            }
            val currentEventName = viewModel.activeEventName.value
            viewModel.clearPlayback()
            onItemClick(Player(streams = streamItems, startIndex = 0, eventName = currentEventName))
        }
    }

    // Show error toast if fetching fails
    LaunchedEffect(errorStreams) {
        if (errorStreams != null) {
            android.widget.Toast.makeText(context, errorStreams, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearPlayback()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // Sleek dark mode slate-900 background
    ) {
        when (val state = uiState) {
            MainScreenUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFEF4444))
                }
            }
            is MainScreenUiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Error loading streams: ${state.message}",
                        color = Color.Red,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.refreshEvents() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("Retry", color = Color.White)
                    }
                }
            }
            is MainScreenUiState.Success -> {
                val activeSource by viewModel.selectedSource.collectAsStateWithLifecycle()
                val activeTabFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(activeSource) {
                    if (state.filteredEvents.isNotEmpty()) {
                        try {
                            firstItemFocusRequester.requestFocus()
                        } catch (e: Exception) {
                            // ignore if focus framework not fully ready on layout pass
                        }
                    }
                }
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

                if (isPortrait) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val activeSource by viewModel.selectedSource.collectAsStateWithLifecycle()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF070B14))
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Source:", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f).padding(start = 12.dp)
                            ) {
                                listOf(1, 2, 3, 4, 5, 6).forEach { src ->
                                    val isSelected = activeSource == src
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) Color(0xFFEF4444) else Color.White.copy(alpha = 0.05f))
                                            .border(1.dp, if (isSelected) Color(0xFFEF4444) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                            .clickable { viewModel.setSource(src) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(src.toString(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A))
                                .padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.categories) { category ->
                                val isSelected = state.selectedCategory == category
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (isSelected) Color(0xFFEF4444) else Color.White.copy(alpha = 0.08f))
                                        .clickable { viewModel.selectCategory(category) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(category, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            if (state.filteredEvents.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No Live or Upcoming matches at the moment", color = Color.Gray)
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(1),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    itemsIndexed(state.filteredEvents) { index, event ->
                                        EventCard(
                                            event = event,
                                            onClick = { viewModel.fetchPlayback(event) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left navigation sidebar for TV Categories selection
                        LazyColumn(
                            modifier = Modifier
                                .width(180.dp)
                                .fillMaxHeight()
                                .background(Color(0xFF070B14))
                                .padding(vertical = 16.dp, horizontal = 8.dp)
                                .focusGroup(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                val activeSource by viewModel.selectedSource.collectAsStateWithLifecycle()

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp, start = 4.dp, end = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(1, 2, 3, 4, 5, 6).forEach { src ->
                                        val isSelected = activeSource == src
                                        var isFocused by remember { mutableStateOf(false) }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 2.dp)
                                                .aspectRatio(1.2f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    when {
                                                        isFocused -> Color.White.copy(alpha = 0.2f)
                                                        isSelected -> Color(0xFFEF4444)
                                                        else -> Color.Transparent
                                                    }
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isSelected) Color(0xFFEF4444) else Color.White.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .onFocusChanged { isFocused = it.isFocused }
                                                .clickable {
                                                    viewModel.setSource(src)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = src.toString(),
                                                color = if (isSelected || isFocused) Color.White else Color.Gray,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.wrapContentHeight(Alignment.CenterVertically)
                                            )
                                        }
                                    }
                                }
                            }

                            items(state.categories) { category ->
                                val isSel = state.selectedCategory == category
                                CategoryChip(
                                    category = category,
                                    isSelected = isSel,
                                    onSelect = { viewModel.selectCategory(category) },
                                    modifier = if (isSel) Modifier.focusRequester(activeTabFocusRequester) else Modifier
                                )
                            }
                        }

                        // Right Grid area showing sport events
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "${state.selectedCategory} Matches",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            if (state.filteredEvents.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No Live or Upcoming matches at the moment", color = Color.Gray)
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    modifier = Modifier.fillMaxSize().focusGroup(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(state.filteredEvents) { index, event ->
                                         val baseModifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                                         EventCard(
                                             event = event,
                                             onClick = {
                                                 viewModel.fetchPlayback(event)
                                             },
                                             modifier = baseModifier.focusProperties { left = activeTabFocusRequester }
                                         )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen overlay during stream loading
        if (loadingStreams) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(enabled = true) {}, // Block clicks to background
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFEF4444))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading live servers...", color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun CategoryChip(
    category: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val backgroundColor = when {
        isSelected -> Color(0xFFEF4444)
        isFocused -> Color(0xFF334155)
        else -> Color.Transparent
    }

    val borderColor = when {
        isSelected && isFocused -> Color.White
        isFocused -> Color(0xFFEF4444)
        else -> Color.Transparent
    }

    val textColor = if (isSelected) Color.White else Color(0xFF94A3B8)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(
                border = BorderStroke(
                    1.dp,
                    borderColor
                ),
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { 
                isFocused = it.isFocused
                if (it.isFocused) {
                    onSelect()
                }
            }
            .focusable()
            .clickable { onSelect() }
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Text(
            text = category,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun EventCard(
    event: SportzfyClient.Event,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val now = remember { System.currentTimeMillis() + SportzfyClient.serverTimeOffset }
    val isLive = remember(event.startsAt, event.isLive) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val eventTime = try {
            val cleanDate = event.startsAt.substringBefore(".")
            sdf.parse(cleanDate)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
        event.isLive || (eventTime > 0L && eventTime <= now + (5 * 60 * 1000) && eventTime >= now - (3 * 60 * 60 * 1000))
    }
    val isRecent = remember(event.startsAt, isLive) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val eventTime = try {
            val cleanDate = event.startsAt.substringBefore(".")
            sdf.parse(cleanDate)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
        !isLive && eventTime > 0L && eventTime < now - (3 * 60 * 60 * 1000)
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                border = BorderStroke(
                    2.dp,
                    if (isFocused) Color(0xFFEF4444) else Color.Transparent
                ),
                shape = RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header chips
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = event.sport.uppercase(),
                    color = Color(0xFFEF4444),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isLive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Red)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "LIVE", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (isRecent) {
                    Text(text = "RECENT", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(text = "UPCOMING", color = Color.Gray, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Team VS team names representation
            Text(
                text = "${event.teamAName} VS ${event.teamBName}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = event.league,
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )

            val relativeTimeText = remember(event.startsAt, now) {
                try {
                    val sdfSource = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                    val eventTime = sdfSource.parse(event.startsAt.substringBefore("."))?.time ?: 0L
                    if (eventTime > 0L) {
                        // Calculate Today / Tomorrow relative check
                        val calEvent = java.util.Calendar.getInstance().apply { timeInMillis = eventTime }
                        val calNow = java.util.Calendar.getInstance().apply { timeInMillis = now }

                        val isToday = calEvent.get(java.util.Calendar.YEAR) == calNow.get(java.util.Calendar.YEAR) &&
                                      calEvent.get(java.util.Calendar.DAY_OF_YEAR) == calNow.get(java.util.Calendar.DAY_OF_YEAR)

                        val calTomorrow = java.util.Calendar.getInstance().apply {
                            timeInMillis = now
                            add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }
                        val isTomorrow = calEvent.get(java.util.Calendar.YEAR) == calTomorrow.get(java.util.Calendar.YEAR) &&
                                         calEvent.get(java.util.Calendar.DAY_OF_YEAR) == calTomorrow.get(java.util.Calendar.DAY_OF_YEAR)

                        val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        val timeStr = timeFormat.format(java.util.Date(eventTime))

                        val dateLine = when {
                            isToday -> "Today, $timeStr"
                            isTomorrow -> "Tomorrow, $timeStr"
                            else -> {
                                val dateFormat = java.text.SimpleDateFormat("dd MMM, h:mm a", java.util.Locale.getDefault())
                                dateFormat.format(java.util.Date(eventTime))
                            }
                        }

                        // Calculate difference
                        val diffMs = eventTime - now
                        val absDiffMs = Math.abs(diffMs)
                        val diffHours = absDiffMs / (60 * 60 * 1000)
                        val diffMins = (absDiffMs % (60 * 60 * 1000)) / (60 * 1000)
                        val diffDays = diffHours / 24

                        val relativeLine = when {
                            diffMs > 0 -> {
                                when {
                                    diffDays > 0 -> "Starts in $diffDays d ${diffHours % 24} h"
                                    diffHours > 0 -> "Starts in $diffHours h $diffMins m"
                                    else -> "Starts in $diffMins m"
                                }
                            }
                            else -> {
                                val isLiveMatch = absDiffMs <= 3 * 60 * 60 * 1000
                                val prefix = if (isLiveMatch) "Started" else "Finished"
                                when {
                                    diffDays > 0 -> "$prefix $diffDays d ${diffHours % 24} h ago"
                                    diffHours > 0 -> "$prefix $diffHours h $diffMins m ago"
                                    else -> "$prefix $diffMins m ago"
                                }
                            }
                        }
                        Pair(dateLine, relativeLine)
                    } else {
                        Pair("", "")
                    }
                } catch (e: Exception) {
                    Pair("", "")
                }
            }

            if (relativeTimeText.first.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = relativeTimeText.first,
                    color = Color(0xFF38BDF8), // Sky blue accent
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = relativeTimeText.second,
                    color = Color(0xFF94A3B8), // Gray subtitle
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun ServerButton(
    label: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color(0xFFEF4444) else Color(0xFF334155))
            .border(
                border = BorderStroke(
                    1.dp,
                    if (isFocused) Color.White else Color.Transparent
                ),
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}
