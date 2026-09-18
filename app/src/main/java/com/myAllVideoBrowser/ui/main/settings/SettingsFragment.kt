package com.myAllVideoBrowser.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.Observable
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentSettingsBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.settings.adblock.AdBlockSettingsFragment
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.IntentUtil
import com.myAllVideoBrowser.util.SystemUtil
import javax.inject.Inject

class SettingsFragment : BaseFragment() {

    companion object {
        fun newInstance() = SettingsFragment()
        private const val ABSOLUTE_MAX_THREADS = 16
    }

    @Inject
    lateinit var fileUtil: FileUtil

    @Inject
    lateinit var intentUtil: IntentUtil

    @Inject
    lateinit var systemUtil: SystemUtil

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var dataBinding: FragmentSettingsBinding
    private lateinit var settingsViewModel: SettingsViewModel

    private var lastSavedRegularThreadsCount = -1

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleCustomFolderPicked(uri)
        }

    private val regularThreadsListener = Slider.OnChangeListener { _, value, fromUser ->
        if (fromUser) {
            val progress = value.toInt()
            settingsViewModel.setRegularThreadsCount(progress)

            if (lastSavedRegularThreadsCount == 1 && progress > 1) {
                context?.let { showDownloadWarningDialog(it) }
            }
            lastSavedRegularThreadsCount = progress
        }
    }

    private val m3u8ThreadsListener = Slider.OnChangeListener { _, value, fromUser ->
        if (fromUser) {
            settingsViewModel.setM3u8ThreadsCount(value.toInt())
        }
    }

    private val adsTresholdListener = Slider.OnChangeListener { _, value, fromUser ->
        if (fromUser) {
            settingsViewModel.setVideoDetectionTreshold(value.toInt())
        }
    }

    private val tresholdCallback = object : Observable.OnPropertyChangedCallback() {
        @SuppressLint("SetTextI18n")
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            val currentTreshold = settingsViewModel.videoDetectionTreshold.get()
            val readableSize = FileUtil.getFileSizeReadable(currentTreshold.toDouble())
            val readebleText = getString(R.string.ads_detection_treshold) + " $readableSize"
            dataBinding.adsTresholdText.text = readebleText
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        settingsViewModel = mainActivity.settingsViewModel
        dataBinding = FragmentSettingsBinding.inflate(inflater, container, false)
        dataBinding.viewModel = settingsViewModel
        dataBinding.lifecycleOwner = viewLifecycleOwner

        dataBinding.seekBarAdsTreshold.setLabelFormatter { value: Float ->
            val readableSize = FileUtil.getFileSizeReadable(value.toDouble())
            return@setLabelFormatter readableSize
        }

        ViewCompat.setOnApplyWindowInsetsListener(dataBinding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)

            dataBinding.bottomBar.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSliderListeners()
        setupTextUpdateCallbacks()
        handleUIEvents()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        dataBinding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        settingsViewModel.start()

        val coreCount = Runtime.getRuntime().availableProcessors()
        val maxThreads = coreCount.coerceIn(1, ABSOLUTE_MAX_THREADS).toFloat()

        dataBinding.seekBarRegular.valueTo = maxThreads
        dataBinding.seekBarM3u8.valueTo = maxThreads

        lastSavedRegularThreadsCount = settingsViewModel.regularThreadsCount.get()
        dataBinding.seekBarRegular.value =
            lastSavedRegularThreadsCount.coerceIn(1, maxThreads.toInt()).toFloat()
        dataBinding.seekBarM3u8.value =
            settingsViewModel.m3u8ThreadsCount.get().coerceIn(1, maxThreads.toInt()).toFloat()
        dataBinding.seekBarAdsTreshold.value =
            settingsViewModel.videoDetectionTreshold.get().toFloat().coerceIn(0f, 52428800f)
    }

    override fun onDestroyView() {
        settingsViewModel.stop()
        settingsViewModel.videoDetectionTreshold.removeOnPropertyChangedCallback(tresholdCallback)

        dataBinding.seekBarRegular.removeOnChangeListener(regularThreadsListener)
        dataBinding.seekBarM3u8.removeOnChangeListener(m3u8ThreadsListener)
        dataBinding.seekBarAdsTreshold.removeOnChangeListener(adsTresholdListener)

        super.onDestroyView()
    }

    private fun setupSliderListeners() {
        dataBinding.seekBarRegular.addOnChangeListener(regularThreadsListener)
        dataBinding.seekBarM3u8.addOnChangeListener(m3u8ThreadsListener)
        dataBinding.seekBarAdsTreshold.addOnChangeListener(adsTresholdListener)
    }

    private fun handleUIEvents() {
        settingsViewModel.clearCookiesEvent.observe(viewLifecycleOwner) {
            systemUtil.clearCookies(context)
        }
        settingsViewModel.openVideoFolderEvent.observe(viewLifecycleOwner) {
            val customUri = FileUtil.CUSTOM_DOWNLOAD_URI
            if (FileUtil.IS_CUSTOM_DIR_USE && customUri != null) {
                openCustomFolderInFileManager(customUri)
            } else {
                intentUtil.openVideoFolder(context, fileUtil.folderDir.path)
            }
        }
        settingsViewModel.openAdBlockSettingsEvent.observe(viewLifecycleOwner) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container_view, AdBlockSettingsFragment.newInstance())
                .addToBackStack(null)
                .commit()
        }
        settingsViewModel.openCustomFolderPickerEvent.observe(viewLifecycleOwner) {
            openDocumentTreeLauncher.launch(null)
        }
    }

    private fun handleCustomFolderPicked(uri: Uri?) {
        val context = context ?: return
        if (uri == null) {
            // User cancelled the picker; the "Custom folder" radio already toggled itself on
            // click, so force a re-sync back to the previously selected storage option.
            settingsViewModel.refreshStorageType()
            return
        }

        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settingsViewModel.setDownloadsFolderCustom(uri)
        } catch (e: Throwable) {
            Toast.makeText(
                context,
                R.string.custom_folder_permission_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openCustomFolderInFileManager(uri: Uri) {
        val context = context ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(
                context,
                R.string.settings_message_open_folder,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupTextUpdateCallbacks() {
        settingsViewModel.videoDetectionTreshold.addOnPropertyChangedCallback(tresholdCallback)
        tresholdCallback.onPropertyChanged(null, 0)
    }

    private fun showDownloadWarningDialog(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Download Warning")
            .setMessage("Some downloads may be corrupted in multi-thread downloading, if you experience some issues, switch back to single thread download!")
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
