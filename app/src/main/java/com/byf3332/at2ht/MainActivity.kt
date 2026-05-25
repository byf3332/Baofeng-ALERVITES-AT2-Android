package com.byf3332.at2ht

import android.Manifest
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.byf3332.at2ht.core.ble.BleScanDevice
import com.byf3332.at2ht.core.ble.BleSession
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.chat.ChatVoicePlayer
import com.byf3332.at2ht.core.chat.OfflineVoiceRecorder
import com.byf3332.at2ht.core.ptt.PttVoiceReceiver
import com.byf3332.at2ht.core.ptt.PttVoiceSender
import com.byf3332.at2ht.core.protocol.At2Commands
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import com.byf3332.at2ht.core.protocol.At2ProtocolDecoder
import com.byf3332.at2ht.core.protocol.At2RxState
import com.byf3332.at2ht.core.protocol.At2SessionProtocol
import com.byf3332.at2ht.core.protocol.ChannelConfig
import com.byf3332.at2ht.core.protocol.FeatureSettingsState
import com.byf3332.at2ht.core.protocol.OfflineMessageAssembler
import com.byf3332.at2ht.widget.SyncHorizontalScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private var permissionsGranted = false
    private var pendingScanAfterPermission = false
    private var pendingVoicePermissionAction: (() -> Unit)? = null
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = hasScanPermissions()
        if (permissionsGranted && pendingScanAfterPermission) {
            pendingScanAfterPermission = false
            continueScanDevicesFromUserAction()
        } else {
            pendingScanAfterPermission = false
        }
    }
    private val requestVoicePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingVoicePermissionAction
        pendingVoicePermissionAction = null
        if (granted) {
            action?.invoke()
        }
        if (::chatInteractionController.isInitialized) {
            chatInteractionController.onVoicePermissionResult(granted)
        }
    }
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (::locationShareController.isInitialized) {
            locationShareController.onLocationPermissionResult(granted)
        }
    }
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        chatInteractionController.processSelectedImage(uri)
    }
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = if (::chatInteractionController.isInitialized) {
            chatInteractionController.consumePendingCameraImageUri()
        } else {
            null
        }
        if (!success || uri == null) return@registerForActivityResult
        chatInteractionController.processSelectedImage(uri, deleteAfter = true)
    }

    private val session by lazy { BleSession(this) }
    private val protocolDecoder by lazy { At2ProtocolDecoder() }
    private val rxState = At2RxState()
    private val channels = MutableList(30) { ChannelConfig() }
    private val readWriteChannels = MutableList(30) { ChannelConfig() }
    private val readWriteBaselineChannels = MutableList(30) { ChannelConfig() }
    private val chatMessages = mutableListOf<OfflineChatMessage>()
    private val knownDevices = mutableListOf<BleScanDevice>()
    private val txMutex = Mutex()
    private val lastPtt403TxAtMs = AtomicLong(0L)
    private val pttReceiver by lazy { PttVoiceReceiver(log = {}) }
    private val offlineVoiceRecorder by lazy { OfflineVoiceRecorder(scope = lifecycleScope, log = {}) }
    private val chatStore by lazy { OfflineChatStore(this) }
    private val chatVoicePlayer by lazy {
        ChatVoicePlayer(
            scope = lifecycleScope,
            log = {},
            onStateChanged = { messageId, playing ->
                if (messageId != null && ::chatInteractionController.isInitialized) {
                    chatInteractionController.onVoicePlayerStateChanged(messageId, playing)
                }
            }
        )
    }
    private val pttSender by lazy {
        PttVoiceSender(
            scope = lifecycleScope,
            log = {},
            sendPayload = sessionProtocol::sendPayload
        )
    }
    private val protocolExecutor by lazy {
        At2ProtocolExecutor(
            sendPayload = sessionProtocol::sendPayload,
            pttAckCounter = { pttModeAckCounter },
            offlineAckCounter = { offlineBusinessAckCounter },
            channelSwitchAckCounter = { channelSwitchAckCounter },
            smartLinkAckCounter = { smartLinkModeAckCounter },
            smartLinkPttAckCounter = { smartLinkPttAckCounter },
            deviceNameAckCounter = { deviceNameAckCounter },
            codeplugWriteAckCounter = { codeplugWriteAckCounter },
            nextOfflineMessageId = ::allocateOfflineMessageId
        )
    }
    private val offlineAssembler = OfflineMessageAssembler()
    private val offlineFramePending = ArrayList<Byte>(4096)

    private val sessionProtocol by lazy {
        At2SessionProtocol(
            session = session,
            txMutex = txMutex,
            lastPtt403TxAtMs = lastPtt403TxAtMs,
        )
    }

    private var bleState: BleSessionState = BleSessionState.Idle
    private var issuedBootstrap = false
    private var autoRetryDone = false
    private var showLoadingOnly = false
    private var talkStage = TalkStage.Connection
    private var appSection = AppSection.Offline
    private var pttModeAckCounter = 0
    private var offlineBusinessAckCounter = 0
    private var channelSwitchAckCounter = 0
    private var smartLinkModeAckCounter = 0
    private var smartLinkPttAckCounter = 0
    private var deviceNameAckCounter = 0
    private var codeplugWriteAckCounter = 0
    private var currentCh = 1
    private var dualWatchFocus = At2Commands.Side.A
    private var dualWatchChannelA = 1
    private var dualWatchChannelB = 1
    private var allowStatusChannelSync = false
    private var chatMenuExpanded = false
    private var selectedDeviceAddress: String? = null
    private var pendingEntryTarget: DeviceEntryTarget? = null
    private var fullScreenLoadingText = ""
    private var controlEntryToken = 0L
    private var connectingDeviceAddress: String? = null
    private var featureSettings = FeatureSettingsState()
    private var smartLinkEnabled = false
    private var smartLinkMainPttTarget: At2Commands.MainPttTarget? = null
    private var readWriteLoaded = false

    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var contentHost: FrameLayout
    private lateinit var rootContainer: FrameLayout
    private lateinit var topBar: MaterialToolbar

    private lateinit var homePanel: LinearLayout
    private lateinit var homeTopSpacer: LinearLayout
    private lateinit var connectionPanel: LinearLayout
    private lateinit var tvConnectionHint: TextView
    private lateinit var rvDevices: RecyclerView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var tvLoadingText: TextView
    private lateinit var fullScreenLoadingPanel: LinearLayout
    private lateinit var fullScreenLoadingSpinner: ProgressBar
    private lateinit var tvFullScreenLoadingText: TextView
    private lateinit var tvCurrentChannel: TextView
    private lateinit var btnDualWatchFocus: TextView
    private lateinit var tvFrequencyMain: TextView
    private lateinit var tvFrequencySub: TextView
    private lateinit var btnEditChannel: TextView
    private lateinit var btnFeatureSettings: TextView
    private lateinit var channelGrid: GridLayout
    private lateinit var featureSettingsPanel: ScrollView
    private lateinit var tvFeatureTabChannel: TextView
    private lateinit var tvFeatureTabSettings: TextView
    private lateinit var rowFeatureDualWatch: LinearLayout
    private lateinit var tvFeatureDualWatchValue: TextView
    private lateinit var rowFeaturePromptLanguage: LinearLayout
    private lateinit var tvFeaturePromptLanguageValue: TextView
    private lateinit var switchFeaturePromptTone: SwitchMaterial
    private lateinit var rowFeatureVolume: LinearLayout
    private lateinit var tvFeatureVolumeValue: TextView
    private lateinit var rowFeatureSquelch: LinearLayout
    private lateinit var tvFeatureSquelchValue: TextView
    private lateinit var rowFeatureTot: LinearLayout
    private lateinit var tvFeatureTotValue: TextView
    private lateinit var switchFeatureVox: SwitchMaterial
    private lateinit var rowFeatureVoxSensitivity: LinearLayout
    private lateinit var tvFeatureVoxSensitivityLabel: TextView
    private lateinit var tvFeatureVoxSensitivityValue: TextView
    private lateinit var tvFeatureVoxSensitivityArrow: TextView
    private lateinit var switchFeatureTxInhibit: SwitchMaterial
    private lateinit var rowFeatureTxInterval: LinearLayout
    private lateinit var tvFeatureTxIntervalValue: TextView
    private lateinit var switchFeatureNoiseReduction: SwitchMaterial

    private lateinit var chatPanel: LinearLayout
    private lateinit var tvChatBanner: TextView
    private lateinit var rvMessages: RecyclerView
    private lateinit var etDraft: EditText
    private lateinit var btnOpenPtt: TextView
    private lateinit var btnToggleOptions: TextView
    private lateinit var chatOptionsRow: LinearLayout
    private lateinit var actionHelp: LinearLayout
    private lateinit var actionLocation: LinearLayout
    private lateinit var actionImage: LinearLayout
    private lateinit var actionVoice: LinearLayout

    private lateinit var pttPanel: LinearLayout
    private lateinit var tvPttHint: TextView
    private lateinit var pttPressArea: LinearLayout
    private lateinit var tvPttChannelLabel: TextView
    private lateinit var tvPttFrequency: TextView
    private lateinit var tvPttState: TextView
    private lateinit var tvPttSubState: TextView
    private lateinit var btnPttPrevChannel: TextView
    private lateinit var btnPttNextChannel: TextView

    private lateinit var smartLinkPanel: ScrollView
    private lateinit var switchSmartLinkMode: SwitchMaterial
    private lateinit var tvSmartLinkStatus: TextView
    private lateinit var btnSmartLinkCompatibility: TextView
    private lateinit var rowSmartLinkPttSetting: LinearLayout
    private lateinit var tvSmartLinkPttSettingValue: TextView
    private lateinit var tvSmartLinkNotice: TextView
    private lateinit var featureSettingsController: FeatureSettingsController
    private lateinit var smartLinkUiController: SmartLinkUiController

    private lateinit var readWritePanel: LinearLayout
    private lateinit var tvReadWriteHint: TextView
    private lateinit var tvReadWriteDirtyState: TextView
    private lateinit var btnWriteCodeplug: TextView
    private lateinit var hsvReadWriteHeader: SyncHorizontalScrollView
    private lateinit var hsvReadWriteBody: SyncHorizontalScrollView
    private lateinit var rvReadWriteFixedChannel: RecyclerView
    private lateinit var rvReadWriteRows: RecyclerView

    private lateinit var chatAdapter: OfflineChatAdapter
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var readWriteFixedAdapter: ReadWriteChannelFixedAdapter
    private lateinit var readWriteTableAdapter: ReadWriteChannelTableAdapter
    private lateinit var readWriteUiController: ReadWriteUiController
    private lateinit var knownDevicesController: KnownDevicesController
    private lateinit var locationShareController: LocationShareController
    private lateinit var deviceConnectionController: DeviceConnectionController
    private lateinit var chatHistoryController: ChatHistoryController
    private lateinit var chatFlowController: ChatFlowController
    private lateinit var chatInteractionController: ChatInteractionController
    private lateinit var bleEventController: BleEventController
    private lateinit var mainUiRenderController: MainUiRenderController
    private lateinit var pttUiController: PttUiController
    private lateinit var homeUiController: HomeUiController
    private lateinit var chatUiController: ChatUiController
    private lateinit var appPermissionController: AppPermissionController
    private lateinit var dialogUiController: DialogUiController
    private lateinit var appNavigationController: AppNavigationController
    private lateinit var mainChromeController: MainChromeController
    private lateinit var bleStateController: BleStateController
    private var statusBarInsetTop = 0

    companion object {
        private const val MENU_SCAN_ID = 0xA721
        private const val MENU_CHAT_USERNAME_ID = 0xA722
        private const val MENU_CHAT_CLEAR_ID = 0xA723
        private const val PREF_CHAT_HISTORY_PREFIX = "chat_history"
        private const val PREF_KNOWN_DEVICES = "known_devices"
        private const val PREF_LAST_DEVICE = "last_device"
        private const val MAX_OFFLINE_TEXT_UTF8_BYTES = 1000
        private const val MAX_CHAT_MESSAGES_INITIAL_LOAD = 100
        private const val MAX_CHAT_MESSAGES_PAGE_SIZE = 100
        private const val MAX_CHAT_MESSAGES_PER_THREAD = 500
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStrings.init(applicationContext)
        enableEdgeToEdge()
        prefs = getSharedPreferences("at2ht_prefs", Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)
        bindViews()
        setupChat()
        appPermissionController = AppPermissionController(
            context = this,
            requestPermissions = { requestPermissions.launch(it) },
        )
        permissionsGranted = hasScanPermissions()
        dialogUiController = DialogUiController(
            context = this,
            dp = ::dp,
        )
        knownDevicesController = KnownDevicesController(
            context = this,
            scope = lifecycleScope,
            prefs = prefs,
            protocolExecutor = protocolExecutor,
            knownDevices = knownDevices,
            knownDevicesPrefKey = PREF_KNOWN_DEVICES,
            lastDevicePrefKey = PREF_LAST_DEVICE,
            getBleState = { bleState },
            getSelectedDeviceAddress = { selectedDeviceAddress },
            getConnectingDeviceAddress = { connectingDeviceAddress },
            setSelectedDeviceAddress = { selectedDeviceAddress = it },
            onKnownDevicesChanged = {
                if (::deviceAdapter.isInitialized) deviceAdapter.notifyDataSetChanged()
            },
            onReconnectRequested = ::reconnectKnownDevice,
            stopConnectionBeforeDelete = { disconnectCurrentKeepDevice() },
            styleDialog = ::styleDialog,
        )
        locationShareController = LocationShareController(
            context = this,
            scope = lifecycleScope,
            prefs = prefs,
            requestLocationPermission = {
                requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            },
            sendStructuredTextMessage = ::sendStructuredTextMessage,
            buildLoadingDialogView = ::buildLoadingDialogView,
            styleDialog = ::styleDialog,
        )
        chatFlowController = ChatFlowController(
            context = this,
            scope = lifecycleScope,
            chatMessages = chatMessages,
            chatAdapter = chatAdapter,
            rvMessages = rvMessages,
            persistChatMessage = ::persistChatMessage,
            deleteChatMessage = ::deleteChatMessage,
            nextChatLocalId = ::nextChatLocalId,
            parseSpecialText = ::parseSpecialText,
            renderChat = ::renderChat,
        )
        chatInteractionController = ChatInteractionController(
            context = this,
            scope = lifecycleScope,
            prefs = prefs,
            contentResolver = contentResolver,
            packageNameProvider = { packageName },
            cacheDirProvider = { cacheDir },
            protocolExecutor = protocolExecutor,
            voiceRecorder = offlineVoiceRecorder,
            voicePlayer = chatVoicePlayer,
            getBleState = { bleState },
            getTalkStage = { talkStage },
            getAppSection = { appSection },
            getCurrentChannel = { channels.getOrNull(currentCh - 1) ?: ChannelConfig() },
            appendChatMessage = ::appendChatMessage,
            replaceChatMessage = ::replaceChatMessage,
            updateChatMessageProgress = ::updateChatMessageProgress,
            removeChatMessage = ::removeChatMessage,
            renderChat = ::renderChat,
            nextChatLocalId = ::nextChatLocalId,
            requestVoicePermission = { requestVoicePermission.launch(Manifest.permission.RECORD_AUDIO) },
            hasRecordAudioPermission = ::hasRecordAudioPermission,
            launchImagePicker = { pickImageLauncher.launch(arrayOf("image/*")) },
            launchCameraCapture = { uri -> takePictureLauncher.launch(uri) },
            styleDialog = ::styleDialog,
            dp = ::dp,
        )
        bleEventController = BleEventController(
            protocolDecoder = protocolDecoder,
            rxState = rxState,
            offlineAssembler = offlineAssembler,
            offlineFramePending = offlineFramePending,
            channels = channels,
            chatFlowController = chatFlowController,
            pttReceiver = pttReceiver,
            getTalkStage = { talkStage },
            getCurrentCh = { currentCh },
            setCurrentCh = { currentCh = it },
            getFeatureSettings = { featureSettings },
            setFeatureSettings = { featureSettings = it },
            getDualWatchFocus = { dualWatchFocus },
            getDualWatchChannelA = { dualWatchChannelA },
            setDualWatchChannelA = { dualWatchChannelA = it },
            getDualWatchChannelB = { dualWatchChannelB },
            setDualWatchChannelB = { dualWatchChannelB = it },
            getAllowStatusChannelSync = { allowStatusChannelSync },
            setAllowStatusChannelSync = { allowStatusChannelSync = it },
            setSmartLinkEnabled = { smartLinkEnabled = it },
            setSmartLinkMainPttTarget = { smartLinkMainPttTarget = it },
            onSmartLinkStateChanged = ::renderSmartLink,
            incrementPttModeAckCounter = { pttModeAckCounter += 1 },
            incrementOfflineBusinessAckCounter = { offlineBusinessAckCounter += 1 },
            incrementChannelSwitchAckCounter = { channelSwitchAckCounter += 1 },
            incrementSmartLinkModeAckCounter = { smartLinkModeAckCounter += 1 },
            incrementSmartLinkPttAckCounter = { smartLinkPttAckCounter += 1 },
            incrementDeviceNameAckCounter = { deviceNameAckCounter += 1 },
            incrementCodeplugWriteAckCounter = { codeplugWriteAckCounter += 1 },
            renderFrequencyCard = ::renderFrequencyCard,
            renderChannelGridSelection = ::renderChannelGridSelection,
        )
        chatHistoryController = ChatHistoryController(
            scope = lifecycleScope,
            chatStore = chatStore,
            chatMessages = chatMessages,
            chatAdapter = chatAdapter,
            rvMessages = rvMessages,
            currentChatStorageKey = ::currentChatStorageKey,
            styleDialog = ::styleDialog,
            stopVoicePlayback = { chatInteractionController.stopVoicePlayback() },
            renderChat = { renderChat() },
            clearIncomingPendingState = chatFlowController::clearIncomingPendingState,
            initialLoadLimit = MAX_CHAT_MESSAGES_INITIAL_LOAD,
            pageSize = MAX_CHAT_MESSAGES_PAGE_SIZE,
            perThreadLimit = MAX_CHAT_MESSAGES_PER_THREAD,
        )
        deviceConnectionController = DeviceConnectionController(
            context = this,
            scope = lifecycleScope,
            session = session,
            protocolExecutor = protocolExecutor,
            getBleState = { bleState },
            hasScanPermissions = ::hasScanPermissions,
            requestPermissions = {
                pendingScanAfterPermission = true
                requestPermissions.launch(scanPermissions())
            },
            isLocationServiceEnabled = ::isLocationServiceEnabled,
            getSelectedDeviceAddress = { selectedDeviceAddress },
            setSelectedDeviceAddress = { selectedDeviceAddress = it },
            getPendingEntryTarget = { pendingEntryTarget },
            setPendingEntryTarget = { pendingEntryTarget = it },
            setIssuedBootstrap = { issuedBootstrap = it },
            setAutoRetryDone = { autoRetryDone = it },
            setShowLoadingOnly = { showLoadingOnly = it },
            setFullScreenLoadingText = { fullScreenLoadingText = it },
            getConnectingDeviceAddress = { connectingDeviceAddress },
            setConnectingDeviceAddress = { connectingDeviceAddress = it },
            setAllowStatusChannelSync = { allowStatusChannelSync = it },
            getFeatureSettings = { featureSettings },
            getSmartLinkEnabled = { smartLinkEnabled },
            setAppSectionState = { appSection = it },
            setTalkStageState = { talkStage = it },
            syncReadWriteFromDevice = ::syncReadWriteFromDevice,
            loadChatMessagesForCurrentThread = { loadChatMessagesForCurrentThread() },
            clearOfflineAssembler = { offlineAssembler.clear() },
            clearOfflineFramePending = { offlineFramePending.clear() },
            refreshSmartLinkState = ::refreshSmartLinkState,
            upsertKnownDevice = ::upsertKnownDevice,
            knownDevicesProvider = { knownDevices },
            renderAll = ::renderAll,
            renderConnection = ::renderConnection,
            showDeviceEntryDialog = ::showDeviceEntryDialog,
            styleDialog = ::styleDialog,
            buildDialogListAdapter = ::buildDialogListAdapter,
            buildLoadingDialogView = ::buildLoadingDialogView,
            putLastDeviceAddress = { prefs.edit().putString(PREF_LAST_DEVICE, it).apply() },
        )
        appNavigationController = AppNavigationController(
            session = session,
            protocolExecutor = protocolExecutor,
            pttSender = pttSender,
            pttReceiver = pttReceiver,
            getBleState = { bleState },
            getShowLoadingOnly = { showLoadingOnly },
            setShowLoadingOnly = { showLoadingOnly = it },
            getPendingEntryTarget = { pendingEntryTarget },
            setPendingEntryTarget = { pendingEntryTarget = it },
            getControlEntryToken = { controlEntryToken },
            setControlEntryToken = { controlEntryToken = it },
            getAppSection = { appSection },
            setAppSection = { appSection = it },
            getTalkStage = { talkStage },
            setTalkStage = { talkStage = it },
            setChatMenuExpanded = { chatMenuExpanded = it },
            setSelectedDeviceAddress = { selectedDeviceAddress = it },
            setConnectingDeviceAddress = { connectingDeviceAddress = it },
            stopVoicePlayback = { chatInteractionController.stopVoicePlayback() },
            cancelOutgoingChatTransfers = { cancelOutgoingChatTransfers() },
            stopPttUi = {
                if (::pttUiController.isInitialized) {
                    pttUiController.stop()
                } else {
                    pttSender.stop()
                }
            },
            stopAndJoinPttUi = {
                if (::pttUiController.isInitialized) {
                    pttUiController.stopAndJoin()
                } else {
                    pttSender.stopAndJoin()
                }
            },
            cleanupDeviceConnection = { deviceConnectionController.cleanup() },
            renderAll = ::renderAll,
        )
        mainChromeController = MainChromeController(
            scope = lifecycleScope,
            topBar = topBar,
            menuScanId = MENU_SCAN_ID,
            menuChatUsernameId = MENU_CHAT_USERNAME_ID,
            menuChatClearId = MENU_CHAT_CLEAR_ID,
            onScanRequested = ::scanDevicesFromUserAction,
            onChatUsernameRequested = ::showChatUsernameDialog,
            onChatClearRequested = ::clearChatHistory,
            onHandleBack = ::handleAppBack,
        )
        bleStateController = BleStateController(
            lifecycleOwner = this,
            scope = lifecycleScope,
            session = session,
            pttReceiver = pttReceiver,
            setBleState = { bleState = it },
            renderConnection = ::renderConnection,
            renderHome = ::renderHome,
            renderChatBanner = ::renderChatBanner,
            renderPtt = ::renderPtt,
            stopPttUi = {
                if (::pttUiController.isInitialized) {
                    pttUiController.stop()
                }
            },
            onBleStateUpdated = { deviceConnectionController.onBleStateUpdated() },
        )
        chatInteractionController.loadOfflineMessageIdState()
        chatInteractionController.loadPreferences()
        loadKnownDevices()
        applyInsets()
        setupHome()
        setupFeatureSettings()
        setupPtt()
        populateChannelGrid()
        setupSmartLink()
        setupReadWrite()
        mainUiRenderController = MainUiRenderController(
            topBar = topBar,
            contentHost = contentHost,
            fullScreenLoadingPanel = fullScreenLoadingPanel,
            tvFullScreenLoadingText = tvFullScreenLoadingText,
            homePanel = homePanel,
            connectionPanel = connectionPanel,
            featureSettingsPanel = featureSettingsPanel,
            chatPanel = chatPanel,
            pttPanel = pttPanel,
            smartLinkPanel = smartLinkPanel,
            readWritePanel = readWritePanel,
            tvConnectionHint = tvConnectionHint,
            loadingPanel = loadingPanel,
            tvLoadingText = tvLoadingText,
            btnWriteCodeplug = btnWriteCodeplug,
            tvReadWriteHint = tvReadWriteHint,
            tvReadWriteDirtyState = tvReadWriteDirtyState,
            readWriteFixedAdapter = readWriteFixedAdapter,
            readWriteTableAdapter = readWriteTableAdapter,
            getShowLoadingOnly = { showLoadingOnly },
            getPendingEntryTarget = { pendingEntryTarget },
            getAppSection = { appSection },
            getTalkStage = { talkStage },
            getStatusBarInsetTop = { statusBarInsetTop },
            getFullScreenLoadingText = { fullScreenLoadingText },
            getBleState = { bleState },
            getSelectedDeviceAddress = { selectedDeviceAddress },
            getPermissionsGranted = { permissionsGranted },
            notifyDeviceAdapterChanged = { deviceAdapter.notifyDataSetChanged() },
            renderFrequencyCard = ::renderFrequencyCard,
            renderChannelGridSelection = ::renderChannelGridSelection,
            renderFeatureSettings = ::renderFeatureSettings,
            renderSmartLink = ::renderSmartLink,
            renderChat = ::renderChat,
            renderPtt = ::renderPtt,
            getReadWriteLoaded = { readWriteLoaded },
            getReadWriteChannels = { readWriteChannels },
            getReadWriteBaselineChannels = { readWriteBaselineChannels },
            dp = ::dp,
            menuScanId = MENU_SCAN_ID,
            menuChatUsernameId = MENU_CHAT_USERNAME_ID,
            menuChatClearId = MENU_CHAT_CLEAR_ID,
        )
        setupTopBar()
        setupSystemBack()
        observeBleState()
        setupRxCallback()
        renderAll()
        promptAppPermissionsOnStartup()
        autoReconnectLastDevice()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceConnectionController.cleanup()
        if (::chatInteractionController.isInitialized) {
            chatInteractionController.stopVoicePlayback()
        } else {
            chatVoicePlayer.stop()
        }
        pttReceiver.stop()
        session.close()
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        contentHost = findViewById(R.id.contentHost)
        topBar = findViewById(R.id.topBar)

        homePanel = findViewById(R.id.homePanel)
        homeTopSpacer = findViewById(R.id.homeTopSpacer)
        connectionPanel = findViewById(R.id.connectionPanel)
        tvConnectionHint = findViewById(R.id.tvConnectionHint)
        rvDevices = findViewById(R.id.rvDevices)
        loadingPanel = findViewById(R.id.loadingPanel)
        tvLoadingText = findViewById(R.id.tvLoadingText)
        fullScreenLoadingPanel = findViewById(R.id.fullScreenLoadingPanel)
        fullScreenLoadingSpinner = findViewById(R.id.fullScreenLoadingSpinner)
        tvFullScreenLoadingText = findViewById(R.id.tvFullScreenLoadingText)
        tvCurrentChannel = findViewById(R.id.tvCurrentChannel)
        btnDualWatchFocus = findViewById(R.id.btnDualWatchFocus)
        tvFrequencyMain = findViewById(R.id.tvFrequencyMain)
        tvFrequencySub = findViewById(R.id.tvFrequencySub)
        btnEditChannel = findViewById(R.id.btnEditChannel)
        btnFeatureSettings = findViewById(R.id.btnFeatureSettings)
        channelGrid = findViewById(R.id.channelGrid)
        featureSettingsPanel = findViewById(R.id.featureSettingsPanel)
        tvFeatureTabChannel = findViewById(R.id.tvFeatureTabChannel)
        tvFeatureTabSettings = findViewById(R.id.tvFeatureTabSettings)
        rowFeatureDualWatch = findViewById(R.id.rowFeatureDualWatch)
        tvFeatureDualWatchValue = findViewById(R.id.tvFeatureDualWatchValue)
        rowFeaturePromptLanguage = findViewById(R.id.rowFeaturePromptLanguage)
        tvFeaturePromptLanguageValue = findViewById(R.id.tvFeaturePromptLanguageValue)
        switchFeaturePromptTone = findViewById(R.id.switchFeaturePromptTone)
        rowFeatureVolume = findViewById(R.id.rowFeatureVolume)
        tvFeatureVolumeValue = findViewById(R.id.tvFeatureVolumeValue)
        rowFeatureSquelch = findViewById(R.id.rowFeatureSquelch)
        tvFeatureSquelchValue = findViewById(R.id.tvFeatureSquelchValue)
        rowFeatureTot = findViewById(R.id.rowFeatureTot)
        tvFeatureTotValue = findViewById(R.id.tvFeatureTotValue)
        switchFeatureVox = findViewById(R.id.switchFeatureVox)
        rowFeatureVoxSensitivity = findViewById(R.id.rowFeatureVoxSensitivity)
        tvFeatureVoxSensitivityLabel = findViewById(R.id.tvFeatureVoxSensitivityLabel)
        tvFeatureVoxSensitivityValue = findViewById(R.id.tvFeatureVoxSensitivityValue)
        tvFeatureVoxSensitivityArrow = findViewById(R.id.tvFeatureVoxSensitivityArrow)
        switchFeatureTxInhibit = findViewById(R.id.switchFeatureTxInhibit)
        rowFeatureTxInterval = findViewById(R.id.rowFeatureTxInterval)
        tvFeatureTxIntervalValue = findViewById(R.id.tvFeatureTxIntervalValue)
        switchFeatureNoiseReduction = findViewById(R.id.switchFeatureNoiseReduction)

        chatPanel = findViewById(R.id.chatPanel)
        tvChatBanner = findViewById(R.id.tvChatBanner)
        rvMessages = findViewById(R.id.rvMessages)
        etDraft = findViewById(R.id.etDraft)
        btnOpenPtt = findViewById(R.id.btnOpenPtt)
        btnToggleOptions = findViewById(R.id.btnToggleOptions)
        chatOptionsRow = findViewById(R.id.chatOptionsRow)
        actionHelp = findViewById(R.id.actionHelp)
        actionLocation = findViewById(R.id.actionLocation)
        actionImage = findViewById(R.id.actionImage)
        actionVoice = findViewById(R.id.actionVoice)

        pttPanel = findViewById(R.id.pttPanel)
        tvPttHint = findViewById(R.id.tvPttHint)
        pttPressArea = findViewById(R.id.pttPressArea)
        tvPttChannelLabel = findViewById(R.id.tvPttChannelLabel)
        tvPttFrequency = findViewById(R.id.tvPttFrequency)
        tvPttState = findViewById(R.id.tvPttState)
        tvPttSubState = findViewById(R.id.tvPttSubState)
        btnPttPrevChannel = findViewById(R.id.btnPttPrevChannel)
        btnPttNextChannel = findViewById(R.id.btnPttNextChannel)

        smartLinkPanel = findViewById(R.id.smartLinkPanel)
        switchSmartLinkMode = findViewById(R.id.switchSmartLinkMode)
        tvSmartLinkStatus = findViewById(R.id.tvSmartLinkStatus)
        btnSmartLinkCompatibility = findViewById(R.id.btnSmartLinkCompatibility)
        rowSmartLinkPttSetting = findViewById(R.id.rowSmartLinkPttSetting)
        tvSmartLinkPttSettingValue = findViewById(R.id.tvSmartLinkPttSettingValue)
        tvSmartLinkNotice = findViewById(R.id.tvSmartLinkNotice)

        readWritePanel = findViewById(R.id.readWritePanel)
        tvReadWriteHint = findViewById(R.id.tvReadWriteHint)
        tvReadWriteDirtyState = findViewById(R.id.tvReadWriteDirtyState)
        btnWriteCodeplug = findViewById(R.id.btnWriteCodeplug)
        hsvReadWriteHeader = findViewById(R.id.hsvReadWriteHeader)
        hsvReadWriteBody = findViewById(R.id.hsvReadWriteBody)
        rvReadWriteFixedChannel = findViewById(R.id.rvReadWriteFixedChannel)
        rvReadWriteRows = findViewById(R.id.rvReadWriteRows)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            statusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            topBar.updatePadding(
                left = topBar.paddingLeft,
                top = statusBarInsetTop + dp(2),
                right = topBar.paddingRight,
                bottom = dp(2)
            )
            chatPanel.updatePadding(bottom = if (imeBottom > 0) imeBottom else 0)
            pttPanel.updatePadding(bottom = if (imeBottom > 0) imeBottom else 0)
            renderTopBar()
            insets
        }
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun setupHome() {
        homeUiController = HomeUiController(
            scope = lifecycleScope,
            rvDevices = rvDevices,
            fullScreenLoadingSpinner = fullScreenLoadingSpinner,
            btnEditChannel = btnEditChannel,
            btnFeatureSettings = btnFeatureSettings,
            btnDualWatchFocus = btnDualWatchFocus,
            channelGrid = channelGrid,
            tvCurrentChannel = tvCurrentChannel,
            tvFrequencyMain = tvFrequencyMain,
            tvFrequencySub = tvFrequencySub,
            getKnownDevices = { knownDevices },
            getBleState = { bleState },
            getSelectedDeviceAddress = { selectedDeviceAddress },
            getConnectingDeviceAddress = { connectingDeviceAddress },
            getCurrentCh = { currentCh },
            setCurrentCh = { currentCh = it },
            getFeatureSettings = { featureSettings },
            getDualWatchFocus = { dualWatchFocus },
            setDualWatchFocus = { dualWatchFocus = it },
            getDualWatchChannelA = { dualWatchChannelA },
            getDualWatchChannelB = { dualWatchChannelB },
            setAllowStatusChannelSync = { allowStatusChannelSync = it },
            channels = channels,
            showDeviceEntryDialog = ::showDeviceEntryDialog,
            showRenameDeviceDialog = ::showRenameDeviceDialog,
            disconnectCurrentKeepDevice = { disconnectCurrentKeepDevice() },
            confirmDeleteDevice = ::confirmDeleteDevice,
            showChannelEditDialog = ::showChannelEditDialog,
            enterFeatureSettings = {
                lifecycleScope.launch {
                    if (bleState == BleSessionState.Ready) {
                        showLoadingOnly = true
                        fullScreenLoadingText = getString(R.string.device_entry_loading)
                        renderAll()
                        protocolExecutor.queryFeatureSettingsState()
                        delay(240)
                        showLoadingOnly = false
                    }
                    talkStage = TalkStage.FeatureSettings
                    renderAll()
                }
            },
            switchDualWatchFocus = { protocolExecutor.switchDualWatchFocus(it) },
            renderFrequencyCard = ::renderFrequencyCard,
            renderChannelGridSelection = ::renderChannelGridSelection,
            populateChannelGrid = ::populateChannelGrid,
        )
        homeUiController.bind()
        deviceAdapter = homeUiController.deviceAdapter
    }

    private fun setupFeatureSettings() {
        featureSettingsController = FeatureSettingsController(
            scope = lifecycleScope,
            protocolExecutor = protocolExecutor,
            tabChannel = tvFeatureTabChannel,
            tabSettings = tvFeatureTabSettings,
            rowDualWatch = rowFeatureDualWatch,
            tvDualWatchValue = tvFeatureDualWatchValue,
            rowPromptLanguage = rowFeaturePromptLanguage,
            tvPromptLanguageValue = tvFeaturePromptLanguageValue,
            switchPromptTone = switchFeaturePromptTone,
            rowVolume = rowFeatureVolume,
            tvVolumeValue = tvFeatureVolumeValue,
            rowSquelch = rowFeatureSquelch,
            tvSquelchValue = tvFeatureSquelchValue,
            rowTot = rowFeatureTot,
            tvTotValue = tvFeatureTotValue,
            switchVox = switchFeatureVox,
            rowVoxSensitivity = rowFeatureVoxSensitivity,
            tvVoxSensitivityLabel = tvFeatureVoxSensitivityLabel,
            tvVoxSensitivityValue = tvFeatureVoxSensitivityValue,
            tvVoxSensitivityArrow = tvFeatureVoxSensitivityArrow,
            switchTxInhibit = switchFeatureTxInhibit,
            rowTxInterval = rowFeatureTxInterval,
            tvTxIntervalValue = tvFeatureTxIntervalValue,
            switchNoiseReduction = switchFeatureNoiseReduction,
            showChoiceDialog = dialogUiController::showChoiceDialog,
            getState = { featureSettings },
            setState = { featureSettings = it },
            getCurrentCh = { currentCh },
            getDualWatchChannelA = { dualWatchChannelA },
            setDualWatchChannelA = { dualWatchChannelA = it },
            getDualWatchChannelB = { dualWatchChannelB },
            setDualWatchChannelB = { dualWatchChannelB = it },
            setDualWatchFocus = { dualWatchFocus = it },
            onTabsChanged = {
                talkStage = it
                renderAll()
            },
            onDualWatchDisabled = { channelA ->
                currentCh = channelA
                allowStatusChannelSync = false
                renderFrequencyCard()
                renderChannelGridSelection()
            },
            onRenderHome = ::renderHome,
        )
        featureSettingsController.bind()
        featureSettingsController.render(featureSettings)
    }

    private fun setupChat() {
        chatUiController = ChatUiController(
            scope = lifecycleScope,
            rvMessages = rvMessages,
            etDraft = etDraft,
            btnOpenPtt = btnOpenPtt,
            btnToggleOptions = btnToggleOptions,
            chatOptionsRow = chatOptionsRow,
            actionHelp = actionHelp,
            actionLocation = actionLocation,
            actionImage = actionImage,
            actionVoice = actionVoice,
            tvChatBanner = tvChatBanner,
            getBleState = { bleState },
            channels = channels,
            getCurrentCh = { currentCh },
            getChatMenuExpanded = { chatMenuExpanded },
            setChatMenuExpanded = { chatMenuExpanded = it },
            hasRecordAudioPermission = ::hasRecordAudioPermission,
            requestVoicePermissionLauncher = requestVoicePermission,
            setPendingVoicePermissionAction = { pendingVoicePermissionAction = it },
            hasDraftContent = ::hasDraftContent,
            fitsUtf8ByteLimit = ::fitsUtf8ByteLimit,
            maxOfflineTextUtf8Bytes = MAX_OFFLINE_TEXT_UTF8_BYTES,
            loadOlderChatMessages = ::loadOlderChatMessages,
            hasOlderChatMessages = { chatHistoryController.hasOlderChatMessages },
            isLoadingOlderChatMessages = { chatHistoryController.isLoadingOlderChatMessages },
            sendDraftMessage = ::sendDraftMessage,
            triggerLocationShare = ::triggerLocationShare,
            showImageSourceDialog = { chatInteractionController.showImageSourceDialog() },
            showVoiceRecordDialog = { chatInteractionController.showVoiceRecordDialog() },
            hideKeyboard = ::hideKeyboard,
            enterPtt = {
                protocolExecutor.enterPttPreflight()
                talkStage = TalkStage.Ptt
            },
            renderAll = ::renderAll,
            renderChatComposer = ::renderChatComposer,
            isVoicePlaying = { id ->
                ::chatInteractionController.isInitialized && chatInteractionController.isVoicePlaying(id)
            },
            isVoicePlayingAlternate = { id ->
                ::chatInteractionController.isInitialized && chatInteractionController.isVoicePlayingAlternate(id)
            },
            onVoiceClick = ::handleVoiceMessageClick,
            onImageClick = ::handleImageMessageClick,
        )
        chatUiController.bind()
        chatAdapter = chatUiController.chatAdapter
    }

    private fun setupPtt() {
        pttUiController = PttUiController(
            context = this,
            scope = lifecycleScope,
            protocolExecutor = protocolExecutor,
            pttSender = pttSender,
            getBleState = { bleState },
            channels = channels,
            getCurrentCh = { currentCh },
            setCurrentCh = { currentCh = it },
            getFeatureSettings = { featureSettings },
            getDualWatchFocus = { dualWatchFocus },
            getDualWatchChannelA = { dualWatchChannelA },
            setDualWatchChannelA = { dualWatchChannelA = it },
            getDualWatchChannelB = { dualWatchChannelB },
            setDualWatchChannelB = { dualWatchChannelB = it },
            setAllowStatusChannelSync = { allowStatusChannelSync = it },
            renderFrequencyCard = ::renderFrequencyCard,
            renderChannelGridSelection = ::renderChannelGridSelection,
            tvPttHint = tvPttHint,
            pttPressArea = pttPressArea,
            tvPttChannelLabel = tvPttChannelLabel,
            tvPttFrequency = tvPttFrequency,
            tvPttState = tvPttState,
            tvPttSubState = tvPttSubState,
            btnPttPrevChannel = btnPttPrevChannel,
            btnPttNextChannel = btnPttNextChannel,
            channelGrid = channelGrid,
            dp = ::dp,
        )
        pttUiController.bind()
    }

    private fun setupSmartLink() {
        smartLinkUiController = SmartLinkUiController(
            context = this,
            scope = lifecycleScope,
            protocolExecutor = protocolExecutor,
            getBleState = { bleState },
            switchSmartLinkMode = switchSmartLinkMode,
            tvSmartLinkStatus = tvSmartLinkStatus,
            btnSmartLinkCompatibility = btnSmartLinkCompatibility,
            rowSmartLinkPttSetting = rowSmartLinkPttSetting,
            tvSmartLinkPttSettingValue = tvSmartLinkPttSettingValue,
            getSmartLinkEnabled = { smartLinkEnabled },
            setSmartLinkEnabled = { smartLinkEnabled = it },
            getMainPttTarget = { smartLinkMainPttTarget },
            setMainPttTarget = { smartLinkMainPttTarget = it },
        )
        smartLinkUiController.bind()
    }

    private fun setupReadWrite() {
        readWriteFixedAdapter = ReadWriteChannelFixedAdapter(
            onClick = { channel -> showReadWriteChannelEditDialog(channel) }
        )
        readWriteTableAdapter = ReadWriteChannelTableAdapter(
            onClick = { channel -> showReadWriteChannelEditDialog(channel) }
        )
        rvReadWriteFixedChannel.layoutManager = LinearLayoutManager(this)
        rvReadWriteFixedChannel.adapter = readWriteFixedAdapter
        rvReadWriteRows.layoutManager = LinearLayoutManager(this)
        rvReadWriteRows.adapter = readWriteTableAdapter
        rvReadWriteFixedChannel.itemAnimator = null
        rvReadWriteRows.itemAnimator = null
        readWriteUiController = ReadWriteUiController(
            context = this,
            scope = lifecycleScope,
            protocolExecutor = protocolExecutor,
            getBleState = { bleState },
            getReadWriteLoaded = { readWriteLoaded },
            setReadWriteLoaded = { readWriteLoaded = it },
            channels = channels,
            readWriteChannels = readWriteChannels,
            readWriteBaselineChannels = readWriteBaselineChannels,
            fixedAdapter = readWriteFixedAdapter,
            tableAdapter = readWriteTableAdapter,
            selectorAdapterFactory = { onChannelChosen -> ReadWriteSelectorChannelAdapter(onChannelChosen) },
            renderFrequencyCard = ::renderFrequencyCard,
            renderChannelGridSelection = ::renderChannelGridSelection,
            renderReadWrite = ::renderReadWrite,
            renderAll = ::renderAll,
            setAllowStatusChannelSync = { allowStatusChannelSync = it },
            queryBootstrapAndCurrentChannel = { tag ->
                protocolExecutor.queryBootstrap(tag)
                delay(120)
                protocolExecutor.queryCurrentChannelInfo()
            },
            setShowLoading = { visible, text ->
                showLoadingOnly = visible
                if (text.isNotBlank()) fullScreenLoadingText = text
            },
            styleDialog = ::styleDialog,
            buildDialogListAdapter = ::buildDialogListAdapter,
        )
        readWriteUiController.bindScrollSync(
            hsvReadWriteHeader = hsvReadWriteHeader,
            hsvReadWriteBody = hsvReadWriteBody,
            rvReadWriteFixedChannel = rvReadWriteFixedChannel,
            rvReadWriteRows = rvReadWriteRows,
        )
        btnWriteCodeplug.setOnClickListener {
            showReadWriteSelectorDialog()
        }
        renderReadWrite()
    }

    private fun setupTopBar() {
        mainChromeController.bindTopBar()
    }

    private fun setupSystemBack() {
        mainChromeController.bindSystemBack(this, onBackPressedDispatcher)
    }

    private suspend fun handleAppBack(): Boolean {
        return appNavigationController.handleAppBack()
    }

    private fun setupRxCallback() {
        session.onRxPayload = { packet ->
            runOnUiThread {
                bleEventController.handleRxPacket(packet)
            }
        }
    }

    private fun observeBleState() {
        bleStateController.observe()
    }

    private fun renderAll() {
        if (!::mainUiRenderController.isInitialized) return
        mainUiRenderController.renderAll()
    }

    private fun renderTopBar() {
        if (!::mainUiRenderController.isInitialized) return
        mainUiRenderController.renderTopBar()
    }

    private fun renderConnection() {
        if (!::mainUiRenderController.isInitialized) return
        mainUiRenderController.renderConnection()
    }

    private fun renderHome() {
        if (!::mainUiRenderController.isInitialized) return
        mainUiRenderController.renderHome()
    }

    private fun renderFeatureSettings() {
        if (!::featureSettingsController.isInitialized) return
        featureSettingsController.render(featureSettings)
    }

    private fun renderSmartLink() {
        if (!::smartLinkUiController.isInitialized) return
        smartLinkUiController.render()
    }

    private fun renderReadWrite() {
        if (!::mainUiRenderController.isInitialized) return
        mainUiRenderController.renderReadWrite()
    }

    private fun syncReadWriteFromDevice() {
        readWriteUiController.syncFromDevice()
    }

    private fun showReadWriteSelectorDialog() {
        readWriteUiController.showSelectorDialog(::showReadWriteChannelEditDialog)
    }

    private suspend fun refreshSmartLinkState() {
        if (!::smartLinkUiController.isInitialized) return
        smartLinkUiController.refreshState()
    }

    private fun scanDevicesFromUserAction() {
        deviceConnectionController.scanDevicesFromUserAction()
    }

    private fun continueScanDevicesFromUserAction() {
        deviceConnectionController.continueScanDevicesFromUserAction()
    }

    private fun showDeviceEntryDialog(device: BleScanDevice) {
        deviceConnectionController.showEntryDialog(device)
    }

    private fun showChannelEditDialog(channel: Int) {
        readWriteUiController.showHomeChannelEditDialog(channel)
    }

    private fun showReadWriteChannelEditDialog(channel: Int) {
        readWriteUiController.showReadWriteChannelEditDialog(channel)
    }

    private fun upsertKnownDevice(device: BleScanDevice) {
        knownDevicesController.upsertKnownDevice(device)
    }

    private fun reconnectKnownDevice(device: BleScanDevice, openDialogAfterConnect: Boolean) {
        deviceConnectionController.reconnectKnownDevice(device, openDialogAfterConnect)
    }

    private fun loadKnownDevices() {
        knownDevicesController.loadKnownDevices()
    }

    private fun autoReconnectLastDevice() {
        knownDevicesController.autoReconnectLastDevice()
    }

    private fun confirmDeleteDevice(device: BleScanDevice) {
        knownDevicesController.confirmDeleteDevice(device)
    }

    private fun showRenameDeviceDialog(device: BleScanDevice) {
        knownDevicesController.showRenameDeviceDialog(device)
    }

    private fun isLocationServiceEnabled(): Boolean {
        return appPermissionController.isLocationServiceEnabled()
    }

    private fun triggerLocationShare(kind: PendingLocationShareKind) {
        locationShareController.triggerLocationShare(kind)
    }

    private fun parseSpecialText(text: String): ParsedSpecialText? {
        return locationShareController.parseSpecialText(text)
    }

    private fun hideKeyboard() {
        currentFocus?.let { focus ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(focus.windowToken, 0)
            focus.clearFocus()
        }
    }

    private fun renderFrequencyCard() {
        if (::homeUiController.isInitialized) {
            homeUiController.renderFrequencyCard()
        }
    }

    private fun renderChannelGridSelection() {
        if (::homeUiController.isInitialized) {
            homeUiController.renderChannelGridSelection()
        }
    }

    private fun renderChat(scrollToBottom: Boolean = false) {
        renderChatBanner()
        renderChatComposer()
        chatFlowController.submitToAdapter(scrollToBottom)
    }

    private fun appendChatMessage(message: OfflineChatMessage) {
        chatFlowController.appendChatMessage(message)
    }

    private fun replaceChatMessage(id: Long, message: OfflineChatMessage, scrollToBottom: Boolean = false) {
        chatFlowController.replaceChatMessage(id, message, scrollToBottom)
    }

    private fun updateChatMessageProgress(id: Long, progress: Int) {
        chatFlowController.updateChatMessageProgress(id, progress)
    }

    private fun removeChatMessage(id: Long) {
        chatFlowController.removeChatMessage(id)
    }

    private fun cancelOutgoingChatTransfers() {
        chatInteractionController.cancelOutgoingTransfers()
    }
    private fun renderChatBanner() {
        if (::chatUiController.isInitialized) {
            chatUiController.renderChatBanner()
        }
    }

    private fun renderChatComposer() {
        if (::chatUiController.isInitialized) {
            chatUiController.renderChatComposer()
        }
    }

    private fun hasDraftContent(): Boolean =
        chatInteractionController.hasDraftContent(etDraft.text?.toString())

    private fun fitsUtf8ByteLimit(text: String, maxBytes: Int): Boolean =
        text.toByteArray(Charsets.UTF_8).size <= maxBytes
    private fun renderPtt() {
        if (::pttUiController.isInitialized) {
            pttUiController.render()
        }
    }

    private fun populateChannelGrid() {
        if (::pttUiController.isInitialized) {
            pttUiController.populateChannelGrid()
        }
    }

    private fun disconnectCurrentKeepDevice() {
        appNavigationController.disconnectCurrentKeepDevice()
    }

    private fun sendDraftMessage() {
        val content = etDraft.text.toString().trim()
        chatInteractionController.sendDraftMessage(
            content = content,
            clearDraft = { etDraft.setText("") },
            restoreDraft = {
                etDraft.setText(content)
                etDraft.setSelection(content.length)
            }
        )
    }

    private fun sendStructuredTextMessage(
        rawText: String,
        kind: ChatMessageKind,
        title: String,
        body: String = "",
    ) {
        chatInteractionController.sendStructuredTextMessage(rawText, kind, title, body)
    }

    private fun promptAppPermissionsOnStartup() {
        appPermissionController.promptAppPermissionsOnStartup()
    }

    private fun nextChatLocalId(): Long = (chatMessages.maxOfOrNull { it.id } ?: System.currentTimeMillis()) + 1

    private fun allocateOfflineMessageId(username: String): UInt {
        return chatInteractionController.allocateOfflineMessageId(username)
    }

    private fun currentChatStorageKey(): String = PREF_CHAT_HISTORY_PREFIX

    private suspend fun loadChatMessagesForCurrentThread() {
        chatHistoryController.loadCurrentThread()
    }

    private fun loadOlderChatMessages() {
        chatHistoryController.loadOlderMessages()
    }

    private fun persistChatMessage(message: OfflineChatMessage) {
        chatHistoryController.persistChatMessage(message)
    }

    private fun deleteChatMessage(id: Long) {
        chatHistoryController.deleteChatMessage(id)
    }

    private fun handleVoiceMessageClick(message: OfflineChatMessage) {
        chatInteractionController.handleVoiceMessageClick(message)
    }

    private fun handleImageMessageClick(message: OfflineChatMessage) {
        chatInteractionController.handleImageMessageClick(message)
    }

    private fun showChatUsernameDialog() {
        chatInteractionController.showChatUsernameDialog()
    }

    private fun clearChatHistory() {
        chatHistoryController.clearChatHistory()
    }
    private fun buildLoadingDialogView(title: String): View =
        dialogUiController.buildLoadingDialogView(title)

    private fun buildDialogListAdapter(items: List<String>): ArrayAdapter<String> =
        dialogUiController.buildDialogListAdapter(items)

    private fun styleDialog(dialog: AlertDialog) {
        dialogUiController.styleDialog(dialog)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun hasScanPermissions(): Boolean =
        appPermissionController.hasScanPermissions()

    private fun hasRecordAudioPermission(): Boolean =
        appPermissionController.hasRecordAudioPermission()

    private fun scanPermissions(): Array<String> =
        appPermissionController.scanPermissions()
}

