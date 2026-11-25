package app.shelfhand.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NaturalOrderTest {
    @Test fun `chapter 10 plays after chapter 9`() {
        val files = listOf("Chapter 10.mp3", "Chapter 2.mp3", "Chapter 1.mp3", "chapter 9.mp3")
        assertEquals(listOf("Chapter 1.mp3", "Chapter 2.mp3", "chapter 9.mp3", "Chapter 10.mp3"), files.sortedWith(NaturalOrder))
    }
    @Test fun `leading zeros compare by value`() {
        assertEquals(listOf("002.mp3", "10.mp3"), listOf("10.mp3", "002.mp3").sortedWith(NaturalOrder))
    }
}

class PathParserTest {
    @Test fun `series and index in the folder name`() =
        assertEquals(BookIdentity("Brandon Sanderson", "Stormlight Archive", 3.0, "Oathbringer"),
            PathParser.parse(listOf("Brandon Sanderson", "Stormlight Archive 03 - Oathbringer")))
    @Test fun `series as its own folder with a Book prefix`() =
        assertEquals(BookIdentity("James S. A. Corey", "The Expanse", 2.0, "Caliban's War"),
            PathParser.parse(listOf("James S. A. Corey", "The Expanse", "Book 2 - Caliban's War")))
    @Test fun `novella index`() =
        assertEquals(2.5, PathParser.parse(listOf("A", "S", "2.5 - Novella")).seriesIndex)
    @Test fun `author and title only`() =
        assertEquals(BookIdentity("Andy Weir", null, null, "Project Hail Mary"), PathParser.parse(listOf("Andy Weir", "Project Hail Mary")))
    @Test fun `single folder with a dash`() =
        assertEquals(BookIdentity("Andy Weir", null, null, "The Martian"), PathParser.parse(listOf("Andy Weir - The Martian")))
    @Test fun `noise tags are dropped`() =
        assertEquals("Dune", PathParser.parse(listOf("Frank Herbert", "Dune [Unabridged] (64kbps)")).title)
    @Test fun `a bare title has no author`() = assertNull(PathParser.parse(listOf("Dune")).author)
    @Test fun `deep nesting uses the last three segments`() =
        assertEquals("The Expanse", PathParser.parse(listOf("SciFi", "James S. A. Corey", "The Expanse", "01 - Leviathan Wakes")).series)
}

private class Node(override val name: String, val kids: List<Node> = emptyList(), val dir: Boolean = kids.isNotEmpty(), val ms: Long? = 1000) : FileNode {
    override val uri get() = "mem://$name"
    override val isDirectory get() = dir
    override fun children() = kids
}

class LibraryScannerTest {
    private val scanner = LibraryScanner { (it as Node).ms }

    @Test fun `one book per folder holding audio, containers skipped`() {
        val root = Node("root", listOf(
            Node("Andy Weir", listOf(
                Node("Project Hail Mary", listOf(Node("02.mp3"), Node("01.mp3"), Node("cover.jpg"), Node("notes.txt"))),
            )),
            Node("loose.mp3"),
        ))
        val books = scanner.scan(root)
        assertEquals(1, books.size)
        assertEquals(listOf("01.mp3", "02.mp3"), books[0].files.map { it.name })
        assertEquals("mem://cover.jpg", books[0].coverUri)
        assertEquals("Andy Weir", books[0].identity.author)
        assertEquals(2000L, books[0].totalMs)
    }
    @Test fun `hidden partial folders are not books`() {
        val root = Node("root", listOf(Node(".partial", listOf(Node("x.m4b")))))
        assertTrue(scanner.scan(root).isEmpty())
    }
    @Test fun `unknown duration leaves the total unknown, not short`() {
        val root = Node("root", listOf(Node("B", listOf(Node("1.mp3"), Node("2.mp3", ms = null)))))
        assertNull(scanner.scan(root)[0].totalMs)
    }
    @Test fun `an empty folder named like audio is a folder`() {
        val root = Node("root", listOf(Node("B", listOf(Node("weird.mp3", dir = true)), dir = true)))
        assertTrue(scanner.scan(root).isEmpty())
    }
}

class ListeningTest {
    @Test fun `timeline round trips`() {
        val t = Timeline(listOf(1000, 2000, 3000))
        assertEquals(1500L, t.toGlobal(1, 500))
        assertEquals(1 to 500L, t.fromGlobal(1500))
        assertEquals(2 to 0L, t.fromGlobal(3000))
        assertEquals(2 to 3000L, t.fromGlobal(99_999))
    }
    @Test fun `finished in the tail, not before`() {
        val ten = 10 * 3600_000L
        assertFalse(Listening.isFinished(ten - 13 * 60_000, ten))
        assertTrue(Listening.isFinished(ten - 11 * 60_000, ten))
        assertTrue(Listening.isFinished(5 * 60_000 - 30_000, 5 * 60_000))   // short book: last minute
        assertFalse(Listening.isFinished(0, 0))
    }
    @Test fun `abandoned needs both idle time and under half`() {
        val day = 24 * 3600_000L
        assertTrue(Listening.isAbandoned(10, 100, 46 * day))
        assertFalse(Listening.isAbandoned(60, 100, 46 * day))
        assertFalse(Listening.isAbandoned(10, 100, 10 * day))
    }
    @Test fun `rewind grows with the pause`() {
        assertEquals(0L, Listening.rewindOnResume(5_000))
        assertEquals(5_000L, Listening.rewindOnResume(5 * 60_000))
        assertEquals(30_000L, Listening.rewindOnResume(3 * 24 * 3600_000L))
    }
    @Test fun `speed is clamped and rounded`() {
        assertEquals(3.0f, Listening.clampSpeed(9f))
        assertEquals(1.25f, Listening.clampSpeed(1.26f))
    }
}

class SleepTimerTest {
    @Test fun `fades over the last ten seconds`() {
        val t = SleepTimer.Countdown(100_000)
        assertEquals(1f, SleepTimer.volume(t, 80_000))
        assertEquals(0.5f, SleepTimer.volume(t, 95_000))
        assertTrue(SleepTimer.expired(t, 100_001))
    }
    @Test fun `shake extends from now when already expired`() {
        val t = SleepTimer.extend(SleepTimer.Countdown(100), nowMs = 1_000, byMs = 60_000)
        assertEquals(SleepTimer.Countdown(61_000), t)
    }
    @Test fun `off stays off when shaken`() = assertEquals(SleepTimer.Off, SleepTimer.extend(SleepTimer.Off, 0))
}

class UpNextTest {
    private fun b(id: Long, series: String?, idx: Double?, finished: Boolean = false, pos: Long = 0, author: String = "A") =
        ShelfBook(id, BookIdentity(author, series, idx, "t$id"), finished, pos, if (pos > 0) id else null)

    @Test fun `next in series comes first, skipping finished`() {
        val done = b(1, "S", 1.0, finished = true)
        val shelf = listOf(done, b(2, "S", 2.0, finished = true), b(3, "S", 3.0), b(4, "S", 4.0), b(5, null, null, pos = 10))
        assertEquals(3L, UpNext.after(done, shelf).first().id)
    }
    @Test fun `no series falls back to books in progress`() {
        val done = b(1, null, null, finished = true)
        val shelf = listOf(done, b(2, null, null), b(3, null, null, pos = 50))
        assertEquals(3L, UpNext.after(done, shelf).first().id)
    }
    @Test fun `same series name by another author is not the same series`() {
        val done = b(1, "Origins", 1.0, finished = true, author = "X")
        val shelf = listOf(done, b(2, "Origins", 2.0, author = "Y"), b(3, null, null, pos = 5, author = "Z"))
        assertEquals(3L, UpNext.after(done, shelf).first().id)
    }
}
