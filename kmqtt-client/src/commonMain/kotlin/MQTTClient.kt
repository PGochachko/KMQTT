import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mqtt.MQTTCurrentPacket
import mqtt.MQTTException
import mqtt.MQTTVersion
import mqtt.Subscription
import mqtt.packets.ConnectFlags
import mqtt.packets.MQTTPacket
import mqtt.packets.Qos
import mqtt.packets.mqtt.*
import mqtt.packets.mqttv4.*
import mqtt.packets.mqttv5.*
import socket.IOException
import socket.SocketClosedException
import socket.SocketInterface
import socket.streams.EOFException
import socket.tls.TLSClientSettings

/**
 * MQTT 3.1.1 and 5 client
 *
 * @param mqttVersion sets the version of MQTT for this client (4 -> 3.1.1, 5 -> 5)
 * @param address the URL of the server
 * @param port the port of the server
 * @param tls TLS settings, null if no TLS
 * @param keepAlive the MQTT keep alive of the connection in seconds
 * @param webSocket whether to use a WebSocket for the underlying connection, null if no WebSocket, otherwise the HTTP path, usually /mqtt
 * @param userName the username field of the CONNECT packet
 * @param password the password field of the CONNECT packet
 * @param properties the properties to be included in the CONNECT message (used only in MQTT5)
 * @param willProperties the properties to be included in the will PUBLISH message (used only in MQTT5)
 * @param willTopic the topic of the will PUBLISH message
 * @param willPayload the content of the will PUBLISH message
 * @param willQos the QoS of the will PUBLISH message
 * @param enhancedAuthCallback the callback called when authenticationData is received, it should return the data necessary to continue authentication or null if completed (used only in MQTT5 if authenticationMethod has been set in the CONNECT properties)
 * @param publishReceived the callback called when a PUBLISH message is received by this client
 */
public class MQTTClient(
    private val mqttVersion: MQTTVersion,
    private val address: String,
    private val port: Int,
    private val tls: TLSClientSettings?,
    keepAlive: Int = 60,
    private val webSocket: String? = null,
    private val cleanStart: Boolean = true,
    private var clientId: String? = null,
    private val userName: String? = null,
    private val password: UByteArray? = null,
    private val properties: MQTT5Properties = MQTT5Properties(),
    private val willProperties: MQTT5Properties? = null,
    private val willTopic: String? = null,
    private val willPayload: UByteArray? = null,
    private val willRetain: Boolean = false,
    private val willQos: Qos = Qos.AT_MOST_ONCE,
    private val enhancedAuthCallback: (authenticationData: UByteArray?) -> UByteArray? = { null },
    private val onConnected: (connack: MQTTConnack) -> Unit = {},
    private val onDisconnected: (disconnect: MQTTDisconnect?) -> Unit = {},
    private val publishReceived: (publish: MQTTPublish) -> Unit
) {

    private val maximumPacketSize = properties.maximumPacketSize?.toInt() ?: (1024 * 1024)
    private var socket: SocketInterface? = null
    private val running: AtomicBoolean = atomic(false)

    private val keepAlive = atomic(keepAlive)

    private val currentReceivedPacket = MQTTCurrentPacket(maximumPacketSize.toUInt(), mqttVersion)
    private val lastActiveTimestamp = atomic(currentTimeMillis())

    // Session
    private var packetIdentifier: UInt = 1u
    // QoS 1 and QoS 2 messages which have been sent to the Server, but have not been completely acknowledged
    private val pendingAcknowledgeMessages = atomic(mutableMapOf<UInt, MQTTPublish>())
    private val pendingAcknowledgePubrel = atomic(mutableMapOf<UInt, MQTTPubrel>())
    // QoS 2 messages which have been received from the Server, but have not been completely acknowledged
    private val qos2ListReceived = mutableListOf<UInt>()

    // Connection
    private val topicAliasesClient = mutableMapOf<UInt, String>() // TODO reset all these on reconnection
    private val maximumQos = atomic(Qos.EXACTLY_ONCE)
    private val retainedSupported = atomic(true)
    private val maximumServerPacketSize = atomic(128 * 1024 * 1024)
    private var topicAliasMaximum = 0u
    private var wildcardSubscriptionAvailable = true
    private var subscriptionIdentifiersAvailable = true
    private var sharedSubscriptionAvailable = true
    private val receiveMax = atomic(65535u)
    private val connackReceived: AtomicBoolean = atomic(false)

    init {
        if (keepAlive > 65535) {
            throw IllegalArgumentException("Keep alive exceeding the maximum value")
        }

        if (willTopic == null && (willQos != Qos.AT_MOST_ONCE || willPayload != null || willRetain)) {
            throw IllegalArgumentException("Will topic null, but other will options have been set")
        }

        if (userName == null && password != null) {
            throw IllegalArgumentException("Cannot set password without username")
        }

        running.value = true

        connectSocket(250)
    }

    private fun connectSocket(readTimeout: Int) {
        if (socket == null) {
            connackReceived.value = false
            socket = if (tls == null)
                ClientSocket(address, port, maximumPacketSize, readTimeout, ::check)
            else
                TLSClientSocket(address, port, maximumPacketSize, readTimeout, tls, ::check)
            if (webSocket != null) {
                socket = WebSocket(socket!!, address, webSocket)
            }

            sendConnect()
        }
    }

    public fun isRunning(): Boolean = running.value

    public fun isConnackReceived(): Boolean = connackReceived.value

    private fun send(data: UByteArray) {
        socket?.send(data) ?: throw SocketClosedException("MQTT send failed")
        lastActiveTimestamp.value = currentTimeMillis()
    }

    private fun sendConnect() {
        val connect = if (mqttVersion == MQTTVersion.MQTT3_1_1) {
            MQTT4Connect(
                "MQTT",
                ConnectFlags(userName != null, password != null, willRetain, willQos, willTopic != null, cleanStart, false),
                keepAlive.value,
                clientId ?: generateRandomClientId(),
                willTopic,
                willPayload,
                userName,
                password
            )
        } else {
            MQTT5Connect(
                "MQTT",
                ConnectFlags(userName != null, password != null, willRetain, willQos, willTopic != null, cleanStart, false),
                keepAlive.value,
                clientId ?: generateRandomClientId(),
                properties,
                willProperties,
                willTopic,
                willPayload,
                userName,
                password
            )
        }
        send(connect.toByteArray())
    }

    private fun generatePacketId(): UInt {
        do { // TODO make atomic
            packetIdentifier++
            if (packetIdentifier > 65535u)
                packetIdentifier = 1u
        } while (isPacketIdInUse(packetIdentifier))

        return packetIdentifier
    }

    private fun isPacketIdInUse(packetId: UInt): Boolean {
        if (qos2ListReceived.contains(packetId))
            return true
        if (pendingAcknowledgeMessages.value[packetId] != null)
            return true
        if (pendingAcknowledgePubrel.value[packetId] != null)
            return true
        return false
    }

    /**
     * Send a PUBLISH message
     *
     * @param retain whether the message should be retained by the server
     * @param qos the QoS value
     * @param topic the topic of the message
     * @param payload the content of the message
     * @param properties the properties to be included in the message (used only in MQTT5)
     */
    public fun publish(retain: Boolean, qos: Qos, topic: String, payload: UByteArray?, properties: MQTT5Properties = MQTT5Properties()) {
        if (!connackReceived.value && properties.authenticationData != null) {
            throw Exception("Not sending until connection complete")
        }
        if (qos > maximumQos.value) {
            throw Exception("QoS exceeding maximum server supported QoS")
        }
        if (retain && !retainedSupported.value) {
            throw Exception("Retained not supported by the server")
        }

        val packetId = if (qos != Qos.AT_MOST_ONCE) {
            generatePacketId()
        } else {
            null
        }
        val publish = if (mqttVersion == MQTTVersion.MQTT3_1_1) {
            MQTT4Publish(retain, qos, false, topic, packetId, payload)
        } else {
            // TODO support client topic aliases
            MQTT5Publish(retain, qos, false, topic, packetId, properties, payload)
        }
        if (qos != Qos.AT_MOST_ONCE) {
            if (pendingAcknowledgeMessages.value.size + pendingAcknowledgePubrel.value.size >= receiveMax.value.toInt()) {
                throw Exception("Sending more PUBLISH with QoS > 0 than indicated by the server in receiveMax")
            }
            pendingAcknowledgeMessages.value[packetId!!] = publish
        }
        val data = publish.toByteArray()
        if (data.size > maximumServerPacketSize.value) {
            throw Exception("Packet size too big for the server to handle")
        }
        send(data)
    }

    /**
     * Subscribe to the specified topics
     *
     * @param subscriptions the list of topic filters and relative settings (many settings are used only in MQTT5)
     * @param properties the properties to be included in the message (used only in MQTT5)
     */
    public fun subscribe(subscriptions: List<Subscription>, properties: MQTT5Properties = MQTT5Properties()) {
        if (!connackReceived.value && properties.authenticationData != null) {
            throw Exception("Not sending until connection complete")
        }
        val subscribe = if (mqttVersion == MQTTVersion.MQTT3_1_1) {
            MQTT4Subscribe(generatePacketId(), subscriptions)
        } else {
            MQTT5Subscribe(generatePacketId(), subscriptions, properties)
        }
        send(subscribe.toByteArray())
    }

    /**
     * Unsubscribe from the specified topics
     *
     * @param topics the list of topic filters
     * @param properties the properties to be included in the message (used only in MQTT5)
     */
    public fun unsubscribe(topics: List<String>, properties: MQTT5Properties = MQTT5Properties()) {
        if (!connackReceived.value && properties.authenticationData != null) {
            throw Exception("Not sending until connection complete")
        }
        val unsubscribe = if (mqttVersion == MQTTVersion.MQTT3_1_1) {
            MQTT4Unsubscribe(generatePacketId(), topics)
        } else {
            MQTT5Unsubscribe(generatePacketId(), topics, properties)
        }
        send(unsubscribe.toByteArray())
    }

    /**
     * Disconnect the client
     *
     * @param reasonCode the specific reason code (only used in MQTT5)
     */
    public fun disconnect(reasonCode: ReasonCode) {
        val disconnect = if (mqttVersion == MQTTVersion.MQTT3_1_1) {
            MQTT4Disconnect()
        } else {
            MQTT5Disconnect(reasonCode)
        }
        send(disconnect.toByteArray())
        close()
    }

    private var lastException: Exception? = null

    private fun check() {
        if (!running.value) {
            return
        }
        if (socket == null) {
            close()
            // Needed because of JS callbacks, otherwise the exception gets swallowed and tests don't complete correctly
            throw lastException ?: SocketClosedException("")
        }
        socket?.sendRemaining()
        val data = socket?.read()

        if (data != null) {
            try {
                currentReceivedPacket.addData(data).forEach {
                    handlePacket(it)
                }
            } catch (e: MQTTException) {
                lastException = e
                e.printStackTrace()
                disconnect(e.reasonCode)
                close()
                onDisconnected(null)
                throw e
            } catch (e: EOFException) {
                lastException = e
                close()
                onDisconnected(null)
                throw e
            } catch (e: IOException) {
                lastException = e
                disconnect(ReasonCode.UNSPECIFIED_ERROR)
                close()
                onDisconnected(null)
                throw e
            } catch (e: Exception) {
                lastException = e
                disconnect(ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR)
                close()
                onDisconnected(null)
                throw e
            }
        } else {
            // If connack not received in a reasonable amount of time, then disconnect
            val currentTime = currentTimeMillis()
            val lastActive = lastActiveTimestamp.value
            val isConnackReceived = connackReceived.value

            if (!isConnackReceived && currentTime > lastActive + 30000) {
                close()
                lastException = Exception("CONNACK not received in 30 seconds")
                throw lastException!!
            }

            val actualKeepAlive = keepAlive.value
            if (actualKeepAlive != 0 && isConnackReceived) {
                if (currentTime > lastActive + (actualKeepAlive * 1000)) {
                    // Timeout
                    close()
                    lastException = MQTTException(ReasonCode.KEEP_ALIVE_TIMEOUT)
                    throw lastException!!
                } else if (currentTime > lastActive + (actualKeepAlive * 1000 * 0.9)) {
                    val pingreq = if (mqttVersion == MQTTVersion.MQTT3_1_1) {
                        MQTT4Pingreq()
                    } else {
                        MQTT5Pingreq()
                    }
                    send(pingreq.toByteArray())
                    // TODO if not receiving pingresp after a reasonable amount of time, close connection
                }
            }
        }
    }

    /**
     * Run a single iteration of the client (blocking)
     */
    public fun step() {
        if (running.value) {
            check()
        }
    }

    /**
     * Run the client (blocking)
     */
    public fun run() {
        while (running.value) {
            step()
        }
    }

    private fun handlePacket(packet: MQTTPacket) {
        when (packet) {
            is MQTTConnack -> handleConnack(packet)
            is MQTTPublish -> handlePublish(packet)
            is MQTTPuback -> handlePuback(packet)
            is MQTTPubrec -> handlePubrec(packet)
            is MQTTPubrel -> handlePubrel(packet)
            is MQTTPubcomp -> handlePubcomp(packet)
            is MQTTSuback -> handleSuback(packet)
            is MQTTUnsuback -> handleUnsuback(packet)
            is MQTTPingresp -> handlePingresp()
            is MQTTDisconnect -> handleDisconnect(packet)
            is MQTT5Auth -> handleAuth(packet)
            else -> throw MQTTException(ReasonCode.PROTOCOL_ERROR)
        }
    }

    private fun handleConnack(packet: MQTTConnack) {
        if (packet is MQTT5Connack) {
            if (packet.connectReasonCode != ReasonCode.SUCCESS) {
                if ((packet.connectReasonCode == ReasonCode.USE_ANOTHER_SERVER || packet.connectReasonCode == ReasonCode.SERVER_MOVED) && packet.properties.serverReference != null) {
                    // TODO if reason code 0x9C try to connect to the given server (4.11 format)
                } else {
                    throw MQTTException(packet.connectReasonCode)
                }
            }

            val receiveMax = packet.properties.receiveMaximum ?: 65535u
            this.receiveMax.value = receiveMax
            val maximumQos = packet.properties.maximumQos?.let { Qos.valueOf(it.toInt()) } ?: Qos.EXACTLY_ONCE
            this.maximumQos.value = maximumQos
            val retainAvailable = packet.properties.retainAvailable != 0u
            this.retainedSupported.value = retainAvailable
            val maximumServerPacketSize = packet.properties.maximumPacketSize?.toInt() ?: maximumServerPacketSize.value
            this.maximumServerPacketSize.value = maximumServerPacketSize
            clientId = packet.properties.assignedClientIdentifier ?: clientId
            topicAliasMaximum = packet.properties.topicAliasMaximum ?: topicAliasMaximum
            wildcardSubscriptionAvailable = packet.properties.wildcardSubscriptionAvailable != 0u
            subscriptionIdentifiersAvailable = packet.properties.subscriptionIdentifierAvailable != 0u
            sharedSubscriptionAvailable = packet.properties.sharedSubscriptionAvailable != 0u

            val keepAlive = packet.properties.serverKeepAlive?.toInt() ?: keepAlive.value
            this.keepAlive.value = keepAlive

            enhancedAuthCallback(packet.properties.authenticationData)
        } else if (packet is MQTT4Connack) {
            if (packet.connectReturnCode != ConnectReturnCode.CONNECTION_ACCEPTED) {
                throw IOException("Connection failed with code: ${packet.connectReturnCode}")
            }
        }

        connackReceived.value = true
        if (cleanStart && packet.connectAcknowledgeFlags.sessionPresentFlag) {
            throw MQTTException(ReasonCode.PROTOCOL_ERROR)
        } else if (!cleanStart && !packet.connectAcknowledgeFlags.sessionPresentFlag) {
            // Session expired on the server, so clean the local session
            packetIdentifier = 1u
            pendingAcknowledgeMessages.value.clear()
            pendingAcknowledgePubrel.value.clear()
            qos2ListReceived.clear()
        } else if (!cleanStart && packet.connectAcknowledgeFlags.sessionPresentFlag) {
            // Resend pending publish and pubrel messages (with dup=1)
            pendingAcknowledgeMessages.value.forEach {
                send(it.value.setDuplicate().toByteArray())
            }
            pendingAcknowledgePubrel.value.forEach {
                send(it.value.toByteArray())
            }
        }
        onConnected(packet)
    }

    private fun handlePublish(packet: MQTTPublish) {
        var correctPacket = packet

        if (correctPacket.qos > maximumQos.value) {
            throw MQTTException(ReasonCode.QOS_NOT_SUPPORTED)
        }

        if (correctPacket.qos > Qos.AT_LEAST_ONCE) {
            if (qos2ListReceived.size > (properties.receiveMaximum?.toInt() ?: 65535)) {
                // Received too many messages
                throw MQTTException(ReasonCode.RECEIVE_MAXIMUM_EXCEEDED)
            }
        }

        if (correctPacket is MQTT5Publish) {
            if (correctPacket.properties.topicAlias != null) {
                if (correctPacket.properties.topicAlias == 0u || correctPacket.properties.topicAlias!! > (properties.topicAliasMaximum
                        ?: 65535u)
                ) {
                    throw MQTTException(ReasonCode.TOPIC_ALIAS_INVALID)
                }
                if (correctPacket.topicName.isNotEmpty()) {
                    // Map alias
                    topicAliasesClient[correctPacket.properties.topicAlias!!] = correctPacket.topicName
                } else if (correctPacket.topicName.isEmpty()) {
                    // Use alias
                    val topicName = topicAliasesClient[correctPacket.properties.topicAlias!!] ?: throw MQTTException(ReasonCode.PROTOCOL_ERROR)
                    correctPacket = correctPacket.setTopicFromAlias(topicName)
                }
            }
        }

        when (correctPacket.qos) {
            Qos.AT_MOST_ONCE -> {
                publishReceived(correctPacket)
            }
            Qos.AT_LEAST_ONCE -> {
                val puback = if (correctPacket is MQTT4Publish) {
                    MQTT4Puback(correctPacket.packetId!!)
                } else {
                    MQTT5Puback(correctPacket.packetId!!)
                }
                send(puback.toByteArray())
                publishReceived(correctPacket)
            }
            Qos.EXACTLY_ONCE -> {
                val pubrec = if (correctPacket is MQTT4Publish) {
                    MQTT4Pubrec(correctPacket.packetId!!)
                } else {
                    MQTT5Pubrec(correctPacket.packetId!!)
                }
                send(pubrec.toByteArray())
                if (!qos2ListReceived.contains(correctPacket.packetId!!)) {
                    qos2ListReceived.add(correctPacket.packetId!!)
                    publishReceived(correctPacket)
                }
            }
        }
    }

    private fun handlePuback(packet: MQTTPuback) {
        if (packet is MQTT5Puback && properties.requestProblemInformation == 0u && (packet.properties.reasonString != null || packet.properties.userProperty.isNotEmpty())) {
            throw MQTTException(ReasonCode.PROTOCOL_ERROR)
        }
        pendingAcknowledgeMessages.value.remove(packet.packetId)
    }

    private fun handlePubrec(packet: MQTTPubrec) {
        if (packet is MQTT5Pubrec && properties.requestProblemInformation == 0u && (packet.properties.reasonString != null || packet.properties.userProperty.isNotEmpty())) {
            throw MQTTException(ReasonCode.PROTOCOL_ERROR)
        }
        pendingAcknowledgeMessages.value.remove(packet.packetId)
        val pubrel = if (packet is MQTT4Pubrec) {
            MQTT4Pubrel(packet.packetId)
        } else {
            MQTT5Pubrel(packet.packetId)
        }
        pendingAcknowledgePubrel.value[packet.packetId] = pubrel
        send(pubrel.toByteArray())
    }

    private fun handlePubrel(packet: MQTTPubrel) {
        if (packet is MQTT5Pubrel && properties.requestProblemInformation == 0u && (packet.properties.reasonString != null || packet.properties.userProperty.isNotEmpty())) {
            throw MQTTException(ReasonCode.PROTOCOL_ERROR)
        }
        val pubcomp = if (packet is MQTT4Pubrel) {
            MQTT4Pubcomp(packet.packetId)
        } else {
            MQTT5Pubcomp(packet.packetId)
        }
        send(pubcomp.toByteArray())
        if (!qos2ListReceived.remove(packet.packetId)) {
            throw MQTTException(ReasonCode.PACKET_IDENTIFIER_NOT_FOUND)
        }
    }

    private fun handlePubcomp(packet: MQTTPubcomp) {
        if (packet is MQTT5Pubcomp && properties.requestProblemInformation == 0u && (packet.properties.reasonString != null || packet.properties.userProperty.isNotEmpty())) {
            throw MQTTException(ReasonCode.PROTOCOL_ERROR)
        }
        if (pendingAcknowledgePubrel.value.remove(packet.packetId) == null) {
            throw MQTTException(ReasonCode.PACKET_IDENTIFIER_NOT_FOUND)
        }
    }

    private fun handleSuback(packet: MQTTSuback) {
        if (packet is MQTT4Suback) {
            for (reasonCode in packet.reasonCodes) {
                if (reasonCode == SubackReturnCode.FAILURE) {
                    throw MQTTException(ReasonCode.UNSPECIFIED_ERROR)
                }
            }
        } else if (packet is MQTT5Suback) {
            if (properties.requestProblemInformation == 0u && (packet.properties.reasonString != null || packet.properties.userProperty.isNotEmpty())) {
                throw MQTTException(ReasonCode.PROTOCOL_ERROR)
            }
            for (reasonCode in packet.reasonCodes) {
                if (reasonCode != ReasonCode.SUCCESS && reasonCode != ReasonCode.GRANTED_QOS1 && reasonCode != ReasonCode.GRANTED_QOS2) {
                    throw MQTTException(reasonCode)
                }
            }
        }
    }

    private fun handleUnsuback(packet: MQTTUnsuback) {
        if (packet is MQTT5Unsuback && properties.requestProblemInformation == 0u && (packet.properties.reasonString != null || packet.properties.userProperty.isNotEmpty())) {
            throw MQTTException(ReasonCode.PROTOCOL_ERROR)
        }
    }

    private fun handlePingresp() {
        lastActiveTimestamp.value = currentTimeMillis()
    }

    private fun handleAuth(packet: MQTT5Auth) {
        if (packet.authenticateReasonCode == ReasonCode.CONTINUE_AUTHENTICATION) {
            val data = enhancedAuthCallback(packet.properties.authenticationData)
            val auth = MQTT5Auth(ReasonCode.CONTINUE_AUTHENTICATION, MQTT5Properties(authenticationMethod = packet.properties.authenticationMethod, authenticationData = data))
            send(auth.toByteArray())
        }
    }

    /**
     * Start the re-authentication process, to be used only when authenticationMethod has been set in the CONNECT packet
     *
     * @param data the authenticationData if necessary
     */
    public fun reAuthenticate(data: UByteArray?) {
        val auth = MQTT5Auth(
            ReasonCode.RE_AUTHENTICATE,
            MQTT5Properties(authenticationMethod = properties.authenticationMethod, authenticationData = data)
        )
        send(auth.toByteArray())
    }

    private fun handleDisconnect(disconnect: MQTTDisconnect) {
        if (disconnect is MQTT5Disconnect) {
            close()
            if ((disconnect.reasonCode == ReasonCode.USE_ANOTHER_SERVER || disconnect.reasonCode == ReasonCode.SERVER_MOVED) && disconnect.properties.serverReference != null) {
                // TODO connect to the new server
            } else {
                throw MQTTException(disconnect.reasonCode)
            }
        }
        onDisconnected(disconnect)
    }

    private fun close() {
        running.value = false
        socket?.close()
        connackReceived.value = false
        socket = null
    }
}