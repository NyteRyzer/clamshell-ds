package com.clamshellds

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.clamshellds.data.Paths
import com.clamshellds.data.Settings
import com.clamshellds.input.Action
import com.clamshellds.input.InputMapper
import com.clamshellds.ui.DsiTheme
import com.clamshellds.ui.DualDisplay
import java.io.File

/** DSi "System Settings"-style options list, fully usable with a D-pad. */
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var input: InputMapper
    private lateinit var list: ListView
    private val rows = ArrayList<Row>()

    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val title: String, val value: () -> String, val onClick: () -> Unit) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        input = InputMapper(settings)
        DualDisplay.immersive(window)

        val accent = DsiTheme.accent(settings.favoriteColor)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DsiTheme.BG_BOTTOM)
        }
        root.addView(TextView(this).apply {
            text = "System Settings"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = DsiTheme.FONT_BOLD
            setPadding(32, 20, 32, 20)
            setBackgroundColor(accent)
        })
        list = ListView(this).apply {
            divider = null
            itemsCanFocus = false
            selector = android.graphics.drawable.ColorDrawable(DsiTheme.lighten(accent, 0.6f))
            setDrawSelectorOnTop(false)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        buildRows()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> (rows[pos] as? Row.Item)?.onClick?.invoke() }
        list.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        DualDisplay.immersive(window)
        adapter.notifyDataSetChanged()
    }

    private fun refresh() = adapter.notifyDataSetChanged()

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun buildRows() {
        rows.clear()
        rows += Row.Header("Console")
        rows += Row.Item("Console mode", { if (settings.consoleType == 1) "Nintendo DSi" else "Nintendo DS" }) {
            choose("Console mode", listOf("Nintendo DS (no files needed)", "Nintendo DSi (needs your dumps)"), settings.consoleType) {
                if (it == 1 && !Paths.dsiFilesPresent()) showDsiFiles(missingWarning = true)
                settings.consoleType = it
            }
        }
        rows += Row.Item("Start games", { if (settings.directBoot) "Directly" else "Through the system menu" }) {
            choose("Start games", listOf("Directly (fastest)", "Through the system menu (DSi/DS boot screen)"), if (settings.directBoot) 0 else 1) {
                settings.directBoot = it == 0
            }
        }
        rows += Row.Item("BIOS / DSi files", { if (Paths.dsiFilesPresent()) "DSi files found" else "Tap for details" }) { showDsiFiles(false) }
        rows += Row.Item("Use DS BIOS & firmware files", { onOff(settings.useExternalDSBios) }) {
            settings.useExternalDSBios = !settings.useExternalDSBios; refresh()
        }

        rows += Row.Header("Screens")
        rows += Row.Item("Screen mode", { listOf("Automatic", "Two screens", "One screen")[settings.displayMode] }) {
            choose("Screen mode", listOf("Automatic (use the second screen if there is one)", "Two screens", "One screen"), settings.displayMode) {
                settings.displayMode = it
            }
        }
        rows += Row.Item("Ask which screen is on top", { "Next time the menu opens" }) {
            settings.topScreenConfirmed = false; refresh()
        }
        rows += Row.Item("Swap physical screens", { onOff(settings.swapDisplays) }) {
            settings.swapDisplays = !settings.swapDisplays; settings.topScreenConfirmed = true; refresh()
        }
        rows += Row.Item("One-screen layout", { listOf("Automatic", "Stacked", "Side by side")[settings.singleLayout] }) {
            choose("One-screen layout", listOf("Automatic", "Stacked (top above bottom)", "Side by side"), settings.singleLayout) {
                settings.singleLayout = it
            }
        }
        rows += Row.Item("Screen filter", { Settings.FILTERS[settings.filter - 1] }) {
            choose("Screen filter", Settings.FILTERS, settings.filter - 1) { settings.filter = it + 1 }
        }
        rows += Row.Item("Show FPS", { onOff(settings.showFps) }) { settings.showFps = !settings.showFps; refresh() }

        rows += Row.Header("Performance")
        rows += Row.Item("JIT recompiler", { onOff(settings.jit) }) { settings.jit = !settings.jit; refresh() }
        rows += Row.Item("JIT fast memory", { onOff(settings.fastMem) }) { settings.fastMem = !settings.fastMem; refresh() }
        rows += Row.Item("Safe mode (most compatible)", { "Tap to apply" }) {
            settings.enableSafeMode(); refresh()
            dialog().setTitle("Safe mode").setMessage("JIT, fast memory and threaded 3D are now off, and DS mode is selected. " +
                "Games will run slower but should start. Turn options back on one at a time to find what your device dislikes.")
                .setPositiveButton("OK", null).show()
        }
        rows += Row.Item("Last crash report", { if (CrashReporter.reportFile().isFile) "View" else "None" }) {
            val f = CrashReporter.reportFile()
            dialog().setTitle("Last crash report")
                .setMessage(if (f.isFile) f.readText().take(8000) else "No crash has been recorded.")
                .setPositiveButton("OK", null).show()
        }
        rows += Row.Item("Threaded 3D rendering", { onOff(settings.threaded3D) }) { settings.threaded3D = !settings.threaded3D; refresh() }
        rows += Row.Item("Audio sync", { onOff(settings.audioSync) }) { settings.audioSync = !settings.audioSync; refresh() }
        rows += Row.Item("Game sound", { onOff(settings.audioEnabled) }) { settings.audioEnabled = !settings.audioEnabled; refresh() }
        rows += Row.Item("Volume", { "${settings.volume}%" }) {
            val opts = listOf(25, 50, 75, 100)
            choose("Volume", opts.map { "$it%" }, opts.indexOf(settings.volume).coerceAtLeast(0)) { settings.volume = opts[it] }
        }
        rows += Row.Item("Menu sounds", { onOff(settings.menuSounds) }) { settings.menuSounds = !settings.menuSounds; refresh() }
        rows += Row.Item("Resume where you left off", { onOff(settings.autoResume) }) {
            settings.autoResume = !settings.autoResume; refresh()
        }

        rows += Row.Header("Controls")
        rows += Row.Item("Remap buttons", { "Press each button in turn" }) { remapWizard(0) }
        Action.values().forEach { a ->
            rows += Row.Item("   " + a.label, { input.keyFor(a)?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") } ?: "—" }) {
                captureKey(a) { refresh() }
            }
        }
        rows += Row.Item("Reset controls to defaults", { "" }) { input.resetToDefaults(); refresh() }

        rows += Row.Header("Profile")
        rows += Row.Item("Nickname", { settings.username }) {
            editText("Nickname", settings.username, 10) { settings.username = it.ifBlank { "Player" } }
        }
        rows += Row.Item("Favorite color", { DsiTheme.FAVORITE_NAMES[settings.favoriteColor] }) {
            choose("Favorite color", DsiTheme.FAVORITE_NAMES, settings.favoriteColor) {
                settings.favoriteColor = it
                recreate()
            }
        }
        rows += Row.Item("Birthday", { "${settings.birthMonth}/${settings.birthDay}" }) {
            choose("Birth month", (1..12).map { it.toString() }, settings.birthMonth - 1) { m ->
                settings.birthMonth = m + 1
                choose("Birth day", (1..31).map { it.toString() }, settings.birthDay - 1) { d -> settings.birthDay = d + 1 }
            }
        }
        rows += Row.Item("Language", { Settings.LANGUAGES[settings.language] }) {
            choose("Language", Settings.LANGUAGES, settings.language) { settings.language = it }
        }

        rows += Row.Header("Library")
        rows += Row.Item("Game folders", { "${settings.romFolders.size + 1} folders + auto-detect" }) { manageFolders() }

        rows += Row.Header("System")
        rows += Row.Item("Use Clamshell DS as home screen", { onOff(isHomeEnabled()) }) { toggleHome() }
        rows += Row.Item("About", { "Version " + versionName() }) { about() }
    }

    // --- Dialog helpers ------------------------------------------------------------

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

    private fun choose(title: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        dialog().setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), current) { d, which -> d.dismiss(); onPick(which); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editText(title: String, value: String, maxLen: Int, onDone: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
            setSelection(text.length)
        }
        dialog().setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> onDone(et.text.toString().trim()); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun captureKey(action: Action, then: () -> Unit) {
        val dlg = dialog().setTitle(action.label)
            .setMessage("Press the button to use for “${action.label}”.")
            .setNegativeButton("Skip", null)
            .create()
        dlg.setOnKeyListener { d, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                input.assign(action, keyCode)
                d.dismiss()
                then()
            }
            true
        }
        dlg.setOnCancelListener { then() }
        dlg.show()
    }

    private fun remapWizard(index: Int) {
        val actions = Action.values()
        if (index >= actions.size) { refresh(); return }
        val dlg = dialog().setTitle("Remap buttons (${index + 1}/${actions.size})")
            .setMessage("Press the button for “${actions[index].label}”.")
            .setNegativeButton("Skip") { _, _ -> remapWizard(index + 1) }
            .setNeutralButton("Stop", null)
            .create()
        dlg.setOnKeyListener { d, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                input.assign(actions[index], keyCode)
                d.dismiss()
                remapWizard(index + 1)
            }
            true
        }
        dlg.show()
    }

    private fun showDsiFiles(missingWarning: Boolean) {
        val files = listOf(
            "bios9.bin" to "DS ARM9 BIOS",
            "bios7.bin" to "DS ARM7 BIOS",
            "dsi_bios9.bin" to "DSi ARM9i BIOS",
            "dsi_bios7.bin" to "DSi ARM7i BIOS",
            "dsi_firmware.bin" to "DSi firmware",
            "dsi_nand.bin" to "DSi NAND",
            "firmware.bin" to "DS firmware (optional)",
        )
        val sb = StringBuilder()
        if (missingWarning) sb.append("Some DSi files are missing, so DSi mode won't start yet.\n\n")
        sb.append("Folder: ${Paths.bios.absolutePath}\n\n")
        files.forEach { (name, desc) ->
            sb.append(if (File(Paths.bios, name).isFile) "✔ " else "✘ ").append(name).append(" – ").append(desc).append('\n')
        }
        sb.append("\nDS mode needs none of these. For the full DSi experience (including the real DSi Menu) " +
            "dump them from your own DSi with GodMode9i – see dsi.cfw.guide. A README is in the folder.")
        dialog().setTitle("BIOS / DSi files").setMessage(sb.toString()).setPositiveButton("OK", null).show()
    }

    // --- Folders ------------------------------------------------------------------

    private fun manageFolders() {
        val folders = settings.romFolders.toMutableList()
        val labels = ArrayList<String>()
        labels += "＋ Add a folder…"
        labels += "＋ Type a folder path…"
        labels += "• ${Paths.roms.absolutePath} (always)"
        labels += "• Everything else on internal storage and SD cards (auto)"
        labels += "ⓘ Where were my games found?"
        folders.forEach { labels += "✘ Remove $it" }
        dialog().setTitle("Game folders")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> runCatching {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
                    }.onFailure { typeFolder() }
                    which == 1 -> typeFolder()
                    which == 4 -> showScanReport()
                    which >= 5 -> {
                        folders.removeAt(which - 5)
                        settings.romFolders = folders.toSet()
                        refresh()
                    }
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showScanReport() {
        val lib = com.clamshellds.data.RomLibrary(this, settings)
        dialog().setTitle("Game search")
            .setMessage(lib.lastReport())
            .setPositiveButton("Search again") { _, _ ->
                Thread {
                    runCatching { lib.scan() }
                    runOnUiThread { if (!isFinishing) showScanReport() }
                }.start()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun typeFolder() {
        editText("Folder path", Environment.getExternalStorageDirectory().absolutePath + "/", 200) { path ->
            if (path.isNotBlank()) settings.romFolders = settings.romFolders + path
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            treeUriToPath(uri)?.let {
                settings.romFolders = settings.romFolders + it
                refresh()
            }
        }
    }

    /** content://…/tree/primary:Roms/NDS -> /storage/emulated/0/Roms/NDS */
    private fun treeUriToPath(uri: Uri): String? = runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        if (rel.isEmpty()) base else "$base/$rel"
    }.getOrNull()

    // --- Home screen ---------------------------------------------------------------

    private fun homeComponent() = ComponentName(this, "$packageName.HomeAlias")

    private fun isHomeEnabled() =
        packageManager.getComponentEnabledSetting(homeComponent()) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun toggleHome() {
        val enable = !isHomeEnabled()
        packageManager.setComponentEnabledSetting(
            homeComponent(),
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        refresh()
        if (enable) {
            dialog().setTitle("Home screen")
                .setMessage("Choose Clamshell DS as your Home app so your handheld boots straight into the DSi-style menu.")
                .setPositiveButton("Open Home settings") { _, _ ->
                    runCatching { startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")

    private fun about() {
        dialog().setTitle("Clamshell DS")
            .setMessage(
                "A DS / DSi emulator front-end for dual-screen Android handhelds.\n\n" +
                    "Emulation core: melonDS (melonds.kuribo64.net), GNU GPL v3.\n" +
                    "Clamshell DS is free software under the GNU GPL v3.\n\n" +
                    "Not affiliated with Nintendo. Use your own legally dumped games and system files."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // --- Adapter --------------------------------------------------------------------

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Item
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {
                is Row.Header -> {
                    val tv = (convertView as? TextView) ?: TextView(this@SettingsActivity).apply {
                        textSize = 13f
                        typeface = DsiTheme.FONT_BOLD
                        setTextColor(DsiTheme.darken(DsiTheme.accent(settings.favoriteColor), 0.1f))
                        setPadding(32, 28, 32, 8)
                    }
                    tv.text = row.title.uppercase()
                    tv
                }
                is Row.Item -> {
                    val layout = (convertView as? LinearLayout) ?: LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(32, 22, 32, 22)
                        setBackgroundColor(Color.WHITE)
                        addView(TextView(context).apply { textSize = 16f; setTextColor(DsiTheme.TEXT) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(context).apply { textSize = 14f; setTextColor(DsiTheme.TEXT_LIGHT); gravity = Gravity.END })
                    }
                    (layout.getChildAt(0) as TextView).text = row.title
                    (layout.getChildAt(1) as TextView).text = row.value()
                    layout
                }
            }
        }
    }

    companion object {
        private const val REQ_FOLDER = 42
    }
}
