package com.byf3332.at2ht

import android.graphics.Color
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.protocol.ChannelConfig
import com.google.android.material.appbar.MaterialToolbar

class MainUiRenderController(
    private val topBar: MaterialToolbar,
    private val contentHost: FrameLayout,
    private val fullScreenLoadingPanel: LinearLayout,
    private val tvFullScreenLoadingText: TextView,
    private val homePanel: LinearLayout,
    private val connectionPanel: LinearLayout,
    private val featureSettingsPanel: ScrollView,
    private val chatPanel: LinearLayout,
    private val pttPanel: LinearLayout,
    private val smartLinkPanel: ScrollView,
    private val readWritePanel: LinearLayout,
    private val tvConnectionHint: TextView,
    private val loadingPanel: LinearLayout,
    private val tvLoadingText: TextView,
    private val btnWriteCodeplug: TextView,
    private val tvReadWriteHint: TextView,
    private val tvReadWriteDirtyState: TextView,
    private val readWriteFixedAdapter: ReadWriteChannelFixedAdapter,
    private val readWriteTableAdapter: ReadWriteChannelTableAdapter,
    private val getShowLoadingOnly: () -> Boolean,
    private val getPendingEntryTarget: () -> DeviceEntryTarget?,
    private val getAppSection: () -> AppSection,
    private val getTalkStage: () -> TalkStage,
    private val getStatusBarInsetTop: () -> Int,
    private val getFullScreenLoadingText: () -> String,
    private val getBleState: () -> BleSessionState,
    private val getSelectedDeviceAddress: () -> String?,
    private val getPermissionsGranted: () -> Boolean,
    private val notifyDeviceAdapterChanged: () -> Unit,
    private val renderFrequencyCard: () -> Unit,
    private val renderChannelGridSelection: () -> Unit,
    private val renderFeatureSettings: () -> Unit,
    private val renderSmartLink: () -> Unit,
    private val renderChat: () -> Unit,
    private val renderPtt: () -> Unit,
    private val getReadWriteLoaded: () -> Boolean,
    private val getReadWriteChannels: () -> List<ChannelConfig>,
    private val getReadWriteBaselineChannels: () -> List<ChannelConfig>,
    private val dp: (Int) -> Int,
    private val menuScanId: Int,
    private val menuChatUsernameId: Int,
    private val menuChatClearId: Int,
) {
    fun renderAll() {
        renderTopBar()
        renderPanels()
        renderConnection()
        renderHome()
        renderFeatureSettings()
        renderSmartLink()
        renderReadWrite()
        renderChat()
        renderPtt()
    }

    fun renderTopBar() {
        val showLoadingOnly = getShowLoadingOnly()
        val pendingEntryTarget = getPendingEntryTarget()
        val appSection = getAppSection()
        val talkStage = getTalkStage()
        val title = when {
            showLoadingOnly && pendingEntryTarget == DeviceEntryTarget.Control -> topBar.context.getString(R.string.top_title_control)
            else -> when (appSection) {
                AppSection.Offline -> when (talkStage) {
                    TalkStage.Main -> topBar.context.getString(R.string.top_title_control)
                    TalkStage.Connection -> topBar.context.getString(R.string.top_title_connection)
                    TalkStage.FeatureSettings -> topBar.context.getString(R.string.top_title_control)
                    TalkStage.Chat -> topBar.context.getString(R.string.top_title_chat)
                    TalkStage.Ptt -> topBar.context.getString(R.string.top_title_ptt_mode)
                }
                AppSection.SmartLink -> topBar.context.getString(R.string.top_title_smart_link)
                AppSection.ReadWrite -> topBar.context.getString(R.string.top_title_read_write)
            }
        }
        val showBack = appSection != AppSection.Offline ||
            (appSection == AppSection.Offline &&
                (talkStage == TalkStage.Main ||
                    talkStage == TalkStage.FeatureSettings ||
                    talkStage == TalkStage.Chat ||
                    talkStage == TalkStage.Ptt ||
                    (showLoadingOnly && pendingEntryTarget == DeviceEntryTarget.Control)))
        topBar.title = title
        topBar.navigationIcon = if (showBack) {
            AppCompatResources.getDrawable(topBar.context, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        } else {
            null
        }
        topBar.setBackgroundColor(Color.parseColor("#F7F7F8"))
        topBar.menu.clear()
        when {
            talkStage == TalkStage.Connection && appSection == AppSection.Offline && !showLoadingOnly -> {
                topBar.menu.add(Menu.NONE, menuScanId, Menu.NONE, topBar.context.getString(R.string.menu_scan_devices)).apply {
                    setIcon(android.R.drawable.ic_input_add)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                topBar.menu.findItem(menuScanId)?.icon?.setTint(Color.parseColor("#111827"))
            }

            talkStage == TalkStage.Chat && appSection == AppSection.Offline -> {
                topBar.menu.add(Menu.NONE, menuChatUsernameId, Menu.NONE, topBar.context.getString(R.string.menu_chat_username))
                topBar.menu.add(Menu.NONE, menuChatClearId, Menu.NONE, topBar.context.getString(R.string.menu_clear_chat_history))
            }
        }
        contentHost.setPadding(0, getStatusBarInsetTop() + dp(56), 0, 0)
    }

    fun renderPanels() {
        val showLoadingOnly = getShowLoadingOnly()
        val appSection = getAppSection()
        val talkStage = getTalkStage()
        fullScreenLoadingPanel.isVisible = showLoadingOnly
        homePanel.isVisible = !showLoadingOnly && appSection == AppSection.Offline && talkStage == TalkStage.Main
        connectionPanel.isVisible = !showLoadingOnly && appSection == AppSection.Offline && talkStage == TalkStage.Connection
        featureSettingsPanel.isVisible = !showLoadingOnly && appSection == AppSection.Offline && talkStage == TalkStage.FeatureSettings
        chatPanel.isVisible = !showLoadingOnly && appSection == AppSection.Offline && talkStage == TalkStage.Chat
        pttPanel.isVisible = !showLoadingOnly && appSection == AppSection.Offline && talkStage == TalkStage.Ptt
        smartLinkPanel.isVisible = !showLoadingOnly && appSection == AppSection.SmartLink
        readWritePanel.isVisible = !showLoadingOnly && appSection == AppSection.ReadWrite
        tvFullScreenLoadingText.text = getFullScreenLoadingText()
    }

    fun renderConnection() {
        tvConnectionHint.text = when {
            getBleState() == BleSessionState.Ready && getSelectedDeviceAddress() != null -> topBar.context.getString(R.string.device_ready_prompt)
            getPermissionsGranted() -> topBar.context.getString(R.string.device_scan_prompt)
            else -> topBar.context.getString(R.string.device_permission_prompt)
        }
        loadingPanel.isVisible = false
        tvLoadingText.text = topBar.context.getString(R.string.common_scanning)
        notifyDeviceAdapterChanged()
    }

    fun renderHome() {
        renderFrequencyCard()
        renderChannelGridSelection()
    }

    fun renderReadWrite() {
        val readWriteLoaded = getReadWriteLoaded()
        val readWriteChannels = getReadWriteChannels()
        val baseline = getReadWriteBaselineChannels()
        val dirtyCount = readWriteChannels.indices.count { readWriteChannels[it] != baseline[it] }
        tvReadWriteHint.text = if (readWriteLoaded) {
            topBar.context.getString(R.string.read_write_pending_hint)
        } else {
            topBar.context.getString(R.string.read_write_loading_hint)
        }
        tvReadWriteDirtyState.text = when {
            !readWriteLoaded -> topBar.context.getString(R.string.read_write_no_modify_before_load)
            dirtyCount == 0 -> topBar.context.getString(R.string.read_write_no_dirty_changes)
            else -> topBar.context.getString(R.string.read_write_dirty_count, dirtyCount)
        }
        btnWriteCodeplug.isEnabled = readWriteLoaded
        btnWriteCodeplug.alpha = if (btnWriteCodeplug.isEnabled) 1f else 0.5f
        readWriteFixedAdapter.submit(readWriteChannels, baseline)
        readWriteTableAdapter.submit(readWriteChannels, baseline)
    }
}
