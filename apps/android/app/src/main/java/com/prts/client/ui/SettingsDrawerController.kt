package com.prts.client.ui

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.prts.client.databinding.SheetSettingsBinding

/** Switches sections within one settings form so draft fields survive navigation. */
internal class SettingsDrawerController(private val binding: SheetSettingsBinding, dialog: BottomSheetDialog) {
    enum class Page(val title: String) {
        HOME("设置"), VOICE("音色管理"), MODEL("模型管理"), BACKGROUND("背景管理"), CONNECTION("连接管理"), CALL("通话管理"), UPDATE("更新管理"),
    }

    private val sections = mapOf(
        Page.HOME to binding.settingsHomeSection,
        Page.VOICE to binding.settingsVoiceSection,
        Page.MODEL to binding.settingsModelSection,
        Page.BACKGROUND to binding.settingsBackgroundSection,
        Page.CONNECTION to binding.settingsConnectionSection,
        Page.CALL to binding.settingsCallSection,
        Page.UPDATE to binding.settingsUpdateSection,
    )
    private val scrollPositions = IntArray(Page.entries.size)
    var page = Page.HOME
        private set
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = show(Page.HOME)
    }

    init {
        dialog.onBackPressedDispatcher.addCallback(back)
        binding.btnSettingsBack.setOnClickListener { show(Page.HOME) }
        binding.btnSettingsVoice.setOnClickListener { show(Page.VOICE) }
        binding.btnSettingsModel.setOnClickListener { show(Page.MODEL) }
        binding.btnSettingsBackground.setOnClickListener { show(Page.BACKGROUND) }
        binding.btnSettingsConnection.setOnClickListener { show(Page.CONNECTION) }
        binding.btnSettingsCall.setOnClickListener { show(Page.CALL) }
        binding.btnSettingsUpdate.setOnClickListener { show(Page.UPDATE) }
    }

    fun show(next: Page) {
        if (page == next) return
        val scroll = binding.settingsScroll
        scrollPositions[page.ordinal] = scroll.scrollY
        binding.root.findFocus()?.let { focused ->
            if (focused.onCheckIsTextEditor()) {
                focused.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
                focused.clearFocus()
            }
        }
        sections.getValue(page).visibility = View.GONE
        page = next
        sections.getValue(next).visibility = View.VISIBLE
        val insideSection = next != Page.HOME
        binding.settingsPageTitle.text = next.title
        binding.btnSaveSettings.text = if (next == Page.CALL) "保存通话设置" else "保存设置"
        binding.btnSaveSettings.visibility = if (next == Page.UPDATE) View.GONE else View.VISIBLE
        binding.btnSettingsBack.visibility = if (insideSection) View.VISIBLE else View.GONE
        binding.settingsError.visibility = View.GONE
        back.isEnabled = insideSection
        scroll.post { if (page == next) scroll.scrollTo(0, scrollPositions[next.ordinal]) }
    }
}
