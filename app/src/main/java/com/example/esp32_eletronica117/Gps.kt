package com.example.esp32_eletronica117

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Posição GPS do telemóvel (LocationManager do Android, sem bibliotecas extra).
 *
 * Uso:
 *   val gps = Gps(this) { lat, lon, precisao, fonte -> ... }
 *   gps.iniciar()   // no onResume() (depois de ter a permissão)
 *   gps.parar()     // no onPause()
 */
class Gps(
    private val context: Context,
    /** Recebe cada nova posição. */
    private val ouvinte: (latitude: Double, longitude: Double, precisaoMetros: Float, fonte: String) -> Unit
) {

    companion object {
        private const val INTERVALO_MS = 1000L   // pedir posição 1x por segundo
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var ultima: Location? = null

    // Implementa todos os métodos (e não só onLocationChanged) para funcionar em Android antigo,
    // onde a interface ainda não tinha implementações por omissão
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = tratar(location)

        @Deprecated("Necessário só para Android < 10")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    /** true se a app já tem autorização de localização. */
    fun temPermissao(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** true se o GPS (Localização) estiver ligado nas definições. */
    fun ligadoNoTelemovel(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    @SuppressLint("MissingPermission")
    fun iniciar(): Boolean {
        if (!temPermissao()) return false
        return try {
            val fornecedores = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            // Mostra logo a última posição conhecida, enquanto o GPS "aquece"
            for (p in fornecedores) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.getLastKnownLocation(p)?.let { tratar(it) }
                }
            }
            for (p in fornecedores) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, INTERVALO_MS, 0f, listener, Looper.getMainLooper())
                }
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun parar() {
        locationManager.removeUpdates(listener)
    }

    private fun tratar(nova: Location) {
        // Prefere o GPS; só aceita a rede se for mais recente (>10 s) ou mais precisa
        val anterior = ultima
        if (anterior != null &&
            nova.provider == LocationManager.NETWORK_PROVIDER &&
            anterior.provider == LocationManager.GPS_PROVIDER &&
            nova.time - anterior.time < 10_000 &&
            nova.accuracy > anterior.accuracy
        ) return

        ultima = nova
        val fonte = if (nova.provider == LocationManager.GPS_PROVIDER) "GPS" else "rede"
        ouvinte(nova.latitude, nova.longitude, nova.accuracy, fonte)
    }
}
