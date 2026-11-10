package com.spektralab.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.R

/**
 * Compose hand-off screen shown while the native engine is being prepared.
 * Android's system splash has a very small branding slot, so the product copy
 * lives here where its width, hierarchy, and line breaks are under our control.
 */
@Composable
internal fun LaunchSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_theia_mark),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(78.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Theia",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "让记忆拥有\n真实的颗粒感",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 21.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = "MEMORIES IN EVERY GRAIN",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.1.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
