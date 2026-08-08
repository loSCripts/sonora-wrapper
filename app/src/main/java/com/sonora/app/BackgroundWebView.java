package com.sonora.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView qui ne signale JAMAIS a Chromium que sa fenetre est passee en
 * arriere-plan.
 *
 * ATTENTION : super() doit recevoir VISIBLE dans TOUS les cas. Android envoie
 * GONE quand l'app passe en arriere-plan : filtrer ce cas revient a laisser
 * passer exactement le signal qu'on veut bloquer.
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
        super.onWindowVisibilityChanged(View.VISIBLE);
    }
}
