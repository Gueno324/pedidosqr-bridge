package com.pedidosqr.bridgeobserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.pedidosqr.bridgeobserver.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: BridgePrefs
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var observerJob: Job? = null
    private val activityScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = BridgePrefs(this)
        loadSavedConfig()
        refreshPrinterStatusIdle()

        binding.buttonSave.setOnClickListener {
            saveConfig()
        }

        binding.buttonToggle.setOnClickListener {
            if (observerJob == null) {
                saveConfig()
                startObserverMode()
            } else {
                stopObserverMode(manual = true)
            }
        }

        binding.editBarId.doAfterTextChanged { updateStoppedStateHint() }
        binding.editPrinterIp.doAfterTextChanged { updateStoppedStateHint() }
        binding.editPrinterPort.doAfterTextChanged { updateStoppedStateHint() }
    }

    override fun onDestroy() {
        stopObserverMode(manual = false)
        super.onDestroy()
    }

    private fun loadSavedConfig() {
        binding.editBarId.setText(prefs.barId)
        binding.editPrinterIp.setText(prefs.printerIp)
        binding.editPrinterPort.setText(prefs.printerPort.toString())
    }

    private fun saveConfig() {
        val config = getConfigFromUi()
        prefs.save(config)
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        updateStoppedStateHint()
    }

    private fun getConfigFromUi(): BridgeConfig {
        val portValue = binding.editPrinterPort.text?.toString()?.trim()?.toIntOrNull() ?: 9100
        return BridgeConfig(
            barId = binding.editBarId.text?.toString()?.trim().orEmpty(),
            printerIp = binding.editPrinterIp.text?.toString()?.trim().orEmpty(),
            printerPort = portValue
        )
    }

    private fun startObserverMode() {
        val config = getConfigFromUi()
        if (config.barId.isBlank()) {
            Toast.makeText(this, "Introduce un barId", Toast.LENGTH_SHORT).show()
            return
        }

        binding.buttonToggle.text = "Detener"
        binding.textFirebaseStatus.text = "Firebase: iniciando observación"
        binding.textLastTicket.text = "Esperando tickets pendientes..."

        observerJob = activityScope.launch {
            while (isActive) {
                val nowText = nowText()
                binding.textLastCheck.text = "Última comprobación: $nowText"

                if (!hasInternetConnection()) {
                    binding.textFirebaseStatus.text = "Firebase: sin conexión de red"
                    binding.textPrinterStatus.text = "Impresora: pendiente"
                    delay(5000)
                    continue
                }

                updatePrinterReachability(config.printerIp, config.printerPort)
                val result = withContext(Dispatchers.IO) {
                    fetchPendingTicket(config.barId)
                }

                when (result) {
                    is TicketFetchResult.Success -> {
                        binding.textFirebaseStatus.text = "Firebase: conectado"
                        binding.textLastTicket.text = result.description
                    }
                    is TicketFetchResult.Empty -> {
                        binding.textFirebaseStatus.text = "Firebase: conectado"
                        binding.textLastTicket.text = "No hay tickets con impreso = false en este momento."
                    }
                    is TicketFetchResult.Error -> {
                        binding.textFirebaseStatus.text = "Firebase: error (${result.message})"
                    }
                }
                delay(4000)
            }
        }
    }

    private fun stopObserverMode(manual: Boolean) {
        observerJob?.cancel()
        observerJob = null
        binding.buttonToggle.text = "Iniciar"
        binding.textFirebaseStatus.text = if (manual) "Firebase: observación detenida" else "Firebase: detenido"
        refreshPrinterStatusIdle()
        updateStoppedStateHint()
    }

    private fun refreshPrinterStatusIdle() {
        binding.textPrinterStatus.text = "Impresora: pendiente"
    }

    private fun updateStoppedStateHint() {
        if (observerJob == null) {
            binding.textLastCheck.text = "Última comprobación: -"
        }
    }

    private suspend fun updatePrinterReachability(ip: String, port: Int) {
        if (ip.isBlank()) {
            binding.textPrinterStatus.text = "Impresora: IP pendiente"
            return
        }

        val reachable = withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 1200)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

        binding.textPrinterStatus.text = if (reachable) {
            "Impresora: accesible en $ip:$port"
        } else {
            "Impresora: no accesible en $ip:$port"
        }
    }

    private fun fetchPendingTicket(barId: String): TicketFetchResult {
        val url = "https://pedidosqr-pruebas-default-rtdb.europe-west1.firebasedatabase.app/bares/$barId/ticketsPendientes.json"
        val request = Request.Builder().url(url).get().build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return TicketFetchResult.Error("HTTP ${response.code}")
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank() || body == "null") {
                    return TicketFetchResult.Empty
                }

                val root = JSONObject(body)
                val keys = root.keys()
                var foundDescription: String? = null

                while (keys.hasNext()) {
                    val key = keys.next()
                    val ticket = root.optJSONObject(key) ?: continue
                    val impreso = ticket.optBoolean("impreso", true)
                    if (!impreso) {
                        foundDescription = buildTicketDescription(key, ticket)
                        break
                    }
                }

                if (foundDescription == null) TicketFetchResult.Empty else TicketFetchResult.Success(foundDescription)
            }
        } catch (e: IOException) {
            TicketFetchResult.Error(e.localizedMessage ?: "Error de red")
        } catch (e: Exception) {
            TicketFetchResult.Error(e.localizedMessage ?: "Error desconocido")
        }
    }

    private fun buildTicketDescription(ticketId: String, ticket: JSONObject): String {
        val mesa = ticket.optString("mesa", "-")
        val zona = ticket.optString("zona", "-")
        val origen = ticket.optString("origen", "-")
        val tipo = ticket.optString("tipo", "-")
        val timestamp = ticket.optString("timestamp", ticket.optString("hora", "-"))
        val total = ticket.optString("total", "-")

        val itemsSummary = when {
            ticket.has("lineas") -> summarizeArray(ticket.optJSONArray("lineas"))
            ticket.has("productos") -> summarizeArray(ticket.optJSONArray("productos"))
            ticket.has("items") -> summarizeArray(ticket.optJSONArray("items"))
            else -> "Sin detalle de líneas"
        }

        return buildString {
            appendLine("Ticket detectado")
            appendLine("ID: $ticketId")
            appendLine("Mesa: $mesa")
            appendLine("Zona: $zona")
            appendLine("Origen: $origen")
            appendLine("Tipo: $tipo")
            appendLine("Hora/Timestamp: $timestamp")
            appendLine("Total: $total")
            appendLine("Líneas: $itemsSummary")
            appendLine("impreso = false")
        }.trim()
    }

    private fun summarizeArray(array: org.json.JSONArray?): String {
        if (array == null || array.length() == 0) return "Sin líneas"
        val parts = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val nombre = item.optString("nombre", item.optString("titulo", "Producto"))
            val cantidad = item.optString("cantidad", item.optString("qty", "1"))
            parts.add("$cantidad x $nombre")
        }
        return if (parts.isEmpty()) "Sin líneas" else parts.joinToString(" · ")
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun nowText(): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
    }
}

data class BridgeConfig(
    val barId: String,
    val printerIp: String,
    val printerPort: Int
)

private sealed class TicketFetchResult {
    data class Success(val description: String) : TicketFetchResult()
    data object Empty : TicketFetchResult()
    data class Error(val message: String) : TicketFetchResult()
}
