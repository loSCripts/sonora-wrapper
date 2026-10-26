package io.github.loscripts.sensormaps.test

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Le journal du test : un fichier texte qui reste sur le téléphone. */
object Journal {
    private const val FICHIER = "journal.txt"
    private const val TAILLE_MAX = 400_000L
    private const val LIGNES_GARDEES = 2_000
    private val heure = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)

    @Synchronized
    fun ecrire(ctx: Context, texte: String) {
        // Aussi dans les messages système : l'essai automatique sur émulateur les lit là
        Log.i("SensorMapsTest", texte)
        val f = File(ctx.applicationContext.filesDir, FICHIER)
        f.appendText(heure.format(Date()) + " | " + texte + "\n")
        if (f.length() > TAILLE_MAX) {
            f.writeText(f.readLines().takeLast(LIGNES_GARDEES).joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized
    fun lire(ctx: Context): String {
        val f = File(ctx.applicationContext.filesDir, FICHIER)
        return if (f.exists()) f.readText() else ""
    }

    @Synchronized
    fun effacer(ctx: Context) {
        File(ctx.applicationContext.filesDir, FICHIER).delete()
    }
}

/** Les trois façons de mesurer comparées par le test, plus la mesure à la main. */
enum class Methode(val cle: String, val titre: String) {
    REVEIL("reveil", "Réveils toutes les 15 min"),
    IMMOBILE("immobile", "Mesures par le service, après immobilité"),
    DIRECT("direct", "Mesures directes, quand le service est refusé"),
    MANUEL("manuel", "Mesures à la main"),
}

/**
 * Les compteurs affichés dans « Résultats ». Ils sont écrits avec commit() : tout de suite,
 * car Android peut couper l'appli juste après une mesure faite en arrière-plan.
 */
@SuppressLint("ApplySharedPref")
object Resume {
    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE)

    @Synchronized
    fun noter(ctx: Context, m: Methode, r: Rafale) {
        val p = prefs(ctx)
        val e = p.edit()
        e.putInt(m.cle + "_total", p.getInt(m.cle + "_total", 0) + 1)
        if ((r.pression?.nb ?: 0) > 0) e.putInt(m.cle + "_pression", p.getInt(m.cle + "_pression", 0) + 1)
        if ((r.magnetisme?.nb ?: 0) > 0) e.putInt(m.cle + "_magn", p.getInt(m.cle + "_magn", 0) + 1)
        if ((r.lumiere?.nb ?: 0) > 0) e.putInt(m.cle + "_lumiere", p.getInt(m.cle + "_lumiere", 0) + 1)
        e.putLong(m.cle + "_dernier", System.currentTimeMillis())
        e.commit()
    }

    @Synchronized
    fun compter(ctx: Context, cle: String) {
        val p = prefs(ctx)
        p.edit().putInt(cle, p.getInt(cle, 0) + 1).commit()
    }

    @Synchronized
    fun effacer(ctx: Context) {
        prefs(ctx).edit().clear().commit()
    }

    fun texte(ctx: Context): String {
        val p = prefs(ctx)
        val aBarometre = Capteurs.barometre(ctx) != null
        val aLumiere = Capteurs.capteurLumiere(ctx) != null
        val lignes = mutableListOf<String>()
        fun bloc(m: Methode) {
            val total = p.getInt(m.cle + "_total", 0)
            val dernier = p.getLong(m.cle + "_dernier", 0L)
            lignes += "${m.titre} : $total" + if (dernier > 0) ", dernière fois le ${Texte.dateHeure(dernier)}" else ""
            if (total > 0) {
                val recu = mutableListOf<String>()
                if (aBarometre) recu += "pression reçue ${p.getInt(m.cle + "_pression", 0)} fois"
                recu += "champ magnétique reçu ${p.getInt(m.cle + "_magn", 0)} fois"
                if (aLumiere) recu += "lumière reçue ${p.getInt(m.cle + "_lumiere", 0)} fois"
                lignes += "    " + recu.joinToString(", ")
            }
        }
        bloc(Methode.REVEIL)
        lignes += "Téléphone devenu immobile : ${p.getInt("immobile_detecte", 0)} fois" +
            ", service refusé par Android ${p.getInt("immobile_refuse", 0)} fois"
        bloc(Methode.IMMOBILE)
        if (p.getInt(Methode.DIRECT.cle + "_total", 0) > 0) bloc(Methode.DIRECT)
        bloc(Methode.MANUEL)
        return lignes.joinToString("\n")
    }
}

/** Mise en forme des lignes du journal, en français. */
object Texte {
    private val jourHeure = SimpleDateFormat("dd/MM 'à' HH:mm", Locale.FRANCE)

    fun nombre(v: Float): String = String.format(Locale.FRANCE, "%.1f", v)

    @Synchronized
    fun dateHeure(ms: Long): String = jourHeure.format(Date(ms))

    private fun lectures(n: Int) = if (n <= 1) "$n lecture" else "$n lectures"

    private fun lecture(l: Lecture?, absent: String): String {
        if (l == null) return absent
        if (!l.inscrit) return "${l.nom} : capteur refusé par Android"
        val mediane = l.mediane() ?: return "${l.nom} : 0 lecture"
        return "${l.nom} : ${lectures(l.nb)}, la première après ${nombre(l.premiereApresMs / 1000f)} s, " +
            "${nombre(mediane)} ${l.unite}" + if (l.avecPrecision) ", précision ${precision(l.precision)}" else ""
    }

    private fun precision(p: Int) = when (p) {
        3 -> "haute"
        2 -> "moyenne"
        1 -> "basse"
        0 -> "pas fiable"
        else -> "inconnue ($p)"
    }

    fun etat(e: Etat): String {
        val morceaux = mutableListOf<String>()
        morceaux += if (e.ecranAllume) "écran allumé" else "écran éteint"
        if (e.veilleProfonde) morceaux += "veille profonde"
        if (e.economieEnergie) morceaux += "économie d'énergie"
        morceaux += "batterie ${e.batterie} %" + if (e.surSecteur) " sur secteur" else ""
        e.temperatureBatterie?.let { morceaux += "batterie à ${nombre(it)} °C" }
        morceaux += "usage ${e.groupeAndroid}"
        if (e.sansOptimisation) morceaux += "sans optimisation de batterie"
        return morceaux.joinToString(", ")
    }

    fun ligne(titre: String, e: Etat, r: Rafale): String = listOf(
        titre,
        etat(e),
        lecture(r.pression, "pas de baromètre"),
        lecture(r.magnetisme, "pas de magnétomètre"),
        lecture(r.lumiere, "pas de capteur de lumière"),
    ).joinToString(" | ")
}
