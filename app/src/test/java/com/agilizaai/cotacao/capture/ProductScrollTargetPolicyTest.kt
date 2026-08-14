package com.agilizaai.cotacao.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductScrollTargetPolicyTest {
    @Test
    fun `rejects order ViewPager even though Android marks it scrollable`() {
        assertEquals(
            -1,
            ProductScrollTargetPolicy.priority(
                viewId = "br.com.ionsistemas.ionvendas:id/view_pager_pedido",
                className = "androidx.viewpager.widget.ViewPager",
                supportsScroll = true
            )
        )
    }

    @Test
    fun `selects real vertical ION product list before its wrappers`() {
        val list = ProductScrollTargetPolicy.priority(
            "br.com.ionsistemas.ionvendas:id/listViewInner",
            "android.widget.ListView",
            supportsScroll = true
        )
        val infiniteWrapper = ProductScrollTargetPolicy.priority(
            "br.com.ionsistemas.ionvendas:id/infiniteListView",
            "android.widget.FrameLayout",
            supportsScroll = true
        )
        val refreshWrapper = ProductScrollTargetPolicy.priority(
            "br.com.ionsistemas.ionvendas:id/swipeRefreshLayout",
            "android.view.ViewGroup",
            supportsScroll = true
        )

        assertEquals(300, list)
        assertEquals(200, infiniteWrapper)
        assertEquals(100, refreshWrapper)
    }

    @Test
    fun `does not use arbitrary scrollable nodes or inert wrappers`() {
        assertEquals(
            -1,
            ProductScrollTargetPolicy.priority(
                "br.com.ionsistemas.ionvendas:id/randomRecycler",
                "androidx.recyclerview.widget.RecyclerView",
                supportsScroll = true
            )
        )
        assertEquals(
            -1,
            ProductScrollTargetPolicy.priority(
                "br.com.ionsistemas.ionvendas:id/swipeRefreshLayout",
                "android.view.ViewGroup",
                supportsScroll = false
            )
        )
    }
}
