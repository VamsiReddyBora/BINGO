package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.presentation.common.PlayerAvatar

/**
 * Head-to-Head PvP Scorecard (Option A).
 * Renders live player vs opponent line completion progress, mini progress pips,
 * and high-tension match point indicators in the lower space below the board.
 */
@Composable
fun HeadToHeadScorecard(
    playerName: String,
    playerAvatarUrl: String? = null,
    playerUsername: String? = null,
    playerLines: Int,
    opponentName: String,
    opponentAvatarUrl: String? = null,
    opponentUsername: String? = null,
    opponentLines: Int,
    targetLines: Int = 5,
    isMyTurn: Boolean,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors

    val isPlayerMatchPoint = playerLines >= (targetLines - 1) && playerLines < targetLines
    val isOpponentMatchPoint = opponentLines >= (targetLines - 1) && opponentLines < targetLines

    val infiniteTransition = rememberInfiniteTransition(label = "scorecardPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "matchPointPulse"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = tokens.surface,
        border = BorderStroke(
            width = if (isPlayerMatchPoint || isOpponentMatchPoint) 1.5.dp else 1.dp,
            color = when {
                isPlayerMatchPoint -> tokens.accentBrand
                isOpponentMatchPoint -> tokens.accentOpponent
                else -> tokens.surfaceBorder
            }
        ),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Match Point Alert banner if either player is 1 line away
            if (isPlayerMatchPoint || isOpponentMatchPoint) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isPlayerMatchPoint) tokens.cellPlayerPickBg else tokens.cellOpponentPickBg,
                    modifier = Modifier
                        .scale(pulseScale)
                        .padding(bottom = 6.dp)
                ) {
                    Text(
                        text = if (isPlayerMatchPoint && isOpponentMatchPoint) {
                            "⚡ DUAL MATCH POINT! Next line wins!"
                        } else if (isPlayerMatchPoint) {
                            "🔥 MATCH POINT! You need 1 more line!"
                        } else {
                            "⚠️ WATCH OUT! $opponentName needs 1 more line!"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isPlayerMatchPoint) tokens.accentBrand else tokens.accentOpponent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // ── Left: Local Player Card ──
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        PlayerAvatar(
                            avatarPathOrUri = playerAvatarUrl,
                            displayName = playerName,
                            username = playerUsername,
                            size = 36.dp
                        )
                        if (isMyTurn) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(CircleShape)
                                    .background(tokens.accentBrand)
                                    .border(1.5.dp, tokens.surface, CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = playerName.ifBlank { "You" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$playerLines / $targetLines Lines",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.accentBrand
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        // Progress Pips
                        LineProgressPips(
                            completed = playerLines,
                            total = targetLines,
                            activeColor = tokens.accentBrand
                        )
                    }
                }

                // ── Center: VS Badge ──
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(tokens.backgroundSecondary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "VS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = tokens.cellNeutralText.copy(alpha = 0.6f)
                    )
                }

                // ── Right: Opponent Player Card ──
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = opponentName.ifBlank { "Opponent" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$opponentLines / $targetLines Lines",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.accentOpponent
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        // Progress Pips
                        LineProgressPips(
                            completed = opponentLines,
                            total = targetLines,
                            activeColor = tokens.accentOpponent
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box {
                        PlayerAvatar(
                            avatarPathOrUri = opponentAvatarUrl,
                            displayName = opponentName,
                            username = opponentUsername,
                            size = 36.dp
                        )
                        if (!isMyTurn) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(CircleShape)
                                    .background(tokens.accentOpponent)
                                    .border(1.5.dp, tokens.surface, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineProgressPips(
    completed: Int,
    total: Int,
    activeColor: Color
) {
    val tokens = BingoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until total) {
            val isFilled = i < completed
            val pipColor by animateColorAsState(
                targetValue = if (isFilled) activeColor else tokens.surfaceBorder,
                label = "pipColor_$i"
            )
            Box(
                modifier = Modifier
                    .size(width = 8.dp, height = 5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(pipColor)
            )
        }
    }
}
