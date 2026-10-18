package com.trevisolli.agendawidget.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object Http {
    fun getJson(url: String, bearer: String): JSONObject =
        request("GET", url, mapOf("Authorization" to "Bearer $bearer"), null)

    fun get(url: String, headers: Map<String, String>): JSONObject = request("GET", url, headers, null)

    fun postJson(url: String, headers: Map<String, String>, body: JSONObject): JSONObject =
        request("POST", url, headers, body)

    private fun request(method: String, url: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code ${err.take(300)}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
