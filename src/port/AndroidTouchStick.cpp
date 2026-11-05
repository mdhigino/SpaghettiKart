// Analog on-screen stick for Android.
//
// The touch UI (Java: TouchControlsView) reports the finger position here. We expose it to
// the game as a real SDL gamepad ("virtual joystick") whose left stick is that position.
// libultraship already reads the left stick of every connected SDL gamepad for player 1, so
// no other game code needs to change, and a physical Bluetooth pad keeps working alongside.
#ifdef __ANDROID__

#include <jni.h>
#include <SDL2/SDL.h>

#include <cmath>
#include <mutex>

namespace {
std::mutex sMutex;
SDL_Joystick* sPad = nullptr;

// SDL's joystick subsystem is brought up by the game a few seconds after the app starts, so
// this may legitimately fail at first. The Java side simply retries until it succeeds.
bool EnsurePad() {
    if (sPad != nullptr) {
        return true;
    }
    if (SDL_WasInit(SDL_INIT_JOYSTICK) == 0) {
        return false;
    }

    SDL_VirtualJoystickDesc desc;
    SDL_zero(desc);
    desc.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
    desc.type = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
    desc.naxes = 2; // with type GAMECONTROLLER SDL maps these to LEFTX / LEFTY
    desc.nbuttons = 0;
    desc.name = "Direcional na tela";

    int index = SDL_JoystickAttachVirtualEx(&desc);
    if (index < 0) {
        SDL_Log("TouchStick: SDL_JoystickAttachVirtualEx failed: %s", SDL_GetError());
        return false;
    }

    sPad = SDL_JoystickOpen(index);
    if (sPad == nullptr) {
        SDL_Log("TouchStick: SDL_JoystickOpen failed: %s", SDL_GetError());
        SDL_JoystickDetachVirtual(index);
        return false;
    }

    SDL_Log("TouchStick: virtual analog gamepad attached (device index %d)", index);
    return true;
}
} // namespace

// x, y in [-1, 1]; y is positive DOWN (same convention as a physical SDL stick).
// Returns true when the virtual pad exists and the value was applied.
extern "C" JNIEXPORT jboolean JNICALL Java_dev_mdhigino_mk64family_TouchControlsView_nativeStick(JNIEnv*, jclass,
                                                                                                  jfloat x,
                                                                                                  jfloat y) {
    std::lock_guard<std::mutex> lock(sMutex);
    if (!EnsurePad()) {
        return JNI_FALSE;
    }

    float len = std::sqrt(x * x + y * y);
    if (len > 1.0f) {
        x /= len;
        y /= len;
    }

    SDL_JoystickSetVirtualAxis(sPad, 0, static_cast<Sint16>(x * 32767.0f));
    SDL_JoystickSetVirtualAxis(sPad, 1, static_cast<Sint16>(y * 32767.0f));
    return JNI_TRUE;
}

#endif // __ANDROID__
