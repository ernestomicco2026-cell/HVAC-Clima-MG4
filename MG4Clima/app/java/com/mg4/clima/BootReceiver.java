package com.mg4.clima;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** All'accensione e dopo un aggiornamento: riattiva il servizio di sostituzione (se qualcuno l'ha tolto). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        ClimaGuard.ensureEnabled(c);
        // dopo un aggiornamento del firmware il SystemUI corretto si toglie da solo se la versione non è più supportata
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            try {
                SysUiPatch.bootCheck(c);
            } catch (Throwable ignored) {
            }
        }
    }
}
