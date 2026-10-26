package io.github.loscripts.sensormaps.test

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Méthode 1 : Android réveille l'appli toutes les 15 minutes environ, sans service au premier plan. */
class TravailReveil(ctx: Context, parametres: WorkerParameters) : Worker(ctx, parametres) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val etat = Capteurs.etat(ctx)
        val rafale = Capteurs.rafale(ctx)
        Resume.noter(ctx, Methode.REVEIL, rafale)
        Journal.ecrire(ctx, Texte.ligne("Réveil 15 min", etat, rafale))
        return Result.success()
    }
}
