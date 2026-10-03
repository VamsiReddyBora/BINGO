@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.bingo.multiplayer.presentation.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.engine.AiDifficulty
import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.GameInvite
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.settings.SettingsScreen
import com.bingo.multiplayer.presentation.social.DashboardAndFriendsScreen
import kotlinx.coroutines.launch

private data class NavPillItem(
    val title: String,
    val icon: ImageVector
)

/**
 * Main landing container holding Home, Dashboard & Friends, and Settings.
 * Features a swipeable 3-page HorizontalPager synced with a sleek floating bottom pill.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainContainerScreen(
    authRepository: AuthRepository,
    friendsRepository: FriendsRepository,
    onPlayAi: (difficulty: AiDifficulty) -> Unit,
    onPlayOnline: () -> Unit,
    onPlayNearbyNetwork: () -> Unit,
    onSignedOut: () -> Unit,
    onInviteFriendToMatch: (Friend) -> Unit,
    onAcceptInviteToMatch: (GameInvite) -> Unit,
    onOpenDeveloperNote: () -> Unit = {},
    onRejoinMatch: ((com.bingo.multiplayer.domain.network.OngoingMatchData) -> Unit)? = null,
    initialPage: Int = 0
) {
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 3 })
    val tokens = BingoTheme.colors

    // System Back Handler: return to Home (page 0) if currently on Dashboard or Settings
    BackHandler(enabled = pagerState.currentPage != 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    val authState by authRepository.authState.collectAsState()
    val userProfile = (authState as? AuthState.Authenticated)?.user ?: UserProfile(
        uid = "guest",
        displayName = "Player"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> {
                    MainMenuScreen(
                        authRepository = authRepository,
                        friendsRepository = friendsRepository,
                        onNavigateToSettings = {
                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                        },
                        onPlayAi = onPlayAi,
                        onPlayOnline = onPlayOnline,
                        onPlayNearbyNetwork = onPlayNearbyNetwork,
                        onOpenDeveloperNote = onOpenDeveloperNote,
                        onRejoinMatch = onRejoinMatch
                    )
                }
                1 -> {
                    DashboardAndFriendsScreen(
                        user = userProfile,
                        authRepository = authRepository,
                        friendsRepository = friendsRepository,
                        onInviteFriendToMatch = onInviteFriendToMatch,
                        onAcceptInviteToMatch = onAcceptInviteToMatch,
                        onBack = {
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        }
                    )
                }
                2 -> {
                    SettingsScreen(
                        authRepository = authRepository,
                        onBack = {
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        },
                        onSignedOut = onSignedOut,
                        onNavigateToDashboard = {
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        friendsRepository = friendsRepository
                    )
                }
            }
        }

        // Floating Bottom Navigation Pill (Home -> Dashboard -> Settings)
        FloatingBottomNavPill(
            selectedTab = pagerState.currentPage,
            onTabSelected = { targetIndex ->
                coroutineScope.launch {
                    pagerState.animateScrollToPage(targetIndex)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun FloatingBottomNavPill(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors
    val items = listOf(
        NavPillItem(title = "Home", icon = Icons.Default.Home),
        NavPillItem(title = "Dashboard", icon = Icons.Default.Insights),
        NavPillItem(title = "Settings", icon = Icons.Default.Settings)
    )

    Surface(
        modifier = modifier
            .shadow(
                elevation = if (tokens.isDark) 16.dp else 10.dp,
                shape = RoundedCornerShape(32.dp),
                spotColor = if (tokens.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.12f)
            ),
        shape = RoundedCornerShape(32.dp),
        color = if (tokens.isDark) Color(0xFF141414) else Color(0xFFFFFFFF),
        border = BorderStroke(
            width = 1.dp,
            color = if (tokens.isDark) Color(0xFF282828) else Color(0xFFE2E8F0)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = (selectedTab == index)

                val activeBg = if (tokens.isDark) Color(0xFF262626) else tokens.accentBrand.copy(alpha = 0.12f)
                val activeContentColor = if (tokens.isDark) Color.White else tokens.accentBrand
                val inactiveContentColor = tokens.textMuted

                val tabBg by animateColorAsState(
                    targetValue = if (isSelected) activeBg else Color.Transparent,
                    animationSpec = tween(180),
                    label = "pillTabBg_$index"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) activeContentColor else inactiveContentColor,
                    animationSpec = tween(180),
                    label = "pillContentColor_$index"
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .background(tabBg)
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            tint = contentColor,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}
