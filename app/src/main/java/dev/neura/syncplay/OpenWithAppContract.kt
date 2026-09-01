package dev.neura.syncplay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import java.util.Locale

internal data class OpenWithAppRequest(
    val mimeTypes: List<String>,
    val chooserTitle: String,
    val action: String = Intent.ACTION_OPEN_DOCUMENT,
    val component: ComponentName? = null,
)

internal data class PickedContent(
    val uri: Uri,
    val grantFlags: Int,
)

internal data class FilePickerTarget(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val action: String,
)

/**
 * Opens the file manager selected in our own app chooser. An explicit component
 * avoids Android routing ACTION_OPEN_DOCUMENT/GET_CONTENT straight to its preferred
 * picker before the user can choose MiX Explorer or another SMB-capable app.
 */
internal class OpenWithAppContract : ActivityResultContract<OpenWithAppRequest, PickedContent?>() {
    override fun createIntent(context: Context, input: OpenWithAppRequest): Intent {
        val mimeTypes = input.mimeTypes
            .map { it.lowercase(Locale.ROOT) }
            .distinct()
            .ifEmpty { listOf("*/*") }
        val target = Intent(input.action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes.single() else "*/*"
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (input.action == Intent.ACTION_OPEN_DOCUMENT) {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            component = input.component
        }
        if (input.component != null) return target

        return Intent.createChooser(target, input.chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedContent? {
        if (resultCode != Activity.RESULT_OK) return null
        val result = intent ?: return null
        val uri = result.data ?: result.clipData?.getItemAt(0)?.uri ?: return null
        val relevantFlags = result.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        return PickedContent(uri, relevantFlags)
    }
}

/** Returns every visible app capable of returning one of the requested stream types. */
internal fun findFilePickerTargets(
    context: Context,
    mimeTypes: List<String>,
): List<FilePickerTarget> {
    val packageManager = context.packageManager
    val normalizedMimes = mimeTypes
        .map { it.lowercase(Locale.ROOT) }
        .distinct()
        .ifEmpty { listOf("*/*") }
    val targets = linkedMapOf<ComponentName, FilePickerTarget>()

    // Prefer OPEN_DOCUMENT for long-lived grants; use GET_CONTENT as a compatibility fallback.
    listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT).forEach { action ->
        normalizedMimes.forEach { mimeType ->
            val query = Intent(action).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
            }
            @Suppress("DEPRECATION")
            val matches = packageManager.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
            matches.forEach { resolved ->
                val info = resolved.activityInfo ?: return@forEach
                val component = ComponentName(info.packageName, info.name)
                if (component !in targets) {
                    targets[component] = FilePickerTarget(
                        label = resolved.loadLabel(packageManager).toString().ifBlank { info.packageName },
                        packageName = info.packageName,
                        component = component,
                        action = action,
                    )
                }
            }
        }
    }

    return targets.values.sortedWith(
        compareBy<FilePickerTarget> { it.label.lowercase(Locale.ROOT) }
            .thenBy { it.packageName },
    )
}
