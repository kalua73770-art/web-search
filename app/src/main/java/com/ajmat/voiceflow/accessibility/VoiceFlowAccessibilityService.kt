package com.ajmat.voiceflow.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ajmat.voiceflow.overlay.VoiceFlowOverlayService
import com.ajmat.voiceflow.logging.VoiceFlowLog

/**
 * Opt-in helper: it does not inspect or store screen text/events. It only asks
 * the currently focused input control to paste the clipboard when the user
 * taps Paste in VoiceFlow's floating panel.
 */
class VoiceFlowAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: VoiceFlowAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        VoiceFlowLog.info(this, "Keyboard-aware paste helper connected.")
        updateKeyboardState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Detect only whether an editable field and the software keyboard are visible.
        // No view text, labels, typed content, or window titles are read or stored.
        updateKeyboardState()
    }

    fun refreshKeyboardState() = updateKeyboardState()

    private fun updateKeyboardState() {
        try {
            val visibleKeyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val focusedEditor = windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .any { window ->
                    val root = window.root ?: return@any false
                    val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    val editable = node?.isEditable == true
                    node?.recycle()
                    root.recycle()
                    editable
                }
            VoiceFlowOverlayService.onKeyboardVisibilityChanged(visibleKeyboard && focusedEditor)
        } catch (error: Exception) {
            VoiceFlowLog.warn(this, "Could not determine keyboard visibility: " + (error.message ?: "unknown error"))
        }
    }

    override fun onInterrupt() {
        VoiceFlowLog.warn(this, "Paste helper was interrupted by Android.")
    }

    fun pasteIntoFocusedField(): Boolean {
        var focused: AccessibilityNodeInfo? = null
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val candidate = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            root.recycle()
            if (candidate?.isEditable == true) {
                focused = candidate
                break
            }
            candidate?.recycle()
        }
        val target = focused ?: return false
        val pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        VoiceFlowLog.info(this, "Focused editable field paste result=" + pasted + ".")
        target.recycle()
        return pasted
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
