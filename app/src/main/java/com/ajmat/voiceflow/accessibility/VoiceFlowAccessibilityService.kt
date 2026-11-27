package com.ajmat.voiceflow.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        VoiceFlowLog.info(this, "Paste helper accessibility service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally do not read events or window text.
    }

    override fun onInterrupt() {
        VoiceFlowLog.warn(this, "Paste helper was interrupted by Android.")
    }

    fun pasteIntoFocusedField(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        VoiceFlowLog.info(this, "Clipboard paste action result=" + pasted + ".")
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
