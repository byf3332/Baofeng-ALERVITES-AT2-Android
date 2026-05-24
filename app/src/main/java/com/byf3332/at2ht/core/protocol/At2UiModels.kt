package com.byf3332.at2ht.core.protocol

data class ChannelConfig(
    val rxMhz: Double? = null,
    val txMhz: Double? = null,
    val modeDigital: Boolean = false,
    val rxTone: Int = 0x7F,
    val rxToneType: Int = 0x00,
    val txTone: Int = 0x7F,
    val txToneType: Int = 0x00,
    val rxTonePolarity: Int = 0x00,
    val txTonePolarity: Int = 0x00,
    val hopOn: Boolean = false,
    val bandwidthNarrow: Boolean = false,
    val scanAdd: Boolean = true,
    val busyLock: Boolean = false,
    val highPower: Boolean = true,
    val encryptKey: Int = 0,
)

data class FeatureSettingsState(
    val dualWatch: Boolean = false,
    val promptLanguage: At2Commands.PromptLanguage = At2Commands.PromptLanguage.Chinese,
    val promptTone: Boolean = true,
    val volume: Int = 1,
    val squelch: Int = 1,
    val totSeconds: Int = 120,
    val voxEnabled: Boolean = false,
    val voxSensitivity: Int = 3,
    val txInhibit: Boolean = false,
    val txIntervalSeconds: Int = 0,
    val noiseReduction: Boolean = false,
)
