package com.shizington.tv.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Providers send either a bare YouTube id or a full URL. */
fun youTubeId(raw: String?): String? {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty()) return null
    Regex("""(?:v=|youtu\.be/|embed/)([A-Za-z0-9_-]{6,})""").find(v)?.let { return it.groupValues[1] }
    return v.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }
}

/** Opens the trailer in the YouTube app, falling back to any app that handles the web link. */
fun openTrailer(context: Context, raw: String?) {
    val id = youTubeId(raw) ?: return
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next way of opening it
        }
    }
    Toast.makeText(context, "Install the YouTube app to watch trailers", Toast.LENGTH_LONG).show()
}
