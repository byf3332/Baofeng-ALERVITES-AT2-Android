package com.byf3332.at2ht

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class MainChromeController(
    private val scope: LifecycleCoroutineScope,
    private val topBar: MaterialToolbar,
    private val menuScanId: Int,
    private val menuChatUsernameId: Int,
    private val menuChatClearId: Int,
    private val onScanRequested: () -> Unit,
    private val onChatUsernameRequested: () -> Unit,
    private val onChatClearRequested: () -> Unit,
    private val onHandleBack: suspend () -> Boolean,
) {
    fun bindTopBar() {
        topBar.menu.clear()
        topBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                menuScanId -> {
                    onScanRequested()
                    true
                }
                menuChatUsernameId -> {
                    onChatUsernameRequested()
                    true
                }
                menuChatClearId -> {
                    onChatClearRequested()
                    true
                }
                else -> false
            }
        }
        topBar.setNavigationOnClickListener {
            scope.launch {
                onHandleBack()
            }
        }
    }

    fun bindSystemBack(activity: AppCompatActivity, dispatcher: OnBackPressedDispatcher) {
        dispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                scope.launch {
                    val consumed = onHandleBack()
                    if (!consumed) {
                        isEnabled = false
                        dispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }
}
