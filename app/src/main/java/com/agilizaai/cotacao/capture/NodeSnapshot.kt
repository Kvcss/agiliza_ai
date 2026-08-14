package com.agilizaai.cotacao.capture

data class NodeSnapshot(
    val text: String,
    val viewId: String,
    val className: String,
    val packageName: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val childCount: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

