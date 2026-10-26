package io.github.loscripts.sensormaps.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Après un redémarrage du téléphone ou une mise à jour de l'appli, les services Google oublient
 * la détection d'immobilité : on la relance. Le réveil de 15 minutes, lui, se relance tout seul.
 */
class RecepteurDemarrage : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Planif.actif(ctx)) return
        val raison = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "Téléphone redémarré"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "Appli mise à jour"
            else -> return
        }
        Journal.ecrire(ctx, "$raison : détection d'immobilité relancée")
        Planif.inscrireImmobile(ctx)
    }
}
