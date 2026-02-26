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
    suspend fun processWebRTCOffer(offerSdp: String): String
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
                    
                    var offerSdp = ""
                    if (postData.trim().startsWith("{")) {
                        try {
                            val jsonObj = org.json.JSONObject(postData)
                            val requestType = jsonObj.optString("type", "")
                            
                            // 兼容可能有直接带sdp的情况
                            if (jsonObj.has("sdp")) {
                                offerSdp = jsonObj.optString("sdp", "")
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        // Fallback fallback raw SDP text
                        offerSdp = postData
                    }

                    var answerSdp = ""
                    kotlin.runCatching {
                        runBlocking {
                            answerSdp = frameProvider.processWebRTCOffer(offerSdp)
                        }
                    }

                    try {
                        // camera-streamer 兼容格式
                        val responseObj = org.json.JSONObject()
                        responseObj.put("type", "answer")
                        responseObj.put("sdp", answerSdp)
                        val res = newFixedLengthResponse(Response.Status.OK, "application/json", responseObj.toString())
                        res.addHeader("Access-Control-Allow-Origin", "*")
                        return res
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", answerSdp)
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
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