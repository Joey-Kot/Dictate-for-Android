package com.joeykot.dictate.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import com.caverock.androidsvg.SVG
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PromptConfig
import java.io.File
import java.util.UUID

/** Shared by the prompt editor and the floating action menu. */
object PromptIconStore {
    data class Builtin(val id: String, val labelRes: Int, val fallbackLabel: String, val drawableRes: Int) {
        val label: String get() = AppStrings.get(labelRes, fallbackLabel)
    }
    data class ImportedIcon(val name: String, val drawable: Drawable)

    val builtins = listOf(
        Builtin("chat", R.string.prompt_icon_chat, "Chat", R.drawable.prompt_icon_chat),
        Builtin("messages", R.string.prompt_icon_messages, "Messages", R.drawable.prompt_icon_messages),
        Builtin("translate", R.string.prompt_icon_translate, "Translate", R.drawable.prompt_icon_translate),
        Builtin("eye", R.string.prompt_icon_eye, "Eye", R.drawable.prompt_icon_eye),
        Builtin("feather", R.string.prompt_icon_feather, "Feather", R.drawable.prompt_icon_feather),
        Builtin("document", R.string.prompt_icon_document, "Document", R.drawable.prompt_icon_document),
        Builtin("shield", R.string.prompt_icon_shield, "Shield", R.drawable.prompt_icon_shield),
        Builtin("pie_chart", R.string.prompt_icon_pie_chart, "Pie chart", R.drawable.prompt_icon_pie_chart),
        Builtin("tags", R.string.prompt_icon_tags, "Tags", R.drawable.prompt_icon_tags),
        Builtin("text_card", R.string.prompt_icon_text_card, "Text card", R.drawable.prompt_icon_text_card),
        Builtin("box", R.string.prompt_icon_box, "Box", R.drawable.prompt_icon_box),
        Builtin("flask", R.string.prompt_icon_flask, "Flask", R.drawable.prompt_icon_flask),
        Builtin("globe", R.string.prompt_icon_globe, "Globe", R.drawable.prompt_icon_globe),
        Builtin("cookie", R.string.prompt_icon_cookie, "Cookie", R.drawable.prompt_icon_cookie),
        Builtin("window", R.string.prompt_icon_window, "Window", R.drawable.prompt_icon_window),
        Builtin("plug", R.string.prompt_icon_plug, "Plug", R.drawable.prompt_icon_plug),
        Builtin("settings", R.string.prompt_icon_settings, "Settings", R.drawable.prompt_icon_settings),
        Builtin("layers", R.string.prompt_icon_layers, "Layers", R.drawable.prompt_icon_layers),
        Builtin("key", R.string.prompt_icon_key, "Key", R.drawable.prompt_icon_key),
        Builtin("network", R.string.prompt_icon_network, "Network", R.drawable.prompt_icon_network),
        Builtin("hierarchy", R.string.prompt_icon_hierarchy, "Hierarchy", R.drawable.prompt_icon_hierarchy),
        Builtin("bar_chart", R.string.prompt_icon_bar_chart, "Bar chart", R.drawable.prompt_icon_bar_chart),
        Builtin("microphone", R.string.prompt_icon_microphone, "Microphone", R.drawable.prompt_icon_microphone),
        Builtin("fingerprint", R.string.prompt_icon_fingerprint, "Fingerprint", R.drawable.prompt_icon_fingerprint),
        Builtin("layout", R.string.prompt_icon_layout, "Layout", R.drawable.prompt_icon_layout),
    )

    fun load(context: Context, prompt: PromptConfig): Drawable {
        prompt.customIcon?.let { name ->
            runCatching { decode(context, file(context, name)) }.getOrNull()?.let { return it }
        }
        val builtin = builtins.firstOrNull { it.id == prompt.icon } ?: builtins.first()
        return requireNotNull(context.getDrawable(builtin.drawableRes)).mutate()
    }

    /** Imports only after validation, leaving the currently saved icon untouched. */
    fun import(context: Context, uri: Uri): String = importDecoded(context, uri).name

    /** Returns the validated preview so callers do not decode the same file again on the UI thread. */
    fun importDecoded(context: Context, uri: Uri): ImportedIcon {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val data = stream.readBoundedBytes(MAX_FILE_BYTES + 1)
            require(data.size <= MAX_FILE_BYTES) { context.getString(R.string.prompt_icon_file_too_large) }
            data
        } ?: throw IllegalArgumentException(context.getString(R.string.prompt_icon_unreadable))
        require(bytes.isNotEmpty()) { context.getString(R.string.prompt_icon_empty) }
        val extension = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() &&
                bytes.copyOfRange(1, 4).contentEquals("PNG".toByteArray()) -> "png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.toString(Charsets.UTF_8).contains(Regex("<svg(?:\\s|>)", RegexOption.IGNORE_CASE)) -> "svg"
            else -> throw IllegalArgumentException(context.getString(R.string.prompt_icon_unsupported))
        }
        val directory = File(context.filesDir, "icons").apply { mkdirs() }
        val existingSize = directory.listFiles()?.sumOf { it.length() } ?: 0L
        require(existingSize + bytes.size <= MAX_TOTAL_BYTES) { context.getString(R.string.prompt_icons_too_large) }
        val destination = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            destination.writeBytes(bytes)
            return ImportedIcon(destination.name, decode(context, destination))
        } catch (error: Exception) {
            destination.delete()
            throw IllegalArgumentException(context.getString(R.string.prompt_icon_read_error, error.message ?: context.getString(R.string.prompt_icon_invalid_image)), error)
        }
    }

    fun deleteIfUnused(context: Context, name: String?, prompts: List<PromptConfig>) {
        if (name != null && prompts.none { it.customIcon == name }) {
            runCatching { file(context, name).delete() }
        }
    }

    private fun file(context: Context, name: String): File {
        require(SAFE_FILENAME.matches(name)) { context.getString(R.string.prompt_icon_invalid_filename) }
        return File(File(context.filesDir, "icons"), name)
    }

    private fun decode(context: Context, source: File): Drawable {
        require(source.isFile && source.length() <= MAX_FILE_BYTES) { context.getString(R.string.prompt_icon_missing_or_large) }
        if (source.extension.equals("svg", ignoreCase = true)) {
            val svg = source.inputStream().use { SVG.getFromInputStream(it) }
            val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(64)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPicture(svg.renderToPicture(size, size))
            return BitmapDrawable(context.resources, bitmap)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { context.getString(R.string.prompt_icon_invalid_image) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) {
                inSampleSize = if (inSampleSize <= 0) 2 else inSampleSize * 2
            }
        }
        val bitmap = BitmapFactory.decodeFile(source.path, options)
            ?: throw IllegalArgumentException(context.getString(R.string.prompt_icon_decode_failed))
        return BitmapDrawable(context.resources, bitmap)
    }

    // InputStream.readBytes() has no size limit; bound reads before allocating the next chunk.
    private fun java.io.InputStream.readBoundedBytes(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private val SAFE_FILENAME = Regex("[A-Za-z0-9_-]{1,128}\\.(?:png|jpg|jpeg|svg)")
    private const val MAX_FILE_BYTES = 1_048_576
    private const val MAX_TOTAL_BYTES = 8_388_608L
}
