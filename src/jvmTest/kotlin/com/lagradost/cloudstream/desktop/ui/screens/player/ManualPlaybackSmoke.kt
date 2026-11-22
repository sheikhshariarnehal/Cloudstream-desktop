package com.lagradost.cloudstream.desktop.ui.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream.desktop.ui.navigation.StreamSourceInfo
import com.lagradost.cloudstream.desktop.ui.navigation.SubtitleTrackInfo
import com.lagradost.cloudstream.desktop.ui.theme.CloudStreamDesktopTheme
import java.io.File

fun main(args: Array<String>) {
    require(args.size == 1) { "Pass the playback fixture directory and set -Duser.home to a test profile." }
    val directory = File(args.single())
    fun fixture(name: String): String {
        val file = directory.resolve(name)
        require(file.isFile) { "Missing playback fixture: $file" }
        return file.toPath().toAbsolutePath().toUri().toASCIIString()
    }

    val sources = listOf(
        StreamSourceInfo("Local primary", fixture("primary.avi"), 180),
        StreamSourceInfo("Local mirror", fixture("mirror.avi"), 180),
    )
    val subtitles = listOf(
        SubtitleTrackInfo(fixture("caption-a.srt"), "Caption A"),
        SubtitleTrackInfo(fixture("caption-b.srt"), "Caption B"),
    )

    application {
        val state = rememberWindowState(width = 1040.dp, height = 680.dp)
        var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
        Window(onCloseRequest = ::exitApplication, state = state, title = "CloudStream Playback Smoke Test") {
            CloudStreamDesktopTheme {
                PlayerScreen(
                    mediaUrl = sources.first().url,
                    title = "Local playback fixture",
                    sources = sources,
                    subtitles = subtitles,
                    isFullscreen = state.placement == WindowPlacement.Fullscreen,
                    onToggleFullscreen = {
                        if (state.placement == WindowPlacement.Fullscreen) {
                            state.placement = previousPlacement
                        } else {
                            previousPlacement = state.placement
                            state.placement = WindowPlacement.Fullscreen
                        }
                    },
                    onBack = ::exitApplication,
                )
            }
        }
    }
}
