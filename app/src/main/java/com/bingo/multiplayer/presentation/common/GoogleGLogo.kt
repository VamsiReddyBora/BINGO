package com.bingo.multiplayer.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bingo.multiplayer.R

/**
 * Official multicolored Google "G" logo using standard VectorDrawable asset.
 */
@Composable
fun GoogleGLogo(
    size: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.ic_google_logo),
        contentDescription = "Google",
        modifier = modifier.size(size)
    )
}
