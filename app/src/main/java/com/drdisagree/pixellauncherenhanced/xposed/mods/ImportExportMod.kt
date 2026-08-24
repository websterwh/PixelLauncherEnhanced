package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
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
import java.io.File
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
 * 4. WRITE - rows go straight into the favorites SQLite database with our
 *    own SQLiteDatabase connection (largest launcher*.db in the launcher's
 *    databases directory - the name varies by grid, William's device uses
 *    launcher_4_by_6.db). The LauncherProvider route is NOT usable: on this
 *    build it throws "same process should call model directly" for any
 *    in-process call (confirmed on-device, Aug 2026) - it only serves
 *    external callers. Semantics: WIPE & REPLACE (a restore, matching
 *    William's manual db-swap workflow).
 * 4b. WIDGETS - the stock Export writes <appwidget> tags (provider
 *     component + spans + position). Restored for real: a fresh AppWidget id
 *     is allocated on the launcher's own host and bound to the provider
 *     in-process (the launcher holds BIND_APPWIDGET), then the row is
 *     written with that id. Widgets whose app isn't installed are skipped.
 *     Restored widgets come back at their DEFAULT configuration - per-widget
 *     settings are keyed to the old widget id inside the widget's own app
 *     and cannot survive an XML round-trip.
 * 5. RESTART - toast + root killall via the existing proxy so LoaderTask
 *    re-reads the table fresh.
 */
class ImportExportMod(context: Context) : ModPack(context) {

    companion object {
        private const val CONTAINER_DESKTOP = -100
        private const val CONTAINER_HOTSEAT = -101
        private const val ITEM_TYPE_APPLICATION = 0
        private const val ITEM_TYPE_FOLDER = 2
        private const val ITEM_TYPE_WIDGET = 4
        // AOSP Launcher3's LauncherAppWidgetHost id; read from the launcher's
        // own class at runtime when possible (see appWidgetHostId()).
        private const val FALLBACK_APPWIDGET_HOST_ID = 1024
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
                    if (success.widgetsRestored > 0) {
                        append(", ${success.widgetsRestored} widget(s)")
                    }
                    if (success.widgetsSkipped > 0) {
                        append(" (${success.widgetsSkipped} widget(s) skipped - app missing?)")
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

    private class ImportCounts(
        val apps: Int,
        val folders: Int,
        val widgetsRestored: Int,
        val widgetsSkipped: Int
    )

    private fun importLayout(uri: Uri): ImportCounts {
        // Parse EVERYTHING before touching the database, so a malformed file
        // can never leave the workspace half-wiped.
        val topItems = parseLayout(openStream(uri))

        var apps = 0
        var folders = 0
        var widgetsRestored = 0
        var widgetsSkipped = 0

        // Write DIRECTLY to the favorites SQLite database. The
        // LauncherProvider route is dead by design: on this build the
        // provider throws "same process should call model directly" for any
        // call made from inside the launcher process (confirmed on-device,
        // Aug 2026) - it only serves external callers. Opening the same
        // database file with our own connection is exactly what the proven
        // standalone-script approach did (see
        // plenhanced_import_export_notes.md); SQLite serializes concurrent
        // connections, and the post-import process restart makes the
        // launcher's in-memory model irrelevant anyway.
        val db = SQLiteDatabase.openDatabase(
            findFavoritesDbFile().absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            // NOTE: no "PRAGMA busy_timeout" here - executing it via
            // execSQL throws "Queries can be performed using SQLiteDatabase
            // query or rawQuery methods only" (PRAGMAs return a result row,
            // confirmed on-device Aug 2026). Android already applies its own
            // default busy timeout to every SQLiteDatabase connection.

            db.beginTransaction()
            try {
                // Wipe & replace: this is a restore, not a merge.
                db.delete("favorites", null, null)

                for (item in topItems) {
                    if (item.isWidget) {
                        if (insertWidget(db, item)) widgetsRestored++ else widgetsSkipped++
                        continue
                    }
                    insertItemTree(db, item, rank = 0)
                    if (item.itemType == ITEM_TYPE_FOLDER) folders++ else apps++
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }

        return ImportCounts(apps, folders, widgetsRestored, widgetsSkipped)
    }

    // The favorites database name varies by launcher grid (William's device
    // uses "launcher_4_by_6.db"), so discover it: the largest launcher*.db
    // in the launcher's own databases directory. -wal/-shm/-journal
    // sidecars don't match the ".db" suffix filter.
    private fun findFavoritesDbFile(): File {
        val databasesDir = File(mContext.applicationInfo.dataDir, "databases")
        val candidates = databasesDir.listFiles { file ->
            file.isFile && file.name.startsWith("launcher") && file.name.endsWith(".db")
        } ?: throw IllegalStateException("databases directory not found")
        return candidates.maxByOrNull { it.length() }
            ?: throw IllegalStateException("no launcher database file found")
    }

    private fun insertItemTree(db: SQLiteDatabase, item: ParsedItem, rank: Int) {
        val generatedId = db.insert("favorites", null, buildValues(item, rank))
        if (generatedId < 0) throw IllegalStateException("favorites insert failed")

        val children = item.children ?: return
        var nextRank = 0
        for (child in children) {
            if (child.isWidget) continue
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
                isWidget = false,
                children = null
            )
            insertItemTree(db, childWithContainer, rank = nextRank++)
        }
    }

    // Widget restore: allocate a real AppWidget id on the LAUNCHER's own
    // host id, bind the provider (we run inside the launcher process, which
    // holds BIND_APPWIDGET as the default home - no user consent prompt
    // needed), then write the favorites row with that id. Returns false when
    // the widget's app isn't installed or the provider refuses the bind -
    // the row is then simply skipped. NOTE: a restored widget comes back at
    // its DEFAULT configuration - per-widget settings live in the widget's
    // own app, keyed to the old (device-specific) widget id, and cannot
    // survive an XML round-trip.
    private fun insertWidget(db: SQLiteDatabase, item: ParsedItem): Boolean {
        val packageName = item.packageName ?: return false
        val className = item.className ?: return false
        val provider = ComponentName(packageName, className)

        val appWidgetManager = AppWidgetManager.getInstance(mContext)
        val host = AppWidgetHost(mContext, appWidgetHostId())
        val appWidgetId = host.allocateAppWidgetId()

        val bound = try {
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
        } catch (_: Throwable) {
            false
        }
        if (!bound) {
            host.deleteAppWidgetId(appWidgetId)
            return false
        }

        val values = buildValues(item, rank = 0).apply {
            put("appWidgetId", appWidgetId)
            put("appWidgetProvider", provider.flattenToString())
        }
        val inserted = db.insert("favorites", null, values) >= 0
        if (!inserted) host.deleteAppWidgetId(appWidgetId)
        return inserted
    }

    // The launcher's own host id, so its AppWidgetHost adopts our allocated
    // ids when it next starts listening.
    private fun appWidgetHostId(): Int =
        findClass("com.android.launcher3.LauncherAppWidgetHost")
            ?.getStaticFieldSilently("APPWIDGET_HOST_ID") as? Int
            ?: FALLBACK_APPWIDGET_HOST_ID

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
            if (item.itemType != ITEM_TYPE_APPLICATION ||
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

                    "appwidget" -> {
                        // Stock Export writes widgets as
                        // <appwidget container spanX spanY x y screen
                        //  className packageName> (confirmed from a real
                        // export, Aug 2026) - className is the provider's
                        // receiver class.
                        val item = ParsedItem(
                            itemType = ITEM_TYPE_WIDGET,
                            container = resolveContainer(attr(parser, "container"))
                                ?: CONTAINER_DESKTOP,
                            screen = attrInt(parser, "screen"),
                            cellX = attrInt(parser, "x"),
                            cellY = attrInt(parser, "y"),
                            spanX = attrInt(parser, "spanX", 1),
                            spanY = attrInt(parser, "spanY", 1),
                            title = null,
                            packageName = attr(parser, "packageName"),
                            className = attr(parser, "className"),
                            isWidget = true,
                            children = null
                        )
                        (folderStack.lastOrNull()?.children ?: topItems).add(item)
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
