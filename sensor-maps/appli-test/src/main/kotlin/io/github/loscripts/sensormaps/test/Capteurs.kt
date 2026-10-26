package io.github.loscripts.sensormaps.test

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Ce qu'un capteur a envoyé pendant une rafale. */
class Lecture(val nom: String, val unite: String) {
    var inscrit = false
    var nb = 0
    var premiereApresMs = -1L
    val valeurs = ArrayList<Float>()

    fun mediane(): Float? = if (valeurs.isEmpty()) null else valeurs.sorted()[valeurs.size / 2]
}

/** Une rafale : null quand le téléphone n'a pas ce capteur. */
class Rafale(val pression: Lecture?, val magnetisme: Lecture?)

/** L'état du téléphone au moment d'une mesure. */
class Etat(
    val ecranAllume: Boolean,
    val veilleProfonde: Boolean,
    val economieEnergie: Boolean,
    val batterie: Int,
    val surSecteur: Boolean,
    val temperatureBatterie: Float?,
    val groupeAndroid: String,
    val sansOptimisation: Boolean,
)

object Capteurs {
    const val DUREE_RAFALE_MS = 10_000L

    private fun gestionnaire(ctx: Context) =
        ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun barometre(ctx: Context): Sensor? = gestionnaire(ctx).getDefaultSensor(Sensor.TYPE_PRESSURE)

    fun magnetometre(ctx: Context): Sensor? = gestionnaire(ctx).getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /**
     * Allume le baromètre et le magnétomètre pendant [dureeMs], compte ce qui arrive, puis éteint tout.
     * Bloquant : à appeler hors du fil de l'écran.
     */
    fun rafale(ctx: Context, dureeMs: Long = DUREE_RAFALE_MS): Rafale {
        val app = ctx.applicationContext
        val sm = gestionnaire(app)
        val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val magn = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val pression = baro?.let { Lecture("pression", "hPa") }
        val magnetisme = magn?.let { Lecture("champ magnétique", "µT") }

        // Le processeur doit rester éveillé pendant la rafale, sinon rien n'arrive écran éteint
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val eveil = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sensormaps:rafale")
        eveil.acquire(dureeMs + 5_000)

        val fil = HandlerThread("rafale").apply { start() }
        val debut = SystemClock.elapsedRealtime()
        val ecouteur = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val l = (if (e.sensor.type == Sensor.TYPE_PRESSURE) pression else magnetisme) ?: return
                if (l.nb == 0) l.premiereApresMs = SystemClock.elapsedRealtime() - debut
                l.nb++
                val v = if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                } else {
                    e.values[0]
                }
                if (l.valeurs.size < 5_000) l.valeurs.add(v)
            }

            override fun onAccuracyChanged(s: Sensor, precision: Int) {}
        }
        val h = Handler(fil.looper)
        try {
            if (baro != null && pression != null) {
                pression.inscrit = sm.registerListener(ecouteur, baro, SensorManager.SENSOR_DELAY_NORMAL, h)
            }
            if (magn != null && magnetisme != null) {
                magnetisme.inscrit = sm.registerListener(ecouteur, magn, SensorManager.SENSOR_DELAY_NORMAL, h)
            }
            Thread.sleep(dureeMs)
        } catch (_: InterruptedException) {
        } finally {
            sm.unregisterListener(ecouteur)
            fil.quitSafely()
            fil.join(2_000)
            if (eveil.isHeld) eveil.release()
        }
        return Rafale(pression, magnetisme)
    }

    fun etat(ctx: Context): Etat {
        val app = ctx.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val niveau = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val echelle = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val dixiemes = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val groupe = if (Build.VERSION.SDK_INT >= 28) {
            val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            nomGroupe(usm.appStandbyBucket)
        } else {
            "inconnu"
        }
        return Etat(
            ecranAllume = pm.isInteractive,
            veilleProfonde = pm.isDeviceIdleMode,
            economieEnergie = pm.isPowerSaveMode,
            batterie = if (niveau >= 0 && echelle > 0) niveau * 100 / echelle else -1,
            surSecteur = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            temperatureBatterie = if (dixiemes == Int.MIN_VALUE) null else dixiemes / 10f,
            groupeAndroid = groupe,
            sansOptimisation = pm.isIgnoringBatteryOptimizations(app.packageName),
        )
    }

    // Le « groupe » où Android range l'appli selon son usage : plus il est rare, plus elle est freinée
    private fun nomGroupe(g: Int) = when (g) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "quotidienne"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "fréquente"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
        45 -> "restreinte" // STANDBY_BUCKET_RESTRICTED, Android 11 et plus
        else -> "code $g"
    }
}
