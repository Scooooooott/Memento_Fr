package com.scott.frenchvocab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.frenchvocab.data.content.ContentRepository
import com.scott.frenchvocab.data.user.StudyRepository
import com.scott.frenchvocab.domain.DEFAULT_BOOK_ID
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
            assertTrue(content.wordCount > 0)
            val etre = content.find("fr:être:verb:1")
            assertNotNull(etre)
            assertTrue(etre!!.senses.first().chinese.isNotBlank())
            assertTrue(etre.examples.first().chinese.isNotBlank())
            val sample = content.allUids().take(100).mapNotNull(content::find)
            assertTrue(sample.flatMap { it.senses }.all { it.chinese.isNotBlank() })
            assertTrue(sample.flatMap { it.examples }.all { it.chinese.isNotBlank() })
            assertEquals(content.wordCount, content.allUids().toSet().size)
            assertTrue(content.books().all { book -> book.lexemeUids.all(content::contains) })
        }
    }

    @Test fun bundledCatalogPagesByFiftyAndSearchesBeyondTheFirstPage() {
        ContentRepository(instrumentation.targetContext).use { content ->
            val first = content.browse("", "", false, emptySet(), 0, 50)
            val second = content.browse("", "", false, emptySet(), 50, 50)
            val third = content.browse("", "", false, emptySet(), 100, 50)
            assertEquals(50, first.items.size)
            assertEquals(50, second.items.size)
            assertEquals(50, third.items.size)
            assertTrue(first.hasMore)
            assertTrue(first.items.map { it.uid }.toSet().intersect(second.items.map { it.uid }.toSet()).isEmpty())
            assertTrue(second.items.map { it.uid }.toSet().intersect(third.items.map { it.uid }.toSet()).isEmpty())
            val target = second.items.first()
            val searched = content.browse("", target.lemma, false, emptySet(), 0, 50)
            assertTrue(searched.items.any { it.uid == target.uid })
            val favorite = content.browse("", "", true, setOf(target.uid), 0, 50)
            assertEquals(listOf(target.uid), favorite.items.map { it.uid })
        }
    }

    @Test fun examplesRemainOwnedByTheirDatabaseSense() {
        load("chinese").use { content ->
            val word = content.find("fr:être:verb:1")!!
            assertTrue(word.senses.first().examples.isNotEmpty())
            assertEquals(word.senses.flatMap { it.examples }, word.examples)
        }
    }

    @Test fun freshInstallUsesBundledA1FrequencyBook() {
        val context = instrumentation.targetContext
        context.deleteDatabase("french_user.db")
        try {
            ContentRepository(context).use { content ->
                StudyRepository(context, content).use { study ->
                    assertEquals(DEFAULT_BOOK_ID, study.snapshot().settings.bookId)
                }
            }
        } finally {
            context.deleteDatabase("french_user.db")
        }
    }
}
