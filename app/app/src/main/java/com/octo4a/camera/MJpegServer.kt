package com.octo4a.camera

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

interface MJpegFrameProvider {
    data class FrameInfo(val image: ByteArray? = null, val id: Int)

    suspend fun takeSnapshot(): ByteArray
    fun getNewFrame(prevFrame: FrameInfo?): FrameInfo
    fun registerListener(): Boolean
    fun unregisterListener()
    suspend fun createWebRTCOffer(): Pair<String, String>
    suspend fun processWebRTCAnswer(id: String, answerSdp: String): Boolean
    fun addWebRTCIceCandidate(id: String, sdpMid: String?, sdpMLineIndex: Int, sdpCandidate: String)
}

// Simple http server hosting mjpeg stream along with
class MJpegServer(port: Int, private val frameProvider: MJpegFrameProvider): NanoHTTPD(port) {

    override fun serve(session: IHTTPSession?): Response {
        when (session?.uri) {
            "/snapshot" -> {
                    var res: Response? = null
                    kotlin.runCatching {
                        runBlocking {
                            val data = frameProvider?.takeSnapshot()
                            val inputStream = ByteArrayInputStream(data)

                            res = newFixedLengthResponse(
                                Response.Status.OK,
                                "image/jpeg",
                                inputStream,
                                data.size.toLong()
                            )
                        }
                    }.onFailure {
                    }

                return res ?: newFixedLengthResponse(
                    "<h1>Failed to fetch image</h1>"
                )
            }
            "/mjpeg" -> {
                return MjpegResponse(frameProvider)
            }
            "/webrtc" -> {
                // Handling CORS Preflight
                if (session.method == Method.OPTIONS) {
                    val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    res.addHeader("Access-Control-Allow-Headers", "*")
                    res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    return res
                }
                
                if (session.method == Method.POST) {
                    val map = HashMap<String, String>()
                    try {
                        session.parseBody(map)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    val postData = map["postData"] ?: ""

                    if (postData.trim().startsWith("{")) {
                        try {
                            val jsonObj = org.json.JSONObject(postData)
                            when (jsonObj.optString("type", "")) {
                                "request" -> {
                                    var id = ""
                                    var offerSdp = ""
                                    kotlin.runCatching {
                                        runBlocking {
                                            val result = frameProvider.createWebRTCOffer()
                                            id = result.first
                                            offerSdp = result.second
                                        }
                                    }.onFailure { e ->
                                        e.printStackTrace()
                                    }
                                    if (id.isEmpty() || offerSdp.isEmpty()) {
                                        val res = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to create WebRTC offer")
                                        res.addHeader("Access-Control-Allow-Origin", "*")
                                        return res
                                    }
                                    val responseObj = org.json.JSONObject()
                                    responseObj.put("type", "offer")
                                    responseObj.put("sdp", offerSdp)
                                    responseObj.put("id", id)
                                    responseObj.put("iceServers", org.json.JSONArray())
                                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", responseObj.toString())
                                    res.addHeader("Access-Control-Allow-Origin", "*")
                                    return res
                                }
                                "answer" -> {
                                    val id = jsonObj.optString("id", "")
                                    val answerSdp = jsonObj.optString("sdp", "")
                                    var success = false
                                    kotlin.runCatching {
                                        runBlocking {
                                            success = frameProvider.processWebRTCAnswer(id, answerSdp)
                                        }
                                    }.onFailure { e ->
                                        e.printStackTrace()
                                    }
                                    if (!success) {
                                        val res = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to process WebRTC answer")
                                        res.addHeader("Access-Control-Allow-Origin", "*")
                                        return res
                                    }
                                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
                                    res.addHeader("Access-Control-Allow-Origin", "*")
                                    return res
                                }
                                "remote_candidate" -> {
                                    val id = jsonObj.optString("id", "")
                                    val candidates = jsonObj.optJSONArray("candidates")
                                    candidates?.let {
                                        for (i in 0 until it.length()) {
                                            val candidateObj = it.optJSONObject(i)
                                            if (candidateObj != null) {
                                                frameProvider.addWebRTCIceCandidate(
                                                    id,
                                                    candidateObj.optString("sdpMid"),
                                                    candidateObj.optInt("sdpMLineIndex"),
                                                    candidateObj.optString("candidate")
                                                )
                                            }
                                        }
                                    }
                                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
                                    res.addHeader("Access-Control-Allow-Origin", "*")
                                    return res
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad Request")
            }
            else -> return newFixedLengthResponse(
                "<html><body>"
                        + "<h1>GET /snapshot</h1><p>GET a current JPEG image.</p>"
                        + "<h1>GET /mjpeg</h1><p>GET MJPEG frames.</p>"
                        + "<h1>POST /webrtc</h1><p>POST WebRTC SDP Offer.</p>"
                        + "</body></html>"
            )
        }
    }

    fun startServer() {
        start()
    }

    fun stopServer() {
        stop()
    }
}