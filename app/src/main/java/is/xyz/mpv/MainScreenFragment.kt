package `is`.xyz.mpv

import `is`.xyz.filepicker.DocumentPickerFragment
import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import `is`.xyz.mpv.preferences.PreferenceActivity
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {
    private lateinit var binding: FragmentMainScreenBinding

    private lateinit var documentTreeOpener: ActivityResultLauncher<Uri?>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var playerLauncher: ActivityResultLauncher<Intent>

    private var firstRun = false
    private var returningFromPlayer = false

    private var prev = ""
    private var prevData: String? = null
    private var lastPath = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstRun = savedInstanceState == null

        documentTreeOpener = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { root ->
                requireContext().contentResolver.takePersistableUriPermission(
                    root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveChoice("doc", root.toString())

                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.DOC_PICKER)
                i.putExtra("root", root.toString())
                filePickerLauncher.launch(i)
            }
        }
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            it.data?.getStringExtra("last_path")?.let { path ->
                lastPath = path
            }
            it.data?.getStringExtra("path")?.let { path ->
                playFile(path)
            }
        }
        playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // we don't care about the result but remember that we've been here
            returningFromPlayer = true
            Log.v(TAG, "returned from player ($it)")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentMainScreenBinding.bind(view)

        Utils.handleInsetsAsPadding(binding.root)

        binding.docBtn.setOnClickListener {
            try {
                documentTreeOpener.launch(null)
            } catch (e: ActivityNotFoundException) {
                // Android TV doesn't come with a document picker and certain versions just throw
                // instead of handling this gracefully
                binding.docBtn.isEnabled = false
            }
        }
        binding.urlBtn.setOnClickListener {
            saveChoice("url")
            val helper = Utils.OpenUrlDialog(requireContext())
            with (helper) {
                builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                    playFile(helper.text)
                }
                builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                create().show()
            }
        }
        binding.filepickerBtn.setOnClickListener {
            saveChoice("file")
            val i = Intent(context, FilePickerActivity::class.java)
            i.putExtra("skip", FilePickerActivity.FILE_PICKER)
            if (lastPath != "")
                i.putExtra("default_path", lastPath)
            filePickerLauncher.launch(i)
        }
        binding.settingsBtn.setOnClickListener {
            saveChoice("") // will reset
            startActivity(Intent(context, PreferenceActivity::class.java))
        }
        binding.aniCliBtn.setOnClickListener {
            saveChoice("anicli")
            showAniCliDialog()
        }

        if (BuildConfig.DEBUG) {
            binding.settingsBtn.setOnLongClickListener { showDebugMenu(); true }
        }

        onConfigurationChanged(view.resources.configuration)
    }

    private fun showAniCliDialog() {
        val contentView = layoutInflater.inflate(R.layout.dialog_ani_cli_search, null)
        val queryEditText = contentView.findViewById<EditText>(R.id.queryEditText)
        val episodeEditText = contentView.findViewById<EditText>(R.id.episodeEditText)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ani_cli_dialog_title)
            .setMessage(R.string.ani_cli_dialog_subtitle)
            .setView(contentView)
            .setPositiveButton(R.string.ani_cli_action_run) { _, _ ->
                val query = queryEditText.text?.toString()?.trim().orEmpty()
                if (query.isBlank()) {
                    showAniCliError(getString(R.string.ani_cli_error_empty_query))
                    return@setPositiveButton
                }
                runAniCli(query, episodeEditText.text?.toString()?.trim().orEmpty())
            }
            .setNeutralButton(R.string.ani_cli_action_help) { _, _ ->
                showAniCliHelp()
            }
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun runAniCli(query: String, episode: String) {
        val command = buildString {
            append("ani-cli --default-player mpv ")
            if (episode.isNotBlank()) {
                append("-e ")
                append(quoteForShell(episode))
                append(' ')
            }
            append(quoteForShell(query))
        }

        val termuxIntent = Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        }

        try {
            startActivity(termuxIntent)
        } catch (_: ActivityNotFoundException) {
            val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("ani-cli", command))
            showAniCliError(getString(R.string.ani_cli_error_missing_termux, command))
        }
    }

    private fun quoteForShell(raw: String): String {
        return "'${raw.replace("'", "'\\''")}'"
    }

    private fun showAniCliHelp() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ani_cli_help_title)
            .setMessage(R.string.ani_cli_help_message)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun showAniCliError(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ani_cli_error_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun showDebugMenu() {
        assert(BuildConfig.DEBUG)
        val context = requireContext()
        with (AlertDialog.Builder(context)) {
            setItems(DEBUG_ACTIVITIES) { dialog, idx ->
                dialog.dismiss()
                val intent = Intent(Intent.ACTION_MAIN)
                intent.setClassName(context, "${context.packageName}.${DEBUG_ACTIVITIES[idx]}")
                startActivity(intent)
            }
            create().show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // phone screens are too small to show the action buttons alongside the logo
        if (!Utils.isXLargeTablet(requireContext())) {
            binding.logo.isVisible = newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstRun) {
            restoreChoice()
        } else if (returningFromPlayer) {
            restoreChoice(prev, prevData)
        }
        firstRun = false
        returningFromPlayer = false
    }

    private fun saveChoice(type: String, data: String? = null) {
        if (prev != type)
            lastPath = ""
        prev = type
        prevData = data

        if (!binding.switch1.isChecked)
            return
        binding.switch1.isChecked = false
        with (PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()) {
            putString("MainScreenFragment_remember", type)
            if (data == null)
                remove("MainScreenFragment_remember_data")
            else
                putString("MainScreenFragment_remember_data", data)
            commit()
        }
    }

    private fun restoreChoice() {
        with (PreferenceManager.getDefaultSharedPreferences(requireContext())) {
            restoreChoice(
                getString("MainScreenFragment_remember", "") ?: "",
                getString("MainScreenFragment_remember_data", "")
            )
        }
    }

    private fun restoreChoice(type: String, data: String?) {
        when (type) {
            "doc" -> {
                val uri = Uri.parse(data)
                // check that we can still access the folder
                if (!DocumentPickerFragment.isTreeUsable(requireContext(), uri))
                    return

                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.DOC_PICKER)
                i.putExtra("root", uri.toString())
                if (lastPath != "")
                    i.putExtra("default_path", lastPath)
                filePickerLauncher.launch(i)
            }
            "url" -> binding.urlBtn.callOnClick()
            "file" -> binding.filepickerBtn.callOnClick()
            "anicli" -> binding.aniCliBtn.callOnClick()
        }
    }

    private fun playFile(filepath: String) {
        val i: Intent
        if (filepath.startsWith("content://")) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse(filepath))
        } else {
            i = Intent()
            i.putExtra("filepath", filepath)
        }
        i.setClass(requireContext(), MPVActivity::class.java)
        playerLauncher.launch(i)
    }

    companion object {
        private const val TAG = "mpv"

        // list of debug or testing activities that can be launched
        private val DEBUG_ACTIVITIES = arrayOf(
            "IntentTestActivity",
            "CodecInfoActivity"
        )
    }
}
