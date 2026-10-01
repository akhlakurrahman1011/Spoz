package com.raen.spoz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.raen.spoz.ui.main.MainScreen

import com.raen.spoz.ui.player.PlayerScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(onItemClick = { navKey -> backStack.add(navKey) }, modifier = Modifier.fillMaxSize())
        }
        entry<Player> { playerKey ->
          PlayerScreen(
              streams = playerKey.streams,
              startIndex = playerKey.startIndex,
              eventName = playerKey.eventName,
              onBack = { backStack.removeLastOrNull() }
          )
        }
      },
  )
}
