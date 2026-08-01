package io.github.sawaichi9527.eyeshell.ssh

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.KeyPair
import java.security.PublicKey
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.apache.sshd.agent.SshAgent
import org.apache.sshd.agent.SshAgentFactory
import org.apache.sshd.agent.SshAgentKeyConstraint
import org.apache.sshd.agent.SshAgentServer
import org.apache.sshd.agent.common.AbstractAgentProxy
import org.apache.sshd.common.FactoryManager
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.buffer.ByteArrayBuffer

internal class OpenSshAgentFactory(
    private val endpoint: SshAgentEndpoint,
) : SshAgentFactory {
    override fun getChannelForwardingFactories(manager: FactoryManager): List<ChannelFactory> = emptyList()

    override fun createClient(session: Session?, manager: FactoryManager): SshAgent = when (endpoint) {
        is SshAgentEndpoint.UnixSocket -> UnixOpenSshAgent(endpoint.path)
        SshAgentEndpoint.WindowsOpenSsh -> WindowsOpenSshAgent()
    }

    override fun createServer(service: ConnectionService): SshAgentServer =
        throw IOException("SSH agent forwarding is not supported")
}

private abstract class OpenSshAgent : AbstractAgentProxy(null) {
    final override fun addIdentity(key: KeyPair, comment: String, vararg constraints: SshAgentKeyConstraint) =
        throw IOException("SSH agent identity changes are not supported")

    final override fun removeIdentity(key: PublicKey) =
        throw IOException("SSH agent identity changes are not supported")

    final override fun removeAllIdentities() =
        throw IOException("SSH agent identity changes are not supported")

    protected fun response(payload: ByteArray): Buffer {
        require(payload.isNotEmpty()) { "SSH agent returned an empty response" }
        require(payload.size <= MAX_FRAME_SIZE) { "SSH agent response is too large" }
        return ByteArrayBuffer(payload)
    }

    protected fun requestBytes(buffer: Buffer): ByteBuffer {
        val available = buffer.available()
        if (available < Int.SIZE_BYTES + 1) throw IOException("SSH agent request is incomplete")
        val frameSize = buffer.rawUInt(buffer.rpos())
        if (frameSize !in 1..MAX_FRAME_SIZE.toLong() || frameSize != available.toLong() - Int.SIZE_BYTES) {
            throw IOException("Invalid SSH agent request length: $frameSize")
        }
        return ByteBuffer.wrap(buffer.array(), buffer.rpos(), available)
    }

    protected fun checkedFrameSize(header: ByteBuffer): Int {
        header.flip()
        val size = header.int
        if (size !in 1..MAX_FRAME_SIZE) throw IOException("Invalid SSH agent response length: $size")
        return size
    }

    companion object {
        val IO_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val MAX_FRAME_SIZE = 256 * 1024
    }
}

private class UnixOpenSshAgent(path: Path) : OpenSshAgent() {
    private val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    private val selector = Selector.open()

    init {
        try {
            channel.configureBlocking(false)
            channel.register(selector, SelectionKey.OP_CONNECT)
            if (!channel.connect(UnixDomainSocketAddress.of(path))) {
                waitUntilReady(SelectionKey.OP_CONNECT, deadline())
                if (!channel.finishConnect()) throw IOException("Could not connect to SSH agent")
            }
        } catch (failure: Exception) {
            channel.close()
            selector.close()
            throw failure
        }
    }

    override fun isOpen(): Boolean = channel.isOpen

    @Synchronized
    override fun request(buffer: Buffer): Buffer {
        check(isOpen) { "SSH agent connection is closed" }
        val deadline = deadline()
        writeFully(requestBytes(buffer), deadline)
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
        readFully(header, deadline)
        val payload = ByteBuffer.allocate(checkedFrameSize(header))
        readFully(payload, deadline)
        return response(payload.array())
    }

    override fun close() {
        channel.close()
        selector.close()
        super.close()
    }

    private fun writeFully(buffer: ByteBuffer, deadline: Long) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) waitUntilReady(SelectionKey.OP_WRITE, deadline)
        }
    }

    private fun readFully(buffer: ByteBuffer, deadline: Long) {
        while (buffer.hasRemaining()) {
            when (channel.read(buffer)) {
                -1 -> throw IOException("SSH agent closed the connection")
                0 -> waitUntilReady(SelectionKey.OP_READ, deadline)
            }
        }
    }

    private fun waitUntilReady(operation: Int, deadline: Long) {
        channel.keyFor(selector).interestOps(operation)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("Timed out waiting for SSH agent")
            val timeoutMillis = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
            if (selector.select(timeoutMillis) > 0) {
                selector.selectedKeys().clear()
                return
            }
        }
    }

    private fun deadline(): Long = System.nanoTime() + IO_TIMEOUT.toNanos()
}

private class WindowsOpenSshAgent : OpenSshAgent() {
    private val channel = AsynchronousFileChannel.open(Path.of(PIPE_NAME), READ, WRITE)

    override fun isOpen(): Boolean = channel.isOpen

    @Synchronized
    override fun request(buffer: Buffer): Buffer {
        check(isOpen) { "SSH agent connection is closed" }
        val deadline = System.nanoTime() + IO_TIMEOUT.toNanos()
        writeFully(requestBytes(buffer), deadline)
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
        readFully(header, deadline)
        val payload = ByteBuffer.allocate(checkedFrameSize(header))
        readFully(payload, deadline)
        return response(payload.array())
    }

    override fun close() {
        channel.close()
        super.close()
    }

    private fun writeFully(buffer: ByteBuffer, deadline: Long) {
        while (buffer.hasRemaining()) await(channel.write(buffer, 0), deadline)
    }

    private fun readFully(buffer: ByteBuffer, deadline: Long) {
        while (buffer.hasRemaining()) {
            if (await(channel.read(buffer, 0), deadline) < 0) throw IOException("SSH agent closed the connection")
        }
    }

    private fun await(operation: Future<Int>, deadline: Long): Int {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) {
            operation.cancel(true)
            throw SocketTimeoutException("Timed out waiting for SSH agent")
        }
        return try {
            operation.get(remaining, TimeUnit.NANOSECONDS)
        } catch (failure: TimeoutException) {
            operation.cancel(true)
            throw SocketTimeoutException("Timed out waiting for SSH agent")
        } catch (failure: InterruptedException) {
            operation.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for SSH agent", failure)
        } catch (failure: ExecutionException) {
            throw IOException("SSH agent I/O failed", failure.cause)
        }
    }

    companion object {
        private const val PIPE_NAME = "\\\\.\\pipe\\openssh-ssh-agent"
    }
}
