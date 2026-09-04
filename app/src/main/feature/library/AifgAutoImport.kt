
package com.winlator.cmod.feature.library

import android.content.Context
import android.net.Uri
import com.winlator.cmod.runtime.display.aifg.AifgFrameGen
import java.io.File

object AifgAutoImport {
    const val RESULT_IMPORTED = 1
    const val RESULT_FAILED = 5

    class Outcome(val result: Int, val sourceName: String)

    fun importFrom(context: Context, uri: Uri): Outcome {
        val status = AifgFrameGen.installFrom(context, uri)
        if (status != AifgFrameGen.STATUS_OK) return Outcome(RESULT_FAILED, "")
        return Outcome(RESULT_IMPORTED, uri.lastPathSegment?.substringAfterLast('/').orEmpty())
    }

    fun importFrom(context: Context, dll: File): Outcome {
        val name = dll.parentFile?.name?.takeIf { it.isNotBlank() } ?: dll.name
        val status = AifgFrameGen.installFrom(context, dll)
        if (status != AifgFrameGen.STATUS_OK) return Outcome(RESULT_FAILED, name)
        return Outcome(RESULT_IMPORTED, name)
    }
}
