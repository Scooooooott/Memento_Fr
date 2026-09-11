package com.scott.frenchvocab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.frenchvocab.data.content.ContentRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseContentInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun load(name: String) = ContentRepository(instrumentation.targetContext,
        instrumentation.context.assets.open("content-fixtures/$name.db").use { it.readBytes() })

    @Test fun oldBilingualBooksRemainReadableWithoutInventingChinese() {
        load("legacy").use { content ->
            assertEquals(40, content.allWords().size)
            assertTrue(content.allWords().flatMap { it.senses }.all { it.chinese.isEmpty() })
            assertTrue(content.allWords().flatMap { it.examples }.all { it.chinese.isEmpty() })
            assertEquals("to be", content.find("fr:être:verb:1")!!.senses.first().english)
        }
    }

    @Test fun optionalChineseLoadsForBothSupportedVersionsAndNullBecomesEmpty() {
        for (name in listOf("chinese", "chinese-v1")) load(name).use { content ->
            val etre = content.find("fr:être:verb:1")!!
            assertEquals("是；处于", etre.senses.first().chinese)
            assertEquals("我在家。", etre.examples.first().chinese)
            assertEquals("to be", etre.senses.first().english)
            assertTrue(content.find("fr:avoir:verb:1")!!.senses.all { it.chinese.isEmpty() })
        }
    }

    @Test fun chineseMeaningsCanArriveBeforeExampleTranslations() {
        load("chinese-senses-only").use { content ->
            val etre = content.find("fr:être:verb:1")!!
            assertEquals("是；处于", etre.senses.first().chinese)
            assertTrue(etre.examples.all { it.chinese.isEmpty() })
        }
    }

    @Test fun unknownFutureSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { load("future").close() }
    }

    @Test fun actualBundledBookLoadsCompleteChineseWithoutChangingStableIds() {
        ContentRepository(instrumentation.targetContext).use { content ->
            assertTrue(content.allWords().isNotEmpty())
            val etre = content.find("fr:être:verb:1")
            assertNotNull(etre)
            assertEquals("是；处于", etre!!.senses.first().chinese)
            assertEquals("我在家。", etre.examples.first().chinese)
            assertTrue(content.allWords().flatMap { it.senses }.all { it.chinese.isNotBlank() })
            assertTrue(content.allWords().flatMap { it.examples }.all { it.chinese.isNotBlank() })
            assertEquals(content.allWords().size, content.allWords().map { it.uid }.toSet().size)
            assertTrue(content.books().all { book -> book.lexemeUids.all { content.find(it) != null } })
        }
    }
}
