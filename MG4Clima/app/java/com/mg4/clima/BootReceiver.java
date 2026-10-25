package com.mg4.clima;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** All'accensione e dopo un aggiornamento: riattiva il servizio di sostituzione (se qualcuno l'ha tolto). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        ClimaGuard.ensureEnabled(c);
    }
}
