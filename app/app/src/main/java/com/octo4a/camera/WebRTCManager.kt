package com.octo4a.camera

import android.content.Context
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebRTCManager(private val context: Context) {

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    
    private val peerConnections = mutableListOf<PeerConnection>()

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

    suspend fun processOffer(offerSdp: String): String = suspendCoroutine { cont ->
        if (factory == null) {
            cont.resume("")
            return@suspendCoroutine
        }
    
        val peerConnection = factory?.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()),
            object : PeerConnection.Observer {
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(p0: IceCandidate?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )
        
        peerConnection?.addTrack(localVideoTrack)
        peerConnections.add(peerConnection!!)

        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                peerConnection.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                         peerConnection.setLocalDescription(object : SdpObserver {
                             override fun onCreateSuccess(p0: SessionDescription?) {}
                             override fun onSetSuccess() {
                                 cont.resume(answer?.description ?: "")
                             }
                             override fun onCreateFailure(p0: String?) {
                                 cont.resume("")
                             }
                             override fun onSetFailure(p0: String?) {
                                 cont.resume("")
                             }
                         }, answer)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) { cont.resume("") }
                    override fun onSetFailure(p0: String?) { cont.resume("") }
                }, MediaConstraints())
            }
            override fun onCreateFailure(p0: String?) { cont.resume("") }
            override fun onSetFailure(p0: String?) { cont.resume("") }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }
}
