package com.octo4a.camera

import android.content.Context
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebRTCManager(private val context: Context) {

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private val peerConnectionsById = ConcurrentHashMap<String, PeerConnection>()

    var onStreamActiveStatusChanged: ((Boolean) -> Unit)? = null
    private val activePeerIds = ConcurrentHashMap.newKeySet<String>()

    private fun updatePeerStatus(id: String, connected: Boolean) {
        val wasEmpty = activePeerIds.isEmpty()
        if (connected) {
            activePeerIds.add(id)
        } else {
            activePeerIds.remove(id)
            peerConnectionsById.remove(id)?.close()
        }
        val isEmpty = activePeerIds.isEmpty()
        if (wasEmpty && !isEmpty) {
            onStreamActiveStatusChanged?.invoke(true)
        } else if (!wasEmpty && isEmpty) {
            onStreamActiveStatusChanged?.invoke(false)
        }
    }

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()
        val options = PeerConnectionFactory.Options()

        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase?.eglBaseContext,  /* enableIntelVp8Encoder */ true,  /* enableH264HighProfile */ true
        )
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()

        videoSource = factory?.createVideoSource(false)
        localVideoTrack = factory?.createVideoTrack("100", videoSource)
    }

    fun pushFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        if (factory == null) return
        try {
            val buffer = NV21Buffer(nv21, width, height, null)
            val frame = VideoFrame(buffer, rotation, System.nanoTime())
            videoSource?.capturerObserver?.onFrameCaptured(frame)
            frame.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Creates an offer (server is the offerer, matching camera-streamer API).
    // Returns a pair of (id, offerSdp). Waits for ICE gathering to complete.
    suspend fun createOffer(): Pair<String, String> = suspendCoroutine { cont ->
        if (factory == null) {
            cont.resume(Pair("", ""))
            return@suspendCoroutine
        }

        val id = UUID.randomUUID().toString()
        val resumed = java.util.concurrent.atomic.AtomicBoolean(false)

        val peerConnection = factory?.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()),
            object : PeerConnection.Observer {
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (state == PeerConnection.IceConnectionState.CONNECTED) {
                        updatePeerStatus(id, true)
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                        state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.CLOSED) {
                        updatePeerStatus(id, false)
                    }
                }
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE && resumed.compareAndSet(false, true)) {
                        val sdp = peerConnectionsById[id]?.localDescription?.description ?: ""
                        cont.resume(if (sdp.isNotEmpty()) Pair(id, sdp) else Pair("", ""))
                    }
                }
                override fun onIceCandidate(p0: IceCandidate?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )

        if (peerConnection == null) {
            cont.resume(Pair("", ""))
            return@suspendCoroutine
        }

        peerConnection.addTrack(localVideoTrack)
        peerConnectionsById[id] = peerConnection

        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription?) {
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        // ICE gathering starts; wait for onIceGatheringChange(COMPLETE)
                    }
                    override fun onCreateFailure(p0: String?) {
                        if (resumed.compareAndSet(false, true)) cont.resume(Pair("", ""))
                    }
                    override fun onSetFailure(p0: String?) {
                        if (resumed.compareAndSet(false, true)) cont.resume(Pair("", ""))
                    }
                }, offer)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                if (resumed.compareAndSet(false, true)) cont.resume(Pair("", ""))
            }
            override fun onSetFailure(p0: String?) {
                if (resumed.compareAndSet(false, true)) cont.resume(Pair("", ""))
            }
        }, MediaConstraints())
    }

    // Sets the client's answer SDP as remote description for the given peer connection id.
    suspend fun processAnswer(id: String, answerSdp: String): Boolean = suspendCoroutine { cont ->
        val peerConnection = peerConnectionsById[id]
        if (peerConnection == null) {
            cont.resume(false)
            return@suspendCoroutine
        }

        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { cont.resume(true) }
            override fun onCreateFailure(p0: String?) { cont.resume(false) }
            override fun onSetFailure(p0: String?) { cont.resume(false) }
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    // Adds a remote ICE candidate from the client to the given peer connection.
    fun addIceCandidate(id: String, candidate: IceCandidate) {
        peerConnectionsById[id]?.addIceCandidate(candidate)
    }
}
