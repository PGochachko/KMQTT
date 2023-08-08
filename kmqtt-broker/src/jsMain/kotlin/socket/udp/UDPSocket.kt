package socket.udp

import node.events.Event
import socket.SocketState
import toBuffer
import toUByteArray

internal actual class UDPSocket(
    private val socket: Socket,
    private val selectCallback: (attachment: Any?, state: SocketState) -> Boolean
) {

    private val queue = ArrayDeque<UDPReadData>()
    private var attachment: Any? = null

    init {
        socket.on(Event.MESSAGE) { msg, rinfo ->
            queue.add(UDPReadData(msg.toUByteArray(), rinfo.address, rinfo.port.toInt()))
            selectCallback(attachment, SocketState.READ)
        }
    }

    actual fun send(data: UByteArray, address: String, port: Int) {
        socket.send(data.toBuffer(), port, address) { _, _ -> }
        selectCallback(attachment, SocketState.WRITE)
    }

    actual fun read(): UDPReadData? {
        return queue.removeFirstOrNull()
    }

    fun setAttachment(attachment: Any?) {
        this.attachment = attachment
    }

}
