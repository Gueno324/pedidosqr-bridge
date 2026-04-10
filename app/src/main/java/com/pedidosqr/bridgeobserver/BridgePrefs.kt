package com.pedidosqr.bridgeobserver

import android.content.Context

class BridgePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)

    val barId: String
        get() = prefs.getString(KEY_BAR_ID, "pedidosqr-pruebas").orEmpty()

    val printerIp: String
        get() = prefs.getString(KEY_PRINTER_IP, "192.168.1.228").orEmpty()

    val printerPort: Int
        get() = prefs.getInt(KEY_PRINTER_PORT, 9100)

    fun save(config: BridgeConfig) {
        prefs.edit()
            .putString(KEY_BAR_ID, config.barId)
            .putString(KEY_PRINTER_IP, config.printerIp)
            .putInt(KEY_PRINTER_PORT, config.printerPort)
            .apply()
    }

    companion object {
        private const val KEY_BAR_ID = "bar_id"
        private const val KEY_PRINTER_IP = "printer_ip"
        private const val KEY_PRINTER_PORT = "printer_port"
    }
}
