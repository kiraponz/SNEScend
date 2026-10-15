package com.kira3.ametrine.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.delay
import java.util.Locale

/** The frames per second the game runs at, in the top end corner, read twice a second. */
@Composable
fun FpsCounter(core: EmulatorCore) {
    var fps by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(core) {
        while (true) {
            fps = core.fps
            delay(500)
        }
    }
    Text(
        String.format(Locale.ROOT, "%.1f FPS", fps),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .wrapContentSize(Alignment.TopEnd)
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
