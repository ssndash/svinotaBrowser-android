package com.svinota.Browser

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class BrowserWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Используем стандартную тему Glance, которая точно есть
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(8.dp)
                // surfaceVariant обычно есть в любой версии Material3 Glance
                .background(ImageProvider(R.drawable.widget_background))
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_search),
                contentDescription = null,
                modifier = GlanceModifier.padding(start = 16.dp, end = 8.dp).size(20.dp)
            )

            Text(
                text = "Search... or not",
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(fontSize = 16.sp)
            )

            // Кнопка New Tab
            Box(
                modifier = GlanceModifier
                    .padding(4.dp)
                    .background(ImageProvider(R.drawable.widget_button_bg))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_new_tab),
                        contentDescription = null,
                        modifier = GlanceModifier.size(18.dp)
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(text = "New Tab", style = TextStyle(fontSize = 14.sp))
                }
            }
        }
    }
}

class BrowserWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BrowserWidget()
}