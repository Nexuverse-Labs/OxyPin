package ru.oxypin;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class PinTile extends TileService {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mResync = new Runnable() {
        @Override
        public void run() {
            updateTileState();
        }
    };

    private static boolean isPinned() {
        try {
            final Class<?> amClass = Class.forName("android.app.ActivityManager");
            final Object am = amClass.getMethod("getService").invoke(null);
            final Object st = am.getClass().getMethod("getLockTaskModeState").invoke(am);
            return st instanceof Integer && (Integer) st != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updateTileState() {
        final Tile t = getQsTile();
        if (t == null) return;
        t.setState(isPinned() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override
    public void onStartListening() {
        updateTileState();
    }

    @Override
    public void onClick() {
        final boolean pinned = isPinned();
        final Intent i = new Intent(pinned ? OxyPin.ACTION_UNPIN : OxyPin.ACTION_PIN);
        try {
            i.putExtra(OxyPin.EXTRA_TOKEN, Settings.Secure.getInt(getContentResolver(), OxyPin.TOKEN_KEY));
        } catch (Throwable ignored) {
        }
        try {
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
        final Tile t = getQsTile();
        if (t != null) {
            t.setState(pinned ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
            t.updateTile();
        }
        mHandler.removeCallbacks(mResync);
        mHandler.postDelayed(mResync, 700);
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mResync);
        super.onDestroy();
    }
}
