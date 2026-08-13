package io.github.darthr4v3m.ohtello.tello

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_760_000_000_000L

    private fun store(maxSessions: Int = 10) =
        SessionLogStore(folder.root.resolve("logs"), maxSessions) { now }

    private fun entry(text: String, kind: CommandLogEntry.Kind = CommandLogEntry.Kind.SENT) =
        CommandLogEntry(id = 1, timestampMillis = now, kind = kind, text = text)

    @Test
    fun `a session is written as it happens, not saved at the end`() {
        val store = store()
        store.startSession("Oh-Tello test")
        store.append(entry("→ command"))
        store.append(entry("← ok", CommandLogEntry.Kind.RECEIVED))

        // Nothing was closed or flushed explicitly: a log has to survive the app
        // being killed mid-flight, which is when it is most worth having.
        val written = store.sessions().single().readText()
        assertTrue(written.startsWith("Oh-Tello test"))
        assertTrue(written.contains("→ command"))
        assertTrue(written.contains("← ok"))
    }

    @Test
    fun `only the last N sessions are kept`() {
        val store = store(maxSessions = 3)
        repeat(6) { index ->
            now += 1_000
            store.startSession("session $index")
            store.append(entry("line $index"))
        }

        val kept = store.sessions().map { it.readText().lineSequence().first() }
        assertEquals(listOf("session 3", "session 4", "session 5"), kept)
    }

    @Test
    fun `sessions come back oldest first so the newest reads last`() {
        val store = store()
        repeat(3) { index ->
            now += 1_000
            store.startSession("session $index")
        }

        val text = store.shareableText()
        assertTrue(text.indexOf("session 0") < text.indexOf("session 2"))
    }

    @Test
    fun `sharing keeps the recent end when there is too much to send`() {
        val store = store()
        store.startSession("header")
        repeat(200) { store.append(entry("line $it")) }

        val trimmed = store.shareableText(maxChars = 400)

        assertTrue(trimmed.length <= 402)
        assertTrue("the newest lines are the ones worth keeping", trimmed.contains("line 199"))
        assertTrue(trimmed.startsWith("…"))
    }

    @Test
    fun `appending without a session does nothing rather than throwing`() {
        val store = store()

        store.append(entry("→ command"))

        assertTrue(store.sessions().isEmpty())
    }

    @Test
    fun `an unwritable directory does not bring down the caller`() {
        val store = SessionLogStore(folder.newFile("not-a-directory"), 10) { now }

        store.startSession("header")
        store.append(entry("→ command"))

        assertTrue(store.sessions().isEmpty())
    }

    @Test
    fun `concurrent appends neither throw nor corrupt a line`() {
        // The console is written from several threads at once: the reply waiter
        // under the send mutex, a priority land that bypasses it, and disconnect
        // on the main thread. Formatting a timestamp outside the lock let those
        // trample each other's SimpleDateFormat.
        val store = store()
        store.startSession("header")

        val threads = 8
        val perThread = 150
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (0 until threads).map { worker ->
            Thread {
                start.await()
                runCatching {
                    repeat(perThread) { store.append(entry("worker $worker line $it")) }
                }.onFailure { failures += it }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertTrue("append threw: ${failures.firstOrNull()}", failures.isEmpty())

        val lines = store.sessions().single().readLines().drop(1)
        assertEquals(threads * perThread, lines.size)
        val wellFormed = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} {2}\w+ +.+$""")
        val mangled = lines.filterNot { wellFormed.matches(it) }
        assertTrue("mangled lines: ${mangled.take(3)}", mangled.isEmpty())
    }
}
