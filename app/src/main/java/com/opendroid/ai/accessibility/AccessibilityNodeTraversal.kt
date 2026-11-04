package com.opendroid.ai.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject

/**
 * Pure node-tree traversal/matching logic used by [OpenDroidAccessibilityService],
 * extracted so it can be exercised in tests without a bound accessibility service.
 *
 * Every function here takes the root [AccessibilityNodeInfo] as a parameter instead
 * of reading `rootInActiveWindow` itself, so a test can hand it either the real
 * service's root or a root obtained straight from `UiAutomation` - same behavior,
 * no service required. See #66 (prototype) / #105 (this extraction).
 *
 * Stateless and dependency-free: production wires it in through Hilt, tests can
 * just `AccessibilityNodeTraversal()` it directly.
 *
 * Callers remain responsible for recycling the root node they obtained; this class
 * only recycles the intermediate/child nodes it creates while walking.
 */
class AccessibilityNodeTraversal @Inject constructor() {

    /** Concatenates the text/contentDescription of every node in the tree, depth-first. */
    fun screenText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val nodeText = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        if (!nodeText.isNullOrEmpty()) {
            sb.append(nodeText).append("\n")
        } else if (!contentDesc.isNullOrEmpty()) {
            sb.append(contentDesc).append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
            child.recycle()
        }
    }

    /**
     * Finds the first node matching [text] (or the nearest clickable ancestor,
     * for leaves that aren't themselves clickable) and performs ACTION_CLICK on it.
     * Returns whether a click was dispatched.
     */
    fun findAndClick(root: AccessibilityNodeInfo?, text: String): Boolean {
        if (root == null) return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return true
                }
                parent = parent.parent
            }
            node.recycle()
        }
        return false
    }

    /**
     * Walks the tree depth-first for a clickable node whose text or
     * contentDescription (case-insensitive) is one of [labels], and clicks it.
     * This is the IME-submit fallback used when ACTION_IME_ENTER isn't
     * available or fails.
     */
    fun findAndClickSubmitControl(root: AccessibilityNodeInfo?, labels: List<String>): Boolean {
        if (root == null) return false
        return findSubmitNode(root, labels)
    }

    /**
     * Universal submit matcher for local AI clients. It first uses exact semantic labels and then
     * falls back to a clickable ImageView/Button positioned next to an editable input.
     */
    fun findAndClickUniversalSubmitControl(root: AccessibilityNodeInfo?, labels: List<String>): Boolean {
        if (root == null) return false
        if (findSubmitNode(root, labels)) return true
        val editableBounds = mutableListOf<Rect>()
        collectEditableBounds(root, editableBounds)
        return findNearbySubmitNode(root, editableBounds)
    }

    /** Captures the last visible TextView/RecyclerView-backed response, with a tree-text fallback. */
    fun lastResponseText(root: AccessibilityNodeInfo?, prompt: String): String {
        if (root == null) return ""
        val candidates = mutableListOf<String>()
        collectResponseNodes(root, candidates)
        val normalizedPrompt = prompt.trim()
        return candidates.asReversed()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != normalizedPrompt && it.length >= 2 }
            ?: screenText(root)
    }

    private fun findSubmitNode(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        if (node.isClickable && labels.any { label ->
                text?.equals(label.trim(), ignoreCase = true) == true ||
                    contentDesc?.equals(label.trim(), ignoreCase = true) == true
            }) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findSubmitNode(child, labels)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun collectEditableBounds(node: AccessibilityNodeInfo, result: MutableList<Rect>) {
        if (node.isEditable && node.isVisibleToUser) {
            result += Rect().also(node::getBoundsInScreen)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditableBounds(child, result)
            child.recycle()
        }
    }

    private fun findNearbySubmitNode(node: AccessibilityNodeInfo, editableBounds: List<Rect>): Boolean {
        val className = node.className?.toString().orEmpty()
        val description = node.contentDescription?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.lowercase().orEmpty()
        val isImageOrButton = className.contains("ImageView", ignoreCase = true) ||
            className.contains("Button", ignoreCase = true)
        if (node.isClickable && isImageOrButton && (description.contains("send") || text.contains("send") || editableBounds.any { input ->
                val bounds = Rect().also(node::getBoundsInScreen)
                val nearHorizontally = bounds.left >= input.right - 600 && bounds.right <= input.right + 900
                val nearVertically = kotlin.math.abs(bounds.centerY() - input.centerY()) <= 220
                nearHorizontally && nearVertically
            })) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findNearbySubmitNode(child, editableBounds)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun collectResponseNodes(node: AccessibilityNodeInfo, result: MutableList<String>) {
        val className = node.className?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        if (text.isNotBlank() && (className.contains("TextView", ignoreCase = true) ||
                className.contains("RecyclerView", ignoreCase = true))) {
            result += text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectResponseNodes(child, result)
            child.recycle()
        }
    }
}
