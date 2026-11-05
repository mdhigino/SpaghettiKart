package dev.mdhigino.mk64family;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

/**
 * On-screen controls drawn over the game. They do not talk to the game directly: each
 * button injects the keyboard key that the game already maps to that N64 button by
 * default (A=Shift, B=Ctrl, Z=Z, R=Space, Start=Enter, stick=arrow keys, Menu=Esc).
 *
 * Touches that do not land on a control are NOT consumed, so the game's own menus
 * (ImGui) still receive them.
 */
public class TouchControlsView extends View {

    private static final String PREFS = "touch_controls";
    private static final String PREF_HIDDEN = "hidden";

    private static class Btn {
        final String label;
        final int keycode;
        final float rDp;
        final int color;
        float cx, cy, r;
        int pointerId = -1;

        Btn(String label, int keycode, float rDp, int color) {
            this.label = label;
            this.keycode = keycode;
            this.rDp = rDp;
            this.color = color;
        }

        boolean down() {
            return pointerId != -1;
        }
    }

    // Mario Kart 64: A accelerate, B brake, Z use item, R hop/drift, C-Up change camera,
    // C-Down look behind.
    private final Btn btnA = new Btn("A", KeyEvent.KEYCODE_SHIFT_LEFT, 46, 0xFF3CB371);
    private final Btn btnB = new Btn("B", KeyEvent.KEYCODE_CTRL_LEFT, 34, 0xFFE04B4B);
    private final Btn btnZ = new Btn("Z", KeyEvent.KEYCODE_Z, 36, 0xFF4B7BE0);
    private final Btn btnR = new Btn("R", KeyEvent.KEYCODE_SPACE, 30, 0xFFE0B84B);
    private final Btn btnStart = new Btn("START", KeyEvent.KEYCODE_ENTER, 28, 0xFFBBBBBB);
    private final Btn btnCam = new Btn("CAM", KeyEvent.KEYCODE_T, 24, 0xFF9B7BE0);
    private final Btn btnBack = new Btn("TRÁS", KeyEvent.KEYCODE_G, 24, 0xFF9B7BE0);
    private final Btn btnMenu = new Btn("MENU", KeyEvent.KEYCODE_ESCAPE, 24, 0xFFBBBBBB);
    private final Btn[] gameButtons = { btnA, btnB, btnZ, btnR, btnStart, btnCam, btnBack, btnMenu };

    // The "hide / show" button is handled by the view itself (it sends no key).
    private final Btn btnToggle = new Btn("", 0, 24, 0xFFBBBBBB);

    // Virtual stick (4 arrow keys, 8 directions)
    private static final int[] STICK_KEYS = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
    };
    private final boolean[] stickDown = new boolean[4];
    private int stickPointer = -1;
    private float stickCx, stickCy, stickR, thumbX, thumbY;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean hidden;

    // Analog stick: implemented natively (src/port/AndroidTouchStick.cpp) as a virtual SDL
    // gamepad. If it is not available (yet), the stick falls back to the 4 arrow keys.
    private static native boolean nativeStick(float x, float y);

    private int attachTries = 0;
    private final Runnable attachTask = new Runnable() {
        @Override
        public void run() {
            // The virtual pad can only be created after the game initialised SDL's joystick
            // system, a few seconds after launch, so keep trying quietly until it works.
            if (!sendAnalog(0f, 0f) && attachTries++ < 120) {
                postDelayed(this, 1000);
            }
        }
    };

    private boolean sendAnalog(float x, float y) {
        try {
            return nativeStick(x, y);
        } catch (Throwable t) { // UnsatisfiedLinkError etc.
            return false;
        }
    }

    public TouchControlsView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * density);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        try {
            hidden = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_HIDDEN, false);
        } catch (Throwable ignored) {
        }
        setHapticFeedbackEnabled(true);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float d = density;
        place(btnA, w - 70 * d, h - 80 * d);
        place(btnB, w - 160 * d, h - 50 * d);
        place(btnZ, w - 70 * d, h - 175 * d);
        place(btnR, w - 160 * d, h - 130 * d);
        place(btnStart, w / 2f, 30 * d);
        place(btnCam, w - 40 * d, 36 * d);
        place(btnBack, w - 100 * d, 36 * d);
        place(btnMenu, 36 * d, 36 * d);
        place(btnToggle, 96 * d, 36 * d);

        stickR = 70 * d;
        stickCx = 40 * d + stickR;
        stickCy = h - 40 * d - stickR;
        thumbX = stickCx;
        thumbY = stickCy;
    }

    private void place(Btn b, float cx, float cy) {
        b.cx = cx;
        b.cy = cy;
        b.r = b.rDp * density;
    }

    // ---------------------------------------------------------------- key injection

    private void keyDown(int keycode) {
        try {
            SDLActivity.onNativeKeyDown(keycode);
        } catch (Throwable ignored) {
            // Native library not ready yet: ignore the touch.
        }
    }

    private void keyUp(int keycode) {
        try {
            SDLActivity.onNativeKeyUp(keycode);
        } catch (Throwable ignored) {
        }
    }

    private void buzz() {
        try {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- touch handling

    private static boolean hit(Btn b, float x, float y) {
        float dx = x - b.cx, dy = y - b.cy, rr = b.r * 1.2f;
        return dx * dx + dy * dy <= rr * rr;
    }

    private boolean claim(int pointerId, float x, float y) {
        if (hit(btnToggle, x, y)) {
            setHidden(!hidden);
            buzz();
            return true;
        }
        if (hidden) {
            return false;
        }
        for (Btn b : gameButtons) {
            if (!b.down() && hit(b, x, y)) {
                b.pointerId = pointerId;
                keyDown(b.keycode);
                buzz();
                return true;
            }
        }
        float dx = x - stickCx, dy = y - stickCy, rr = stickR * 1.5f;
        if (stickPointer == -1 && dx * dx + dy * dy <= rr * rr) {
            stickPointer = pointerId;
            updateStick(x, y);
            return true;
        }
        return false;
    }

    private void updateStick(float x, float y) {
        float dx = x - stickCx, dy = y - stickCy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > stickR) {
            dx = dx / len * stickR;
            dy = dy / len * stickR;
        }
        thumbX = stickCx + dx;
        thumbY = stickCy + dy;

        boolean[] want;
        if (sendAnalog(dx / stickR, dy / stickR)) {
            // Analog works: make sure no arrow key is left pressed from the fallback mode.
            want = new boolean[] { false, false, false, false };
        } else {
            float thr = stickR * 0.3f;
            want = new boolean[] { dy < -thr, dy > thr, dx < -thr, dx > thr };
        }
        for (int i = 0; i < 4; i++) {
            if (want[i] != stickDown[i]) {
                stickDown[i] = want[i];
                if (want[i]) {
                    keyDown(STICK_KEYS[i]);
                } else {
                    keyUp(STICK_KEYS[i]);
                }
            }
        }
    }

    private void releaseStick() {
        stickPointer = -1;
        thumbX = stickCx;
        thumbY = stickCy;
        sendAnalog(0f, 0f);
        for (int i = 0; i < 4; i++) {
            if (stickDown[i]) {
                stickDown[i] = false;
                keyUp(STICK_KEYS[i]);
            }
        }
    }

    private void releasePointer(int pointerId) {
        for (Btn b : gameButtons) {
            if (b.pointerId == pointerId) {
                b.pointerId = -1;
                keyUp(b.keycode);
            }
        }
        if (stickPointer == pointerId) {
            releaseStick();
        }
    }

    private void releaseAll() {
        for (Btn b : gameButtons) {
            if (b.down()) {
                b.pointerId = -1;
                keyUp(b.keycode);
            }
        }
        releaseStick();
    }

    private void setHidden(boolean value) {
        hidden = value;
        if (hidden) {
            releaseAll();
        }
        try {
            SharedPreferences.Editor ed = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            ed.putBoolean(PREF_HIDDEN, hidden);
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                boolean claimed = claim(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                if (!claimed && action == MotionEvent.ACTION_DOWN) {
                    // Not on a control: let the game's own menu receive this touch.
                    return false;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (stickPointer != -1) {
                    int i = e.findPointerIndex(stickPointer);
                    if (i >= 0) {
                        updateStick(e.getX(i), e.getY(i));
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                releasePointer(e.getPointerId(idx));
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachTries = 0;
        post(attachTask);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(attachTask);
        releaseAll();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- drawing

    private void drawBtn(Canvas c, Btn b, String label, boolean pressed) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(b.color);
        fill.setAlpha(pressed ? 190 : 80);
        c.drawCircle(b.cx, b.cy, b.r, fill);
        stroke.setColor(b.color);
        stroke.setAlpha(pressed ? 255 : 150);
        c.drawCircle(b.cx, b.cy, b.r, stroke);

        text.setColor(0xFFFFFFFF);
        text.setAlpha(pressed ? 255 : 200);
        float size = (label.length() > 2 ? 10f : 22f) * density;
        text.setTextSize(size);
        float baseline = b.cy - (text.ascent() + text.descent()) / 2f;
        c.drawText(label, b.cx, baseline, text);
    }

    @Override
    protected void onDraw(Canvas c) {
        drawBtn(c, btnToggle, hidden ? "MOSTRAR" : "OCULTAR", false);
        if (hidden) {
            return;
        }

        // stick base + thumb
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(40);
        c.drawCircle(stickCx, stickCy, stickR, fill);
        stroke.setColor(0xFFFFFFFF);
        stroke.setAlpha(120);
        c.drawCircle(stickCx, stickCy, stickR, stroke);
        fill.setAlpha(stickPointer != -1 ? 190 : 110);
        c.drawCircle(thumbX, thumbY, 30 * density, fill);

        for (Btn b : gameButtons) {
            drawBtn(c, b, b.label, b.down());
        }
    }
}
