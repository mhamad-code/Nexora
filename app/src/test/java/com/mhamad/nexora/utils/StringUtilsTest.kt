package com.mhamad.nexora.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringUtilsTest {

    @Test
    fun `extract chinese double quotes`() {
        assertEquals(listOf("مرحبًا"), "قال"مرحبًا"".extractQuotedContent())
    }

    @Test
    fun `extract chinese single quotes`() {
        assertEquals(listOf("العالم"), "العنوان هو‘العالم’".extractQuotedContent())
    }

    @Test
    fun `extract english double quotes`() {
        assertEquals(listOf("hello"), "he said \"hello\"".extractQuotedContent())
    }

    @Test
    fun `extract english single quotes`() {
        assertEquals(listOf("world"), "title is 'world'".extractQuotedContent())
    }

    @Test
    fun `extract corner brackets`() {
        assertEquals(listOf("مرحبًا"), "قال"مرحبًا"".extractQuotedContent())
    }

    @Test
    fun `extract white corner brackets`() {
        assertEquals(listOf("العالم"), "العنوان هو『العالم』".extractQuotedContent())
    }

    @Test
    fun `extract multiple quotes`() {
        assertEquals(
            listOf("مرحبًا", "العالم"),
            ""مرحبًا" و ‘العالم’".extractQuotedContent(),
        )
    }

    @Test
    fun `blank content is ignored`() {
        assertTrue(""" \"\" '  '".extractQuotedContent().isEmpty())
    }

    @Test
    fun `no quotes returns empty`() {
        assertTrue("بدون أي علامات اقتباس".extractQuotedContent().isEmpty())
    }

    @Test
    fun `extract as text joins with separator`() {
        assertEquals("مرحبًا\nالعالم", ""مرحبًا"‘العالم’".extractQuotedContentAsText())
    }

    @Test
    fun `extract as text returns null when empty`() {
        assertNull("لا توجد علامات اقتباس".extractQuotedContentAsText())
    }

    @Test
    fun `remove english brackets`() {
        assertEquals("مرحبًا أيها العالم", "مرحبًا(سرد)العالم".removeBracketedContent())
    }

    @Test
    fun `remove chinese brackets`() {
        assertEquals("مرحبًا أيها العالم", "مرحبًا(سرد)العالم".removeBracketedContent())
    }

    @Test
    fun `remove multiple brackets`() {
        assertEquals("مرحبًا أيها العالم", "مرحبًا(تعليق)العالم(ملاحظات)".removeBracketedContent())
    }

    @Test
    fun `remove brackets keeps outside text trimmed`() {
        assertEquals("مرحبًا", "(سرد) مرحبًا ".removeBracketedContent())
    }

    @Test
    fun `remove brackets does not cross bracket boundaries`() {
        assertEquals("ac", "a(b)c".removeBracketedContent())
    }

    @Test
    fun `remove brackets returns null when all removed`() {
        assertNull("(كلها تعليق راوي)".removeBracketedContent())
    }

    @Test
    fun `remove brackets returns null for blank result`() {
        assertNull("(سرد) ".removeBracketedContent())
    }

    @Test
    fun `no brackets returns original text`() {
        assertEquals("بدون أقواس", "بدون أقواس".removeBracketedContent())
    }
}
