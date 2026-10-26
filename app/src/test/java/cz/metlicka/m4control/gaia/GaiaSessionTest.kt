package cz.metlicka.m4control.gaia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

class GaiaSessionTest {
    private lateinit var scope: CoroutineScope
    private lateinit var device: PipedOutputStream
    private lateinit var sent: ByteArrayOutputStream
    private lateinit var session: GaiaSession
    private val notifications = CopyOnWriteArrayList<Frame>()
    private var closedWith: Throwable? = null

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val input = PipedInputStream(4096)
        device = PipedOutputStream(input)
        sent = ByteArrayOutputStream()
        session = GaiaSession(input, sent, scope, { notifications += it }, onClosed = { closedWith = it })
        session.start()
    }

    @After fun tearDown() {
        session.close()
        scope.cancel()
    }

    private fun deviceSends(hex: String) { device.write(hex.hexToBytes()); device.flush() }

    @Test fun responseWithInterleavedNotification() = runBlocking {
        val r = async { session.request(Cmd.MASTER_GET) }
        delay(50)
        assertEquals("FF 03 00 00 04 95 1A 05", sent.toByteArray().toHex())
        deviceSends("FF 03 00 01 04 95 1A 83 32 FF 03 00 01 04 95 1B 05 01")
        val reply = r.await()
        assertTrue(reply is Reply.Ok)
        assertArrayEquals(byteArrayOf(1), (reply as Reply.Ok).payload)
        assertEquals(listOf(0x1A83), notifications.map { it.command })
    }

    @Test fun vendorMismatchDoesNotSatisfyRequest() = runBlocking {
        val r = async { session.request(Cmd.MASTER_GET, timeoutMs = 300) }
        delay(50)
        deviceSends("FF 03 00 01 00 1D 1B 05 01")
        assertEquals(Reply.Timeout, r.await())
    }

    @Test fun errorResponseCarriesCode() = runBlocking {
        val r = async { session.request(Cmd.SIDETONE_SET, intArrayOf(9)) }
        delay(50)
        deviceSends("FF 03 00 01 04 95 09 85 03")
        val reply = r.await()
        assertTrue(reply is Reply.Rejected)
        assertEquals(3, (reply as Reply.Rejected).code)
    }

    @Test fun reusingTimedOutSlotEndsSession() = runBlocking {
        assertEquals(Reply.Timeout, session.request(Cmd.TRANSPARENCY_SET, intArrayOf(50), timeoutMs = 100))
        try {
            session.request(Cmd.TRANSPARENCY_SET, intArrayOf(50))
            fail("expected session end")
        } catch (e: SessionClosedException) {
            assertTrue(session.isClosed)
        }
    }

    @Test fun lateResponseFreesSlot() = runBlocking {
        assertEquals(Reply.Timeout, session.request(Cmd.TRANSPARENCY_SET, intArrayOf(50), timeoutMs = 100))
        deviceSends("FF 03 00 00 04 95 1B 02") // the late ack
        delay(100)
        val r = async { session.request(Cmd.TRANSPARENCY_SET, intArrayOf(40)) }
        delay(50)
        deviceSends("FF 03 00 00 04 95 1B 02")
        assertTrue(r.await() is Reply.Ok)
        assertTrue(!session.isClosed)
    }

    @Test fun peerCloseClosesSession() = runBlocking {
        device.close()
        delay(200)
        assertTrue(session.isClosed)
        assertTrue(closedWith != null)
    }
}
