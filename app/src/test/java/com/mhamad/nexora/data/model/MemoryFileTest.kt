package com.mhamad.nexora.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryFileTest {
    private fun file(content: String, path: String = "/topics/food.md") =
        MemoryFile(path = path, content = content, updatedAt = 0)

    private fun MemoryFile.edited(
        title: String = this.title,
        description: String = this.description,
        aliases: List<String> = this.aliases,
        body: String = editableBody,
    ) = file(contentWith(title, description, aliases, body), path)

    @Test
    fun `composed content uses the layout the memory format describes`() {
        val content = file("").contentWith(
            title = "نظام غذائي",
            description = "تفضيلات ونواهي غذائية",
            aliases = listOf("مأكولات", "food"),
            body = "- [stated] لا يأكل الكزبرة",
        )

        assertEquals(
            """
            ---
            name: food
            title: نظام غذائي
            description: تفضيلات ونواهي غذائية
            aliases: [مأكولات, food]
            ---

            - [stated] لا يأكل الكزبرة

            """.trimIndent(),
            content,
        )
    }

    @Test
    fun `values that are not plain yaml scalars survive a round trip`() {
        val description = "Covers: food, #tags and \"quotes\" - " + "a long sentence ".repeat(12).trim()
        val edited = file("").edited(
            title = "2024",
            description = description,
            aliases = listOf("a, b", "[x]", "yes"),
            body = "body",
        )

        assertEquals("2024", edited.title)
        assertEquals(description, edited.description)
        assertEquals(listOf("a, b", "[x]", "yes"), edited.aliases)
        assertEquals("body\n", edited.body)
    }

    @Test
    fun `line breaks in title and description are collapsed`() {
        val edited = file("").edited(title = " نظام غذائي\n", description = "السطر الأول\n  السطر الثاني")

        assertEquals("نظام غذائي", edited.title)
        assertEquals("السطر الأول السطر الثاني", edited.description)
    }

    @Test
    fun `empty fields are left out and name follows the path`() {
        val content = file("---\nname: wrong\ntitle: Old\ndescription: old\naliases: [a]\n---\n\nbody\n")
            .contentWith(title = "", description = " ", aliases = emptyList(), body = "body")

        assertEquals("---\nname: food\n---\n\nbody\n", content)
    }

    @Test
    fun `keys without an input field are kept`() {
        val edited = file("---\nname: food\ntitle: Food\nsource: import\n---\n\nbody\n").edited(title = "نظام غذائي")

        assertEquals("---\nname: food\ntitle: نظام غذائي\nsource: import\n---\n\nbody\n", edited.content)
    }

    @Test
    fun `saving without changes keeps a well formed file as it is`() {
        val original = file("---\nname: food\ntitle: نظام غذائي\ndescription: ذوق\naliases: [مأكولات]\n---\n\n- [stated] لا يأكل الكزبرة\n")

        assertEquals(original.content, original.edited().content)
    }

    @Test
    fun `unreadable frontmatter stays visible in the body`() {
        val broken = "---\nname: food\ndescription: Food: likes spicy\n---\n\n- [stated] لا يأكل الكزبرة\n"
        val original = file(broken)

        assertEquals(broken, original.editableBody)
        val edited = original.edited(description = "ذوق")
        assertEquals("ذوق", edited.description)
        assertEquals(broken, edited.body)
    }

    @Test
    fun `aliases containing a comma stay whole when another one is added`() {
        val original = file("---\nname: food\naliases: ['a, b', مأكولات،مشروبات]\n---\n\nbody\n")
        assertEquals(listOf("a, b", "مأكولات،مشروبات"), original.aliases)

        assertEquals(listOf("a, b", "مأكولات،مشروبات", "c"), original.edited(aliases = original.aliases + "c").aliases)
    }
}
