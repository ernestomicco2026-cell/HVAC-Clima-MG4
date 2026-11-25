package com.mg4.clima;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** All'accensione e dopo un aggiornamento: riattiva il servizio di sostituzione (se qualcuno l'ha tolto). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        ClimaGuard.ensureEnabled(c);
        // 1.4.6: per la riga clima.avvio (in avvio bloccato le preferenze non si leggono: solo in memoria)
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(i.getAction()))
            ClimaGuard.bootReceivedS = android.os.SystemClock.elapsedRealtime() / 1000;
    }
}
