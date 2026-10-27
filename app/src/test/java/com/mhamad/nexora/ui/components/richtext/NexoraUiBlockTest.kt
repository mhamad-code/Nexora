package com.mhamad.nexora.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexoraUiBlockTest {
    @Test fun parsesEverySupportedType() {
        val inputs = listOf(
            "{\"type\":\"form\",\"title\":\"Q\",\"questions\":[{\"id\":\"x\",\"question\":\"?\",\"type\":\"text\"}]}",
            "{\"type\":\"options\",\"title\":\"Pick\",\"items\":[{\"title\":\"A\"}]}",
            "{\"type\":\"cards\",\"title\":\"Info\",\"items\":[{\"title\":\"A\"}]}",
            "{\"type\":\"links\",\"title\":\"Read\",\"items\":[{\"title\":\"A\",\"url\":\"https://example.com\"}]}",
            "{\"type\":\"flow\",\"title\":\"Steps\",\"steps\":[{\"title\":\"A\"}]}",
            "{\"type\":\"tree\",\"title\":\"Files\",\"items\":[{\"name\":\"src\",\"kind\":\"folder\",\"children\":[]}]}",
        )
        inputs.forEach { assertEquals(1, parseNexoraUi(it)?.size) }
    }

    @Test fun rejectsInvalidUnknownAndMalformedJson() {
        assertNull(parseNexoraUi("not json"))
        assertNull(parseNexoraUi("{\"type\":\"unknown\",\"title\":\"x\"}"))
        assertNull(parseNexoraUi("{\"type\":\"links\",\"title\":\"x\",\"items\":[{\"title\":\"x\",\"url\":\"javascript:alert(1)\"}]}"))
    }

    @Test fun appliesItemAndTreeDepthLimits() {
        val items = (1..20).joinToString(",") { "{\"title\":\"$it\"}" }
        val parsed = parseNexoraUi("{\"type\":\"cards\",\"title\":\"x\",\"items\":[$items]}")?.single() as NexoraUi.Cards
        assertEquals(8, parsed.items.size)
        val deep = "{\"type\":\"tree\",\"title\":\"x\",\"items\":[{\"name\":\"a\",\"kind\":\"folder\",\"children\":[{\"name\":\"b\",\"kind\":\"folder\",\"children\":[{\"name\":\"c\",\"kind\":\"folder\",\"children\":[{\"name\":\"d\",\"kind\":\"file\"}]}]}]}]}"
        val tree = parseNexoraUi(deep)?.single() as NexoraUi.Tree
        assertTrue(tree.items.single().children.single().children.single().children.isEmpty())
    }

    @Test fun truncatesHostileStringsAndRejectsUnsafeImageUrls() {
        val long = "x".repeat(5000)
        val parsed = parseNexoraUi("{\"type\":\"cards\",\"title\":\"$long\",\"items\":[{\"title\":\"$long\"}]}")?.single() as NexoraUi.Cards
        assertEquals(1000, parsed.title.length)
        assertNull(validateModelImageUrl("http://localhost/image.png"))
        assertNull(validateModelImageUrl("javascript:alert(1)"))
        assertTrue(validateModelImageUrl("https://example.com/image.png") != null)
    }
}
