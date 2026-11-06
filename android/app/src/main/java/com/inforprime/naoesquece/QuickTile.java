package com.inforprime.naoesquece;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Bloco das configurações rápidas do Android (puxar a barra de cima): abre a nota rápida ou o gravador. */
public abstract class QuickTile extends TileService {
    abstract String mode();

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override
    public void onClick() {
        if (isLocked()) unlockAndRun(this::open);
        else open();
    }

    @SuppressWarnings("deprecation")
    private void open() {
        Intent i = QuickActivity.intent(this, mode()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, mode().hashCode(), i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
