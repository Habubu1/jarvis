package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets Jarvis read the screen and tap / type / scroll inside any app.
 * The user must switch it on once in Settings > Accessibility > Jarvis.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
    }

    private val nodes = ArrayList<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------- reading

    /** Numbered list of the visible things on screen: index|flags|text */
    @Synchronized
    fun snapshot(): String {
        nodes.clear()
        val root = rootInActiveWindow ?: return "(screen padh nahi paya)"
        val sb = StringBuilder()
        sb.append("App: ").append(root.packageName).append("\n")
        walk(root, sb)
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null || nodes.size >= 70) return
        if (n.isVisibleToUser) {
            val text = (n.text ?: n.contentDescription)
                ?.toString()?.replace("\n", " ")?.take(40) ?: ""
            if (n.isClickable || n.isEditable || n.isScrollable || text.isNotEmpty()) {
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("click")
                if (n.isEditable) flags.add("edit")
                if (n.isScrollable) flags.add("scroll")
                sb.append(nodes.size).append("|")
                    .append(flags.joinToString(",")).append("|")
                    .append(text).append("\n")
                nodes.add(n)
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), sb)
    }

    @Synchronized
    fun label(i: Int): String {
        val n = nodes.getOrNull(i) ?: return ""
        return (n.text ?: n.contentDescription)?.toString()?.take(25) ?: ""
    }

    // ---------------------------------------------------------- acting

    private fun clickNode(start: AccessibilityNodeInfo?): Boolean {
        var n = start
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.isEnabled) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            n = n.parent
            hops++
        }
        return false
    }

    @Synchronized
    fun clickIndex(i: Int): Boolean = clickNode(nodes.getOrNull(i))

    @Synchronized
    fun setText(i: Int, text: String): Boolean {
        val n = nodes.getOrNull(i) ?: return false
        if (!n.isEditable) clickNode(n)
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    @Synchronized
    fun scroll(down: Boolean): Boolean {
        val n = nodes.firstOrNull { it.isScrollable } ?: return false
        val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return n.performAction(act)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Presses the Send button of WhatsApp / SMS apps if it is on screen. */
    fun clickSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
            if (found != null) {
                for (f in found) if (clickNode(f)) return true
            }
        }
        for (t in listOf("Send", "भेजें")) {
            val found = root.findAccessibilityNodeInfosByText(t)
            if (found != null) {
                for (f in found) {
                    val c = f.contentDescription?.toString()
                    val tx = f.text?.toString()
                    val exact = (c != null && c.equals(t, ignoreCase = true)) ||
                        (tx != null && tx.equals(t, ignoreCase = true))
                    if (exact && clickNode(f)) return true
                }
            }
        }
        return false
    }
}
