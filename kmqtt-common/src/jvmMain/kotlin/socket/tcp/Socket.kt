package socket.tcp

import socket.IOException
import socket.SocketClosedException
import socket.SocketInterface
import toUByteArray
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

public actual open class Socket(
    protected val channel: SocketChannel,
    private val key: SelectionKey?,
    private var sendBuffer: ByteBuffer,
    private val receiveBuffer: ByteBuffer
) : SocketInterface {

    actual override fun send(data: UByteArray) {
        val byteArray = data.toByteArray()
        try {
            sendBuffer.put(byteArray)
        } catch (e: BufferOverflowException) {
            sendBuffer = ByteBuffer.allocate(sendBuffer.capacity() + data.size)
            sendBuffer.put(byteArray)
        }
        sendFromBuffer()
    }

    protected fun sendFromBuffer() {
        sendBuffer.flip()
        val size = sendBuffer.remaining()
        try {
            val count = channel.write(sendBuffer)
            if (count < size) {
                key?.interestOps(SelectionKey.OP_WRITE)
            } else {
                key?.interestOps(SelectionKey.OP_READ)
            }
            sendBuffer.compact()
        } catch (e: java.io.IOException) {
            close()
            throw IOException(e.message)
        }
    }

    protected fun readToBuffer(): Int {
        try {
            val length = channel.read(receiveBuffer)
            when {
                length >= 0 -> return length
                else -> {
                    close()
                    throw SocketClosedException("Read to buffer error End Of Stream ($length)")
                }
            }
        } catch (e: java.io.IOException) {
            close()
            throw IOException(e.message)
        }
    }

    actual override fun read(): UByteArray? {
        return if (readToBuffer() > 0) {
            receiveBuffer.flip()
            receiveBuffer.toUByteArray()
        }
        else null
    }

    actual override fun close() {
        key?.cancel()
        if (channel.isOpen) {
            channel.close()
        }
    }

    actual override fun sendRemaining() {
        sendFromBuffer()
    }

}