package com.byf3332.at2ht

data class ChannelUiState(val rxMhz: Double? = null, val txMhz: Double? = null)

enum class TalkStage { Main, Connection, FeatureSettings, Chat, Ptt }

enum class AppSection { Offline, SmartLink, ReadWrite }

enum class DeviceEntryTarget { Control, ReadWrite, Offline, SmartLink }

enum class PendingLocationShareKind { Location, Help }
