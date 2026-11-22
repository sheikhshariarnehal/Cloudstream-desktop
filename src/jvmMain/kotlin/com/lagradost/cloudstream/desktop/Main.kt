package com.lagradost.cloudstream.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream.desktop.plugins.DesktopPluginLoader
import com.lagradost.cloudstream.desktop.storage.DesktopDataStore
import com.lagradost.cloudstream.desktop.ui.navigation.DesktopScreen
import com.lagradost.cloudstream.desktop.ui.navigation.DesktopSidebar
import com.lagradost.cloudstream.desktop.ui.screens.home.HomeScreen
import com.lagradost.cloudstream.desktop.ui.screens.library.LibraryScreen
import com.lagradost.cloudstream.desktop.ui.screens.player.PlayerScreen
import com.lagradost.cloudstream.desktop.ui.screens.plugins.PluginsScreen
import com.lagradost.cloudstream.desktop.ui.screens.result.ResultScreen
import com.lagradost.cloudstream.desktop.ui.screens.search.SearchScreen
import com.lagradost.cloudstream.desktop.ui.screens.settings.SettingsScreen
import com.lagradost.cloudstream.desktop.ui.theme.CloudStreamDesktopTheme

fun main() {
    // Setup Coil 3 image loader with OkHttp, User-Agent, and TrustAll SSL for poster URLs
    com.lagradost.cloudstream.desktop.utils.setupDesktopImageLoader()

    // Register native BDIX extensions and load cached plugins
    DesktopPluginLoader.registerNehalProviders()
    DesktopPluginLoader.loadAllLocalPlugins()

    application {
        // Load local desktop settings
        val settings = remember { DesktopDataStore.currentSettings }
        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
        var windowedPlacement by remember { mutableStateOf(WindowPlacement.Floating) }

        Window(
            onCloseRequest = ::exitApplication,
            title = "CloudStream Windows Desktop",
            icon = painterResource("icon.png"),
            state = windowState,
            undecorated = true
        ) {
            val isFullscreen = windowState.placement == WindowPlacement.Fullscreen

            // Register CloudStream library providers and cached JAR plugins on window composition
            LaunchedEffect(Unit) {
                DesktopPluginLoader.loadAllLocalPlugins()
            }

            CloudStreamDesktopTheme(darkTheme = settings.isDarkMode) {
                var currentScreen by remember { mutableStateOf<DesktopScreen>(DesktopScreen.Home) }
                val screenHistory = remember { mutableStateListOf<DesktopScreen>() }
                var playerOwnsFullscreen by remember { mutableStateOf(false) }
                var escapePressed by remember { mutableStateOf(false) }

                val toggleFullscreen = {
                    if (windowState.placement == WindowPlacement.Fullscreen) {
                        windowState.placement = windowedPlacement
                        playerOwnsFullscreen = false
                    } else {
                        windowedPlacement = windowState.placement
                        playerOwnsFullscreen = currentScreen is DesktopScreen.Player
                        windowState.placement = WindowPlacement.Fullscreen
                    }
                }

                fun restorePlayerWindowIfNeeded(target: DesktopScreen) {
                    if (currentScreen is DesktopScreen.Player && target !is DesktopScreen.Player && playerOwnsFullscreen) {
                        if (windowState.placement == WindowPlacement.Fullscreen) toggleFullscreen()
                        playerOwnsFullscreen = false
                    }
                }

                fun navigateTo(screen: DesktopScreen, replaceCurrent: Boolean = false) {
                    restorePlayerWindowIfNeeded(screen)
                    if (replaceCurrent) {
                        currentScreen = screen
                    } else {
                        screenHistory.add(currentScreen)
                        currentScreen = screen
                    }
                }

                fun navigateBack() {
                    restorePlayerWindowIfNeeded(screenHistory.lastOrNull() ?: DesktopScreen.Home)
                    if (screenHistory.isNotEmpty()) {
                        currentScreen = screenHistory.removeLast()
                    } else {
                        currentScreen = DesktopScreen.Home
                    }
                }

                val windowFocusRequester = remember { FocusRequester() }
                var windowHasFocus by remember { mutableStateOf(false) }
                var playerWindowShortcutsBlocked by remember { mutableStateOf(false) }

                LaunchedEffect(isFullscreen) {
                    withFrameNanos { }
                    // Recover focus from removed window controls without taking it from surviving text fields.
                    if (currentScreen !is DesktopScreen.Player && !windowHasFocus) windowFocusRequester.requestFocus()
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(windowFocusRequester)
                        .onFocusChanged { windowHasFocus = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.F11) {
                                if (event.type == KeyEventType.KeyUp &&
                                    (currentScreen !is DesktopScreen.Player || !playerWindowShortcutsBlocked)) toggleFullscreen()
                                true
                            } else if (event.key == Key.Escape && currentScreen !is DesktopScreen.Player) {
                                if (event.type == KeyEventType.KeyDown) escapePressed = true
                                if (event.type == KeyEventType.KeyUp && escapePressed) {
                                    escapePressed = false
                                    if (isFullscreen) toggleFullscreen()
                                    else if (currentScreen !is DesktopScreen.Home) navigateBack()
                                }
                                true
                            } else if (event.type == KeyEventType.KeyDown) {
                                val isCtrl = event.isCtrlPressed
                                val isAlt = event.isAltPressed

                                when {
                                    (isCtrl && event.key == Key.F) || (event.key == Key.Slash) -> {
                                        if (currentScreen !is DesktopScreen.Search) {
                                            navigateTo(DesktopScreen.Search())
                                        }
                                        true
                                    }
                                    (isAlt || isCtrl) && event.key == Key.One -> {
                                        navigateTo(DesktopScreen.Home)
                                        true
                                    }
                                    (isAlt || isCtrl) && event.key == Key.Two -> {
                                        navigateTo(DesktopScreen.Search())
                                        true
                                    }
                                    (isAlt || isCtrl) && event.key == Key.Three -> {
                                        navigateTo(DesktopScreen.Library)
                                        true
                                    }
                                    (isAlt || isCtrl) && event.key == Key.Four -> {
                                        navigateTo(DesktopScreen.Plugins)
                                        true
                                    }
                                    (isAlt || isCtrl) && event.key == Key.Five -> {
                                        navigateTo(DesktopScreen.Settings)
                                        true
                                    }
                                    (isAlt && event.key == Key.DirectionLeft) ||
                                    (currentScreen !is DesktopScreen.Player && currentScreen !is DesktopScreen.Home && event.key == Key.Backspace) -> {
                                        navigateBack()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .focusable()
                ) {
                    // Custom dark title bar for windowed mode (hidden in Fullscreen)
                    if (!isFullscreen) {
                        CustomTitleBar(
                            title = "CloudStream Windows Desktop",
                            windowState = windowState,
                            onClose = ::exitApplication
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Sidebar visible for non-player screens
                            if (currentScreen !is DesktopScreen.Player) {
                                DesktopSidebar(
                                    currentScreen = currentScreen,
                                    onNavigate = { navigateTo(it) }
                                )
                            }

                            // Main screen view canvas
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                when (val screen = currentScreen) {
                                    is DesktopScreen.Home -> HomeScreen(
                                        onNavigate = { navigateTo(it) }
                                    )

                                    is DesktopScreen.Search -> SearchScreen(
                                        initialQuery = screen.query,
                                        onNavigate = { navigateTo(it) }
                                    )

                                    is DesktopScreen.Result -> ResultScreen(
                                        url = screen.url,
                                        apiName = screen.apiName,
                                        searchResponse = screen.searchResponse,
                                        autoPlay = screen.autoPlay,
                                        onNavigate = { target, replace -> navigateTo(target, replace) },
                                        onBack = { navigateBack() }
                                    )

                                    is DesktopScreen.Player -> PlayerScreen(
                                        mediaUrl = screen.mediaUrl,
                                        title = screen.title,
                                        episodeName = screen.episodeName,
                                        posterUrl = screen.posterUrl,
                                        headers = screen.headers,
                                        subtitles = screen.subtitles,
                                        sources = screen.sources,
                                        loadResponse = screen.loadResponse,
                                        currentEpisode = screen.currentEpisode,
                                        apiName = screen.apiName,
                                        isFullscreen = isFullscreen,
                                        onToggleFullscreen = toggleFullscreen,
                                        onWindowShortcutsBlockedChange = { playerWindowShortcutsBlocked = it },
                                        onBack = { navigateBack() }
                                    )

                                    is DesktopScreen.Plugins -> PluginsScreen()

                                    is DesktopScreen.Library -> LibraryScreen(
                                        onNavigate = { navigateTo(it) }
                                    )

                                    is DesktopScreen.Settings -> SettingsScreen()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowScope.CustomTitleBar(
    title: String,
    windowState: WindowState,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        color = Color(0xFF121212),
        border = BorderStroke(1.dp, Color(0x1AFFFFFF))
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Logo + App Title
            WindowDraggableArea(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource("icon.png"),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        title,
                        color = Color(0xFFB0B0B0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Right: Windows Caption Control Buttons
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Minimize
                Win11CaptionButton(
                    icon = Icons.Default.Minimize,
                    description = "Minimize",
                    onClick = { windowState.isMinimized = true }
                )
                // Maximize / Restore
                Win11CaptionButton(
                    icon = if (windowState.placement == WindowPlacement.Maximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                    description = if (windowState.placement == WindowPlacement.Maximized) "Restore window" else "Maximize window",
                    onClick = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    }
                )
                // Close
                Win11CaptionButton(
                    icon = Icons.Default.Close,
                    description = "Close",
                    isClose = true,
                    onClick = onClose
                )
            }
        }
    }
}

@Composable
private fun Win11CaptionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    isClose: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        if (hovered) {
            if (isClose) Color(0xFFE81123) else Color(0xFF333333)
        } else Color.Transparent,
        animationSpec = tween(120)
    )
    val iconColor by animateColorAsState(
        if (isClose && hovered) Color.White else if (hovered) Color.White else Color(0xFFB0B0B0),
        animationSpec = tween(120)
    )

    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = iconColor,
            modifier = Modifier.size(12.dp)
        )
    }
}
