package com.campusauth.ui.component

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campusauth.ui.theme.*

enum class StatusLevel { Good, Warning, Error, Unknown, Info }

@Stable
data class StatusInfo(
    val level: StatusLevel,
    val title: String,
    val subtitle: String,
)

@Composable
fun StatusCard(
    title: String,
    status: StatusInfo,
    modifier: Modifier = Modifier,
) {
    val dotColor by animateColorAsState(
        targetValue = when (status.level) {
            StatusLevel.Good    -> GreenConnected
            StatusLevel.Warning -> OrangeCaptive
            StatusLevel.Error   -> RedError
            StatusLevel.Unknown -> GrayUnknown
            StatusLevel.Info    -> MaterialTheme.colorScheme.primary
        },
        animationSpec = spring(),
        label = "statusDot"
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    AnimatedContent(
                        targetState = status.title,
                        transitionSpec = {
                            fadeIn(spring()) togetherWith fadeOut(spring())
                        },
                        label = "statusTitle"
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    AnimatedVisibility(
                        visible = status.subtitle.isNotEmpty(),
                        enter = fadeIn(spring()) + expandVertically(spring()),
                        exit = fadeOut(spring()) + shrinkVertically(spring()),
                    ) {
                        Text(
                            text = status.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
