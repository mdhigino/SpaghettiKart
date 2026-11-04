package dev.mdhigino.mk64family;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import android.os.Bundle;

import org.libsdl.app.SDLActivity;

/**
 * Thin wrapper around SDL's activity. SDLActivity (copied from the SDL2 source
 * tree by the CI workflow) loads the native libraries and calls SDL_main(),
 * which lives in libmain.so (the game itself).
 */
public class MainActivity extends SDLActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Runs before the game starts, so even if the previous run crashed (or this
        // one does), the log of the previous run is saved next to the game files:
        //   Android/data/dev.mdhigino.mk64family/files/log-anterior.txt
        // This also creates that folder on the very first launch.
        saveLogOfPreviousRun();
        super.onCreate(savedInstanceState);
    }

    private void saveLogOfPreviousRun() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File out = new File(dir, "log-anterior.txt");
            // An app can read its own log entries (not other apps').
            Process p = Runtime.getRuntime().exec(new String[] { "logcat", "-d", "-v", "time" });
            try (InputStream in = p.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {
            // Logging must never be the reason the app fails to start.
        }
    }

    @Override
    protected String[] getLibraries() {
        // SDL's HIDAPI support ships as a separate libhidapi.so on Android.
        // Only ask for it if the build produced it.
        File dir = new File(getApplicationInfo().nativeLibraryDir);
        if (new File(dir, "libhidapi.so").exists()) {
            return new String[] { "hidapi", "SDL2", "main" };
        }
        return new String[] { "SDL2", "main" };
    }
}
