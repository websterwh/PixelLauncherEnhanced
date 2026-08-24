package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.widget.Toast
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * William, Aug 2026: makes the launcher's hidden dev-options Import button
 * actually work. Stock behavior is confirmed broken (see
 * plenhanced_import_export_notes.md): the button launches ACTION_OPEN_DOCUMENT
 * via ProxyActivityStarter, but whatever receives the picker's result is a
 * stub - the picked file goes nowhere, zero writes happen. The Export button
 * works and is left untouched.
 *
 * How this fix works:
 * 1. GATE - every activity start in the launcher process funnels through
 *    Instrumentation.execStartActivity; when one carries ACTION_OPEN_DOCUMENT
 *    we raise a one-shot "import pending" flag.
 * 2. CATCH - the picker's result is delivered through EITHER the classic
 *    Activity.onActivityResult OR androidx's ActivityResultRegistry
 *    .dispatchResult (ActivityResultLauncher API), depending on what the
 *    stock code registered. Both are hooked; whichever fires first consumes
 *    the flag and takes the URI. This sidesteps the dead stock callback
 *    entirely instead of trying to find it.
 * 3. PARSE - the file is the stock Export format (<workspace> root holding
 *    <autoinstall> items and nested <folder> elements). Parsed directly with
 *    XmlPullParser - deliberately NOT routed through Google's
 *    AutoInstallsLayout, which is coupled to its resource-lookup path and
 *    dies on "Missing attribute screen" (per the notes).
 * 4. WRITE - rows go into the favorites table through the launcher's OWN
 *    LauncherProvider (contentResolver.insert against
 *    LauncherSettings.Favorites.CONTENT_URI), so the provider picks the
 *    correct database file itself and hands back generated _ids for folder
 *    children to reference. Semantics: WIPE & REPLACE (a restore, matching
 *    William's manual db-swap workflow). Widgets are skipped (their bindings
 *    are device-specific and cannot survive an XML round-trip).
 * 5. RESTART - toast + root killall via the existing proxy so LoaderTask
 *    re-reads the table fresh.
 */
class ImportExportMod(context: Context) : ModPack(context) {

    companion object {
        private const val CONTAINER_DESKTOP = -100
        private const val CONTAINER_HOTSEAT = -101
        private const val ITEM_TYPE_APPLICATION = 0
        private const val ITEM_TYPE_FOLDER = 2
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // One-shot flag: raised when the launcher starts a document picker,
    // consumed by whichever result-delivery path fires first.
    private var importPending = false

    override fun updatePrefs(vararg key: String) {
        // No settings for this mod.
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        // 1. Gate: any picker launch inside the launcher process.
        findClass("android.app.Instrumentation")
            ?.hookMethod("execStartActivity")
            ?.runBefore { param ->
                val intent = param.args.filterIsInstance<Intent>().firstOrNull()
                if (intent?.action == Intent.ACTION_OPEN_DOCUMENT) {
                    importPending = true
                }
            }

        // 2a. Classic result delivery (Activity.onActivityResult).
        findClass("android.app.Activity")
            ?.hookMethod("onActivityResult")
            ?.runBefore { param ->
                val resultCode = param.args.getOrNull(1) as? Int ?: return@runBefore
                val data = param.args.getOrNull(2) as? Intent
                handlePickedDocument(resultCode, data)
            }

        // 2b. ActivityResultLauncher delivery (androidx funnel). Skipped
        // silently if the class doesn't exist in this build.
        findClass("androidx.activity.result.ActivityResultRegistry", suppressError = true)
            ?.hookMethod("dispatchResult")
            ?.runBefore { param ->
                val resultCode = param.args.getOrNull(1) as? Int ?: return@runBefore
                val data = param.args.getOrNull(2) as? Intent
                handlePickedDocument(resultCode, data)
            }
    }

    private fun handlePickedDocument(resultCode: Int, data: Intent?) {
        if (!importPending) return
        importPending = false
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        Thread { runImport(uri) }.start()
    }

    private fun runImport(uri: Uri) {
        var counts: ImportCounts? = null
        var error: Throwable? = null
        try {
            counts = importLayout(uri)
        } catch (t: Throwable) {
            error = t
        }

        val success = counts
        val failure = error
        mainHandler.post {
            val message = if (success != null) {
                buildString {
                    append("Imported ")
                    if (success.folders > 0) append("${success.folders} folder(s) and ")
                    append("${success.apps} app(s)")
                    if (success.widgetsSkipped > 0) {
                        append(" (${success.widgetsSkipped} widget(s) skipped)")
                    }
                    append(" - restarting...")
                }
            } else {
                "Import failed: ${failure?.message ?: failure?.javaClass?.simpleName ?: "unknown error"}"
            }
            Toast.makeText(mContext, message, Toast.LENGTH_LONG).show()

            if (success != null) {
                enqueueProxyCommand { proxy ->
                    proxy.runCommand("killall ${mContext.packageName}")
                }
            }
        }
    }

    // Parsed representation of one row to insert. Folder children are held
    // by their parent until the parent's generated _id exists.
    private class ParsedItem(
        val itemType: Int,
        val container: Int,
        val screen: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int,
        val spanY: Int,
        val title: String?,
        val packageName: String?,
        val className: String?,
        val isWidget: Boolean,
        val children: MutableList<ParsedItem>? // non-null only for folders
    )

    private class ImportCounts(val apps: Int, val folders: Int, val widgetsSkipped: Int)

    private fun importLayout(uri: Uri): ImportCounts {
        val favoritesUri = getFavoritesUri()
            ?: throw IllegalStateException("LauncherSettings.Favorites.CONTENT_URI not found")

        // Parse EVERYTHING before touching the database, so a malformed file
        // can never leave the workspace half-wiped.
        val topItems = parseLayout(openStream(uri))
        val widgetsSkipped = countWidgets(topItems)

        var apps = 0
        var folders = 0

        // Wipe & replace: this is a restore, not a merge.
        mContext.contentResolver.delete(favoritesUri, null, null)

        for (item in topItems) {
            if (item.isWidget) continue
            insertItem(favoritesUri, item, rank = 0)
            if (item.itemType == ITEM_TYPE_FOLDER) folders++ else apps++
        }

        return ImportCounts(apps, folders, widgetsSkipped)
    }

    private fun insertItem(favoritesUri: Uri, item: ParsedItem, rank: Int) {
        val values = buildValues(item, rank)
        val insertedUri = mContext.contentResolver.insert(favoritesUri, values)
            ?: throw IllegalStateException("LauncherProvider refused an insert")
        val generatedId = insertedUri.lastPathSegment?.toLongOrNull()

        val children = item.children ?: return
        for ((index, child) in children.withIndex()) {
            if (child.isWidget) continue
            if (generatedId == null) {
                throw IllegalStateException("Insert returned no row id for a folder")
            }
            val childWithContainer = ParsedItem(
                itemType = child.itemType,
                container = generatedId.toInt(),
                screen = 0,
                cellX = 0,
                cellY = 0,
                spanX = child.spanX,
                spanY = child.spanY,
                title = child.title,
                packageName = child.packageName,
                className = child.className,
                isWidget = child.isWidget,
                children = null
            )
            insertItem(favoritesUri, childWithContainer, rank = index)
        }
    }

    private fun buildValues(item: ParsedItem, rank: Int): ContentValues = ContentValues().apply {
        put("itemType", item.itemType)
        put("container", item.container)
        put("screen", item.screen)
        put("cellX", item.cellX)
        put("cellY", item.cellY)
        put("spanX", item.spanX)
        put("spanY", item.spanY)
        put("rank", rank)
        put("title", item.title)
        put(
            "intent",
            if (item.itemType == ITEM_TYPE_FOLDER ||
                item.packageName == null || item.className == null
            ) {
                null
            } else {
                buildIntentString(item.packageName, item.className)
            }
        )
        put("appWidgetId", -1)
        put("appWidgetProvider", null as String?)
        put("icon", null as ByteArray?)
        put("modified", 0)
        put("restored", 0)
        put("profileId", 0)
        put("options", 0)
        put("appWidgetSource", -1)
    }

    // Exact serialization format from the notes (matches what the stock
    // Export writes into the favorites table's intent column). Inner classes
    // use '$' URL-encoded as %24.
    private fun buildIntentString(packageName: String, className: String): String =
        "#Intent;action=android.intent.action.MAIN;" +
            "category=android.intent.category.LAUNCHER;" +
            "launchFlags=0x10200000;" +
            "component=$packageName/${className.replace("$", "%24")};end"

    private fun getFavoritesUri(): Uri? =
        findClass("com.android.launcher3.LauncherSettings\$Favorites")
            ?.getStaticFieldSilently("CONTENT_URI") as? Uri

    private fun openStream(uri: Uri): InputStream =
        mContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the picked file")

    private fun parseLayout(stream: InputStream): MutableList<ParsedItem> {
        val topItems = mutableListOf<ParsedItem>()
        val folderStack = ArrayDeque<ParsedItem>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "folder" -> {
                        val folder = ParsedItem(
                            itemType = ITEM_TYPE_FOLDER,
                            container = resolveContainer(attr(parser, "container"))
                                ?: CONTAINER_DESKTOP,
                            screen = attrInt(parser, "screen"),
                            cellX = attrInt(parser, "x"),
                            cellY = attrInt(parser, "y"),
                            spanX = attrInt(parser, "spanX", 1),
                            spanY = attrInt(parser, "spanY", 1),
                            title = attr(parser, "title"),
                            packageName = null,
                            className = null,
                            isWidget = false,
                            children = mutableListOf()
                        )
                        (folderStack.lastOrNull()?.children ?: topItems).add(folder)
                        folderStack.add(folder)
                    }

                    "autoinstall" -> {
                        val packageName = attr(parser, "packageName")
                        val className = attr(parser, "className")
                        val appWidgetProvider = attr(parser, "appWidgetProvider")
                        val isWidget = appWidgetProvider != null ||
                            (packageName == null && className == null)

                        val enclosingFolder = folderStack.lastOrNull()
                        val item = if (enclosingFolder != null) {
                            // Folder child: position comes from the folder's
                            // rank, not from x/y/screen attributes.
                            ParsedItem(
                                itemType = ITEM_TYPE_APPLICATION,
                                container = 0, // replaced with the folder's id at insert
                                screen = 0,
                                cellX = 0,
                                cellY = 0,
                                spanX = attrInt(parser, "spanX", 1),
                                spanY = attrInt(parser, "spanY", 1),
                                title = attr(parser, "title"),
                                packageName = packageName,
                                className = className,
                                isWidget = isWidget,
                                children = null
                            )
                        } else {
                            val container = resolveContainer(attr(parser, "container"))
                                ?: CONTAINER_DESKTOP
                            val isHotseat = container == CONTAINER_HOTSEAT
                            // Export writes hotseat position as rank (no
                            // x/y/screen); the favorites table stores the
                            // hotseat slot in screen (cellX mirrors it).
                            val hotseatSlot = attrInt(parser, "rank")
                            ParsedItem(
                                itemType = ITEM_TYPE_APPLICATION,
                                container = container,
                                screen = if (isHotseat) hotseatSlot else attrInt(parser, "screen"),
                                cellX = if (isHotseat) hotseatSlot else attrInt(parser, "x"),
                                cellY = if (isHotseat) 0 else attrInt(parser, "y"),
                                spanX = attrInt(parser, "spanX", 1),
                                spanY = attrInt(parser, "spanY", 1),
                                title = attr(parser, "title"),
                                packageName = packageName,
                                className = className,
                                isWidget = isWidget,
                                children = null
                            )
                        }
                        (enclosingFolder?.children ?: topItems).add(item)
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "folder" &&
                    folderStack.isNotEmpty()
                ) {
                    folderStack.removeLast()
                }
            }
            event = parser.next()
        }

        return topItems
    }

    private fun countWidgets(items: List<ParsedItem>): Int {
        var count = items.count { it.isWidget }
        for (item in items) {
            if (item.children != null) count += countWidgets(item.children)
        }
        return count
    }

    private fun resolveContainer(value: String?): Int? = when (value) {
        null -> null
        "desktop", "workspace" -> CONTAINER_DESKTOP
        "hotseat" -> CONTAINER_HOTSEAT
        else -> value.toIntOrNull()
    }

    private fun attr(parser: XmlPullParser, name: String): String? =
        parser.getAttributeValue(null, name)

    private fun attrInt(parser: XmlPullParser, name: String, fallback: Int = 0): Int =
        attr(parser, name)?.toIntOrNull() ?: fallback
}
