package dev.mdhigino.mk64family;

import java.io.File;

import org.libsdl.app.SDLActivity;

/**
 * Thin wrapper around SDL's activity. SDLActivity (copied from the SDL2 source
 * tree by the CI workflow) loads the native libraries and calls SDL_main(),
 * which lives in libmain.so (the game itself).
 */
public class MainActivity extends SDLActivity {
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
