package dev.mdhigino.mk64family;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared look of the menu screens: arcade-style (flat primary colours, hard pixel bevels, night
 * track background). Sizes are expressed in "U" = 1% of the screen width, like the design preview,
 * so the layout scales with the phone.
 */
final class RetroUi {
    static final int NIGHT = 0xFF0A0E2E, NIGHT2 = 0xFF151B4D, RED = 0xFFE4002B, YELLOW = 0xFFFFD400,
            BLUE = 0xFF0A5BD6, GREEN = 0xFF00A651, INK = 0xFF05060F, PAPER = 0xFFF4F1E6, MUTED = 0xFF8D95C9,
            EDGE = 0xFF2B3380, LCD = 0xFF7DFF9B, LCD_BG = 0xFF021A0B, CHIP_BG = 0xFF0D1240, OFF = 0xFF3A3F73;

    static float U = 10f;          // pixels in 1% of the screen width
    static Typeface display = Typeface.MONOSPACE;
    static Typeface body = Typeface.MONOSPACE;

    static void init(Context c) {
        U = c.getResources().getDisplayMetrics().widthPixels / 100f;
        // Fonts are downloaded by the CI build; without them a system monospace font is used.
        try {
            display = Typeface.createFromAsset(c.getAssets(), "fonts/PressStart2P-Regular.ttf");
        } catch (Throwable t) {
            display = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        }
        try {
            body = Typeface.createFromAsset(c.getAssets(), "fonts/VT323-Regular.ttf");
        } catch (Throwable t) {
            body = Typeface.MONOSPACE;
        }
    }

    static int px(float u) {
        return Math.round(u * U);
    }

    static TextView text(Context c, String s, float sizeU, int color, boolean displayFont) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(displayFont ? display : body);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeU * U);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ---------- backgrounds ----------

    /** Night sky, track lines and the checkered band on top. */
    static final class Backdrop extends Drawable {
        private final Paint p = new Paint();

        @Override
        public void draw(Canvas cv) {
            int w = getBounds().width(), h = getBounds().height();
            p.setStyle(Paint.Style.FILL);
            p.setColor(NIGHT);
            cv.drawRect(0, 0, w, h * 0.62f, p);
            p.setColor(NIGHT2);
            cv.drawRect(0, h * 0.62f, w, h, p);
            p.setColor(0x12FFFFFF);
            for (float x = 0; x < w; x += 11.6f * U) {
                cv.drawRect(x, h * 0.62f, x + 0.3f * U, h, p);
            }
            for (float y = h * 0.62f; y < h; y += 3f * U) {
                cv.drawRect(0, y, w, y + 0.3f * U, p);
            }
            float sq = 2.6f * U;
            for (int i = 0; i * sq < w; i++) {
                for (int j = 0; j < 2; j++) {
                    p.setColor(((i + j) & 1) == 0 ? 0xE6F4F1E6 : 0xE605060F);
                    cv.drawRect(i * sq, j * sq * 0.5f, (i + 1) * sq, (j + 1) * sq * 0.5f, p);
                }
            }
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** Flat colour block with a hard bevel and a solid drop shadow; sinks when pressed. */
    static final class Bevel extends Drawable {
        int color;
        int depth = px(0.7f);
        boolean pressed;
        private final Paint p = new Paint();

        Bevel(int color) { this.color = color; }

        @Override
        public void draw(Canvas cv) {
            int w = getBounds().width(), h = getBounds().height();
            float b = px(0.4f), bev = px(0.5f);
            float off = pressed ? depth : 0;
            float faceBottom = h - depth + off;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            cv.drawRect(0, off, w, h, p);                         // border + drop shadow
            p.setColor(color);
            cv.drawRect(b, off + b, w - b, faceBottom - b, p);    // face
            p.setColor(0x59FFFFFF);
            cv.drawRect(b, off + b, w - b, off + b + bev, p);     // light top
            cv.drawRect(b, off + b, b + bev, faceBottom - b, p);  // light left
            p.setColor(0x59000000);
            cv.drawRect(b, faceBottom - b - bev, w - b, faceBottom - b, p); // dark bottom
            cv.drawRect(w - b - bev, off + b, w - b, faceBottom - b, p);     // dark right
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Dark panel with a blue-violet outline. */
    static final class Panel extends Drawable {
        private final Paint p = new Paint();
        final int fill, edge;
        final boolean dashed;

        Panel(int fill, int edge, boolean dashed) { this.fill = fill; this.edge = edge; this.dashed = dashed; }

        @Override
        public void draw(Canvas cv) {
            int w = getBounds().width(), h = getBounds().height();
            float b = px(0.4f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(edge);
            if (dashed) {
                float seg = b * 2;
                for (float x = 0; x < w; x += seg * 2) {
                    cv.drawRect(x, 0, Math.min(x + seg, w), b, p);
                    cv.drawRect(x, h - b, Math.min(x + seg, w), h, p);
                }
                for (float y = 0; y < h; y += seg * 2) {
                    cv.drawRect(0, y, b, Math.min(y + seg, h), p);
                    cv.drawRect(w - b, y, w, Math.min(y + seg, h), p);
                }
                p.setColor(fill);
                cv.drawRect(b, b, w - b, h - b, p);
            } else {
                cv.drawRect(0, 0, w, h, p);
                p.setColor(fill);
                cv.drawRect(b, b, w - b, h - b, p);
            }
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Dark green "LCD" box used for room codes. */
    static final class Lcd extends Drawable {
        private final Paint p = new Paint();
        int inner = 0xFF0B6B32;

        @Override
        public void draw(Canvas cv) {
            int w = getBounds().width(), h = getBounds().height();
            float b = px(0.45f), i = px(0.35f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            cv.drawRect(0, 0, w, h, p);
            p.setColor(inner);
            cv.drawRect(b, b, w - b, h - b, p);
            p.setColor(LCD_BG);
            cv.drawRect(b + i, b + i, w - b - i, h - b - i, p);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    // ---------- widgets ----------

    static void panel(View v, boolean dashed) {
        v.setBackground(new Panel(dashed ? 0x00000000 : 0x8C000000, EDGE, dashed));
    }

    /** Big button: coloured block, title (pixel font), subtitle (body font), optional corner tag. */
    static LinearLayout bigButton(Context c, int color, String title, String sub, boolean darkText) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        int pad = px(1.8f);
        b.setPadding(pad, px(1.5f), pad, px(1.5f) + px(0.7f));
        final Bevel bg = new Bevel(color);
        b.setBackground(bg);
        int fg = darkText ? INK : 0xFFFFFFFF;
        TextView t = text(c, title, 1.7f, fg, true);
        t.setLineSpacing(0, 1.25f);
        b.addView(t);
        if (sub != null) {
            TextView s = text(c, sub, 2.1f, fg, false);
            s.setPadding(0, px(0.9f), 0, 0);
            b.addView(s);
        }
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    bg.pressed = true;
                    v.invalidate();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    bg.pressed = false;
                    v.invalidate();
                }
                return false; // let the click listener still fire
            }
        });
        return b;
    }

    /** Small selectable chip (radio-button style). */
    static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 1.15f, PAPER, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(1.4f), px(1.1f), px(1.4f), px(1.1f));
        t.setClickable(true);
        t.setFocusable(true);
        setChipSelected(t, selected);
        return t;
    }

    static void setChipSelected(TextView t, boolean selected) {
        t.setTag(selected);
        if (selected) {
            t.setTextColor(INK);
            t.setBackground(new Panel(YELLOW, 0xFF000000, false));
        } else {
            t.setTextColor(PAPER);
            t.setBackground(new Panel(CHIP_BG, EDGE, false));
        }
    }

    static boolean isChipSelected(View v) {
        return Boolean.TRUE.equals(v.getTag());
    }
}
