package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType

/**
 * Utilities for building human-friendly notification text/previews.
 */
object NotificationTextUtils {
    /**
     * Build a user-friendly notification preview for private messages, especially attachments.
     * Plain words, as in Messages notifications (no emoji glyphs):
     * - Image: "Photo"
     * - Audio: "Voice message"
     * - File (pdf): "PDF · file.pdf"
     * - Text: original message content
     */
    fun buildPrivateMessagePreview(message: BitchatMessage): String {
        return try {
            when (message.type) {
                BitchatMessageType.Image -> "Photo"
                BitchatMessageType.Audio -> "Voice message"
                BitchatMessageType.File -> {
                    // Show just the filename (not the full path)
                    val name = try { java.io.File(message.content).name } catch (_: Exception) { null }
                    if (!name.isNullOrBlank()) {
                        val lower = name.lowercase()
                        val kind = when {
                            lower.endsWith(".pdf") -> "PDF"
                            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "Archive"
                            lower.endsWith(".doc") || lower.endsWith(".docx") -> "Document"
                            lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "Spreadsheet"
                            lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "Presentation"
                            else -> "File"
                        }
                        "$kind · $name"
                    } else {
                        "File"
                    }
                }
                else -> message.content
            }
        } catch (_: Exception) {
            // Fallback to original content on any error
            message.content
        }
    }
}
