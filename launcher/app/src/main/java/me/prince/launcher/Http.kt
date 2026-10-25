package me.prince.launcher

import java.net.HttpURLConnection
import java.net.URL

fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String =
    String(httpBytes(url, headers))

fun httpBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 10_000
    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    return try {
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}
