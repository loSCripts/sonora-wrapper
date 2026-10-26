package io.github.loscripts.sensormaps.test

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** L'écran unique de l'appli de test. */
class EcranPrincipal : Activity() {
    private lateinit var texteCapteurs: TextView
    private lateinit var texteTest: TextView
    private lateinit var boutonTest: Button
    private lateinit var boutonMesure: Button
    private lateinit var texteResume: TextView
    private lateinit var texteJournal: TextView
    private val boucle = Handler(Looper.getMainLooper())
    private val rafraichir = object : Runnable {
        override fun run() {
            afficher()
            boucle.postDelayed(this, 5_000)
        }
    }
    private var mesureEnCours = false

    override fun onCreate(etatSauve: Bundle?) {
        super.onCreate(etatSauve)
        val marge = dp(16)
        val colonne = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val defilement = ScrollView(this).apply {
            clipToPadding = false
            addView(colonne)
        }
        // Android 15 et plus dessinent l'appli sous les barres du système : on laisse la place
        ViewCompat.setOnApplyWindowInsetsListener(defilement) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + marge, b.top + marge, b.right + marge, b.bottom + marge)
            insets
        }

        colonne.addView(titre("Test Sensor Maps", 24f))
        colonne.addView(
            paragraphe(
                "Cette appli vérifie si ton téléphone peut lire la pression et le champ magnétique " +
                    "quand l'écran est éteint. Rien n'est envoyé : tout reste sur le téléphone, " +
                    "sauf si tu copies ou partages le journal.",
            ),
        )
        colonne.addView(titre("Capteurs du téléphone", 18f))
        texteCapteurs = paragraphe("").also(colonne::addView)
        colonne.addView(titre("Le test", 18f))
        texteTest = paragraphe("").also(colonne::addView)
        boutonTest = bouton("") { basculerTest() }.also(colonne::addView)
        boutonMesure = bouton(TEXTE_MESURER) { mesurerMaintenant() }.also(colonne::addView)
        colonne.addView(titre("Résultats", 18f))
        texteResume = paragraphe("").also(colonne::addView)
        colonne.addView(bouton("Copier le journal") { copier() })
        colonne.addView(bouton("Partager le journal") { partager() })
        colonne.addView(bouton("Effacer le journal") { effacer() })
        colonne.addView(titre("Journal, le plus récent en haut", 18f))
        texteJournal = paragraphe("").apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
        }.also(colonne::addView)
        setContentView(defilement)
    }

    override fun onResume() {
        super.onResume()
        boucle.post(rafraichir)
    }

    override fun onPause() {
        boucle.removeCallbacks(rafraichir)
        super.onPause()
    }

    private fun afficher() {
        texteCapteurs.text = descriptionCapteurs()
        val actif = Planif.actif(this)
        texteTest.text = if (actif) {
            "Test en cours depuis le ${Texte.dateHeure(Planif.debut(this))}. " +
                "Tu peux éteindre l'écran et poser le téléphone."
        } else {
            "Test arrêté."
        }
        boutonTest.text = if (actif) "Arrêter le test" else "Démarrer le test"
        texteResume.text = Resume.texte(this)
        val lignes = Journal.lire(this).lines().filter { it.isNotBlank() }
        texteJournal.text = if (lignes.isEmpty()) "Le journal est vide." else lignes.takeLast(80).reversed().joinToString("\n")
    }

    private fun descriptionCapteurs(): String {
        val b = Capteurs.barometre(this)
        val m = Capteurs.magnetometre(this)
        val l = Capteurs.capteurLumiere(this)
        return "Baromètre (pression) : " + (b?.let { "oui, ${it.name} (${it.vendor})" } ?: "non, ce téléphone n'en a pas") +
            "\nMagnétomètre (champ magnétique) : " + (m?.let { "oui, ${it.name} (${it.vendor})" } ?: "non") +
            "\nCapteur de lumière : " + (l?.let { "oui, ${it.name} (${it.vendor})" } ?: "non") +
            "\nTéléphone : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}" +
            "\nAppli : version ${BuildConfig.VERSION_NAME}"
    }

    private fun basculerTest() {
        if (Planif.actif(this)) {
            // Une confirmation : un appui de trop ne doit pas couper le test de la nuit
            AlertDialog.Builder(this)
                .setMessage("Arrêter le test ? Les mesures s'arrêteront jusqu'au prochain démarrage.")
                .setPositiveButton("Arrêter") { _, _ ->
                    Planif.arreter(this)
                    afficher()
                }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        val manquantes = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 29 && !accorde(Manifest.permission.ACTIVITY_RECOGNITION)) {
            manquantes += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (Build.VERSION.SDK_INT >= 33 && !accorde(Manifest.permission.POST_NOTIFICATIONS)) {
            manquantes += Manifest.permission.POST_NOTIFICATIONS
        }
        if (manquantes.isEmpty()) demarrer() else requestPermissions(manquantes.toTypedArray(), DEMANDE_AUTORISATIONS)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, resultats: IntArray) {
        super.onRequestPermissionsResult(code, permissions, resultats)
        if (code == DEMANDE_AUTORISATIONS) demarrer()
    }

    private fun demarrer() {
        val activite = Build.VERSION.SDK_INT < 29 || accorde(Manifest.permission.ACTIVITY_RECOGNITION)
        val notifications = Build.VERSION.SDK_INT < 33 || accorde(Manifest.permission.POST_NOTIFICATIONS)
        Journal.ecrire(this, descriptionCapteurs().replace("\n", " | "))
        Journal.ecrire(
            this,
            "Autorisations : activité physique ${accordeeOuRefusee(activite)}, notifications ${accordeeOuRefusee(notifications)}",
        )
        Planif.demarrer(this)
        afficher()
        Toast.makeText(this, "Test démarré : tu peux éteindre l'écran", Toast.LENGTH_LONG).show()
    }

    private fun mesurerMaintenant() {
        if (mesureEnCours) return
        mesureEnCours = true
        boutonMesure.isEnabled = false
        boutonMesure.text = "Mesure en cours, 10 secondes"
        val app = applicationContext
        Thread {
            val etat = Capteurs.etat(app)
            val r = Capteurs.rafale(app)
            Resume.noter(app, Methode.MANUEL, r)
            Journal.ecrire(app, Texte.ligne("Mesure à la main", etat, r))
            runOnUiThread {
                mesureEnCours = false
                boutonMesure.isEnabled = true
                boutonMesure.text = TEXTE_MESURER
                afficher()
            }
        }.start()
    }

    // Le résumé, puis le journal du plus ancien au plus récent
    private fun texteComplet(): String =
        "Journal du test Sensor Maps\n\n" + descriptionCapteurs() + "\n\n" +
            Resume.texte(this) + "\n\nJournal :\n" +
            Journal.lire(this).takeLast(200_000)

    private fun copier() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Journal Sensor Maps", texteComplet()))
        // Android 13 et plus affichent déjà leur propre message
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show()
    }

    private fun partager() {
        val envoi = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Journal du test Sensor Maps")
            .putExtra(Intent.EXTRA_TEXT, texteComplet())
        startActivity(Intent.createChooser(envoi, "Partager le journal"))
    }

    private fun effacer() {
        AlertDialog.Builder(this)
            .setMessage("Effacer tout le journal et les résultats ?")
            .setPositiveButton("Effacer") { _, _ ->
                Journal.effacer(this)
                Resume.effacer(this)
                afficher()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun accorde(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun accordeeOuRefusee(b: Boolean) = if (b) "accordée" else "refusée"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun titre(texte: String, taille: Float) = TextView(this).apply {
        text = texte
        setTextSize(TypedValue.COMPLEX_UNIT_SP, taille)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun paragraphe(texte: String) = TextView(this).apply {
        text = texte
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, 0, 0, dp(8))
    }

    private fun bouton(texte: String, action: () -> Unit) = Button(this).apply {
        text = texte
        isAllCaps = false
        setOnClickListener(View.OnClickListener { action() })
    }

    private companion object {
        const val DEMANDE_AUTORISATIONS = 1
        const val TEXTE_MESURER = "Mesurer maintenant (10 secondes)"
    }
}
