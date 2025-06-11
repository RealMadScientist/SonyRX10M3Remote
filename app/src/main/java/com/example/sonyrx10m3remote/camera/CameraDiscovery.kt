package com.example.sonyrx10m3remote.camera

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

object CameraDiscovery {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_MX = 3
    private const val SEARCH_TARGET = "urn:schemas-sony-com:service:ScalarWebAPI:1"

    /**
     * Sends an SSDP M-SEARCH, gets LOCATION URL,
     * then fetches and parses XML at LOCATION to extract real camera API URL.
     */
    suspend fun discoverCameraUrl(): String? = withContext(Dispatchers.IO) {
        try {
            // SSDP discovery
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 8000  // 8 seconds

            val searchRequest = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: $SSDP_MX\r\n")
                append("ST: $SEARCH_TARGET\r\n")
                append("\r\n")
            }

            val sendData = searchRequest.toByteArray()
            val address = InetAddress.getByName(SSDP_ADDRESS)
            val packet = DatagramPacket(sendData, sendData.size, address, SSDP_PORT)
            socket.send(packet)

            val buffer = ByteArray(2048)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket) // blocks until reply or timeout

            val response = String(responsePacket.data, 0, responsePacket.length)
            Log.d("CameraDiscovery", "SSDP response:\n$response")
            socket.close()

            val locationLine = response.lines()
                .firstOrNull { it.lowercase().startsWith("location:") }
            val locationUrl = locationLine
                ?.substringAfter(":", "")
                ?.trim()

            Log.d("CameraDiscovery", "Found LOCATION URL: $locationUrl")

            if (locationUrl.isNullOrEmpty()) {
                Log.e("CameraDiscovery", "No LOCATION header found")
                return@withContext null
            }

            // Fetch the XML from LOCATION URL
            val xmlContent = URL(locationUrl).readText()
            Log.d("CameraDiscovery", "Downloaded XML from LOCATION")

            // Parse XML to get real camera API URL
            return@withContext parseCameraApiUrl(xmlContent)
        } catch (e: Exception) {
            Log.e("CameraDiscovery", "Discovery failed: ${e.message}")
            return@withContext null
        }
    }

    private fun parseCameraApiUrl(xmlString: String): String? {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val inputStream = xmlString.byteInputStream()
            val doc: Document = builder.parse(inputStream)
            doc.documentElement.normalize()

            val serviceNodes = doc.getElementsByTagName("av:X_ScalarWebAPI_Service")

            for (i in 0 until serviceNodes.length) {
                val node = serviceNodes.item(i)
                val element = node as Element
                val typeNodeList = element.getElementsByTagName("av:X_ScalarWebAPI_ServiceType")
                if (typeNodeList.length > 0) {
                    val serviceType = typeNodeList.item(0).textContent
                    if (serviceType == "camera") {
                        val urlNodeList = element.getElementsByTagName("av:X_ScalarWebAPI_ActionList_URL")
                        if (urlNodeList.length > 0) {
                            val apiUrl = urlNodeList.item(0).textContent
                            if (!apiUrl.isNullOrEmpty()) {
                                Log.d("CameraDiscovery", "Parsed camera API URL: $apiUrl")
                                return apiUrl
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CameraDiscovery", "XML parsing failed: ${e.message}")
        }
        return null
    }
}