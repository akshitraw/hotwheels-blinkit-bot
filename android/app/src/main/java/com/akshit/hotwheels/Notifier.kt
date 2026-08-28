package com.akshit.hotwheels

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

object Notifier {

    const val CHANNEL_ALERTS = "restock"
    const val CHANNEL_SERVICE = "watcher"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "Restock alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fires when a Hot Wheels car comes back in stock"
                enableVibration(true)
            }
        )
        // Low importance so the permanent "watching" notice stays silent and
        // collapsed rather than nagging.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Watcher status", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "The ongoing notice shown while watching" }
        )
    }

    private fun fetchBitmap(url: String): Bitmap? {
        if (url.isBlank()) return null
        return runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
            }
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private fun openProduct(context: Context, url: String, id: Int): PendingIntent =
        PendingIntent.getActivity(
            context, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** One rich notification per restocked car. Tapping opens Blinkit. */
    fun alert(context: Context, product: Product) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val detail = buildString {
            if (product.price.isNotEmpty()) append(product.price)
            if (product.mrp.isNotEmpty() && product.mrp != product.price) append("  was ${product.mrp}")
            if (product.unit.isNotEmpty()) append("  ·  ${product.unit}")
            append("  ·  ")
            append(if (product.qty in 1..3) "only ${product.qty} left" else "${product.qty} in stock")
        }

        val builder = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(product.name)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(openProduct(context, product.url, product.id.hashCode()))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)

        fetchBitmap(product.image)?.let { bmp ->
            builder.setLargeIcon(bmp)
            builder.setStyle(
                Notification.BigPictureStyle()
                    .bigPicture(bmp)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(detail)
            )
        }

        nm.notify(product.id.hashCode(), builder.build())
    }

    fun serviceNotification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Watching Blinkit for Hot Wheels")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
