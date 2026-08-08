package com.sonora.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView qui ne signale JAMAIS a Chromium que sa fenetre est passee en
 * arriere-plan.
 *
 * Sans ca, des que l'app quitte le premier plan, Chromium bascule le document
 * en "hidden" et declenche visibilitychange -- dans la page ET dans toutes ses
 * iframes. Les lecteurs embarques reagissent en coupant le son.
 *
 * En forcant systematiquement VISIBLE, document.visibilityState reste
 * "visible" en permanence, exactement comme le comportement observe sur Opera.
 *
 * Le test "!= View.GONE" laisse passer le cas ou la vue est reellement retiree
 * (destruction de l'activite), pour ne pas empecher le nettoyage.
 */
public class BackgroundWebView extends WebView {

    public BackgroundWebView(Context context) {
        super(context);
    }

    public BackgroundWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BackgroundWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != View.GONE) {
            super.onWindowVisibilityChanged(View.VISIBLE);
        } else {
            super.onWindowVisibilityChanged(visibility);
        }
    }
}
