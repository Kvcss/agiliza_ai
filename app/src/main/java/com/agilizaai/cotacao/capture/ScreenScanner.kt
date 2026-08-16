package com.agilizaai.cotacao.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

object ScreenScanner {
    fun snapshot(root: AccessibilityNodeInfo, targetPackage: String): List<NodeSnapshot> {
        val result = ArrayList<NodeSnapshot>()
        val bounds = Rect()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || visited++ > MAX_NODES) return
            val packageName = node.packageName?.toString().orEmpty()
            val textCandidates = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.hintText?.toString()
            )
                .map(::normalizeText)
                .filter { it.isNotBlank() }
                .distinct()

            if (packageName == targetPackage) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    textCandidates.forEach { text ->
                        result += NodeSnapshot(
                            text = text,
                            viewId = node.viewIdResourceName.orEmpty(),
                            className = node.className?.toString().orEmpty(),
                            packageName = packageName,
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                            childCount = node.childCount
                        )
                    }
                }
            }

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root, 0)
        return result
            .distinctBy { "${it.text}|${it.left}|${it.top}|${it.right}|${it.bottom}" }
            .sortedWith(compareBy<NodeSnapshot> { it.top }.thenBy { it.left })
    }

    fun findBestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestPriority = -1
        var bestArea = -1L
        val bounds = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            val supportsScroll = node.isScrollable ||
                node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                }
            val priority = ProductScrollTargetPolicy.priority(
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                supportsScroll = supportsScroll
            )
            if (priority >= 0) {
                node.getBoundsInScreen(bounds)
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (priority > bestPriority || priority == bestPriority && area > bestArea) {
                    best?.recycle()
                    best = AccessibilityNodeInfo.obtain(node)
                    bestPriority = priority
                    bestArea = area
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root, 0)
        return best
    }

    fun fingerprint(nodes: List<NodeSnapshot>): String {
        val raw = nodes.joinToString("\n") { "${it.text}|${it.left}|${it.top}|${it.right}|${it.bottom}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalizeText(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()

    private const val MAX_DEPTH = 80
    private const val MAX_NODES = 5_000
}

/**
 * Restricts automatic scrolling to the vertical product-list containers exposed by ION.
 *
 * The order screen also exposes its tab ViewPager as scrollable. ACTION_SCROLL_FORWARD on
 * that node means "next tab", not "next products", so broad class/area fallbacks are unsafe.
 */
/**
 * Reconhece o prazo exibido pelo ION no cabeçalho do Novo Pedido.
 *
 * O texto chega já normalizado (maiúsculas, sem acento). O limite de dígito é o que
 * impede "28 DIAS" de ser reconhecido dentro de "128 DIAS" e trocar de prazo errado.
 */
internal object PaymentPlanPolicy {
    fun matches(normalizedText: String, days: Int): Boolean =
        Regex("(?<![0-9])$days DIAS(?![0-9])").containsMatchIn(normalizedText)

    fun matchesAny(normalizedTexts: List<String>, days: Int): Boolean =
        normalizedTexts.any { matches(it, days) }
}

internal object ProductScrollTargetPolicy {
    fun priority(viewId: String, className: String, supportsScroll: Boolean): Int {
        if (!supportsScroll) return -1
        if (className.endsWith("ViewPager") || className.endsWith("HorizontalScrollView")) return -1

        return when (viewId.substringAfterLast('/')) {
            "listViewInner" -> 300
            "infiniteListView" -> 200
            "swipeRefreshLayout" -> 100
            else -> -1
        }
    }
}
