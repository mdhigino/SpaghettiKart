package dev.mdhigino.mk64family;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import static dev.mdhigino.mk64family.RetroUi.*;

/**
 * Waiting room drawn over the game while it waits for the other phones (the game thread blocks in
 * Netplay_Start). The game writes netplay.status ("state=waiting|started|failed", joined, players);
 * this view polls it and disappears once the race can begin.
 */
final class WaitingOverlay extends FrameLayout {
    private final Activity activity;
    private final File dir;
    private final Map<String, String> cfg;
    private final boolean host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout slots;
    private final TextView headline;
    private final long startedAt = System.currentTimeMillis();
    private boolean gone;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (gone) return;
            Map<String, String> st = readKv(new File(dir, "netplay.status"));
            String state = st.get("state");
            int joined = parse(st.get("joined"), host ? 1 : 0);
            int players = parse(st.get("players"), parse(cfg.get("players"), 2));
            if ("started".equals(state)) {
                dismiss();
                return;
            }
            if ("failed".equals(state)) {
                showFailed();
                return;
            }
            refresh(joined, players);
            handler.postDelayed(this, 500);
        }
    };

    WaitingOverlay(Activity a, File dir, Map<String, String> cfg) {
        super(a);
        this.activity = a;
        this.dir = dir;
        this.cfg = cfg;
        this.host = "host".equals(cfg.get("mode"));
        RetroUi.init(a);
        setBackground(new Backdrop());
        setClickable(true); // swallow touches so the game controls below stay quiet

        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(3f), px(4.2f), px(3f), px(2f));

        TextView logo = text(a, "SALA DE ESPERA", 2.8f, YELLOW, true);
        logo.setShadowLayer(1f, px(0.5f), px(0.5f), RED);
        content.addView(logo);
        headline = text(a, host ? "ESPERANDO OS OUTROS CELULARES" : "PROCURANDO A SALA...", 1.15f, PAPER, true);
        headline.setPadding(0, px(1f), 0, px(1.6f));
        content.addView(headline);

        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        // left: code + copy buttons (host) or info (joiner)
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(px(1.7f), px(1.3f), px(1.7f), px(1.3f));
        panel(left, false);

        String code = nz(cfg.get("ui_code"));
        String ip = nz(cfg.get("ui_ip"));
        if (host) {
            boolean online = !code.isEmpty();
            TextView lab = text(a, online ? "CÓDIGO DA SALA" : "ENDEREÇO PARA OS OUTROS", 1.0f, MUTED, true);
            left.addView(lab);
            TextView big = text(a, online ? code : ip, online ? 6f : 3.6f, LCD, true);
            big.setGravity(Gravity.CENTER);
            big.setPadding(px(1.5f), px(2f), px(1.5f), px(2f));
            big.setBackground(new Lcd());
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.setMargins(0, px(0.8f), 0, px(1.4f));
            left.addView(big, bp);

            final String value = online ? code : ip;
            final String invite = buildInvite(online, code, ip);
            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            View c1 = bigButton(a, YELLOW, online ? "COPIAR CÓDIGO" : "COPIAR ENDEREÇO", null, true);
            c1.setOnClickListener(v -> copy(value, online ? "Código copiado!" : "Endereço copiado!"));
            View c2 = bigButton(a, BLUE, "COPIAR CONVITE", null, false);
            c2.setOnClickListener(v -> copy(invite, "Convite copiado! Cole no WhatsApp."));
            LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p1.setMargins(0, 0, px(1.2f), 0);
            btns.addView(c1, p1);
            btns.addView(c2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            left.addView(btns);
        } else {
            left.addView(text(a, "ENTRANDO NA SALA", 1.0f, MUTED, true));
            TextView t = text(a, code.isEmpty() ? ip : "Código " + code, 3f, LCD, false);
            t.setPadding(0, px(1.2f), 0, px(1.2f));
            left.addView(t);
            TextView tip = text(a, "O jogo começa quando o anfitrião estiver pronto.", 2.1f, PAPER, false);
            left.addView(tip);
        }
        cols.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        // right: player slots
        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(1.7f), px(1.3f), px(1.7f), px(1.3f));
        panel(right, false);
        right.addView(text(a, "JOGADORES", 1.0f, MUTED, true));
        slots = new LinearLayout(a);
        slots.setOrientation(LinearLayout.VERTICAL);
        slots.setPadding(0, px(1f), 0, 0);
        right.addView(slots);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.setMargins(px(3f), 0, 0, 0);
        cols.addView(right, rp);
        content.addView(cols);

        addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // back button
        TextView back = chip(a, "◀ CANCELAR", false);
        back.setOnClickListener(v -> cancel());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        bl.setMargins(px(3f), 0, 0, px(2f));
        addView(back, bl);

        refresh(host ? 1 : 0, parse(cfg.get("players"), 2));
        handler.postDelayed(poll, 400);
    }

    private String buildInvite(boolean online, String code, String ip) {
        if (online) {
            String relay = nz(cfg.get("relay"));
            return "Bora jogar Mario Kart em família! Abra o app MK64 Família > Sala pela internet > Entrar, "
                    + "e digite o código: " + code
                    + (relay.isEmpty() ? "" : " (servidor " + relay + ")");
        }
        return "Bora jogar Mario Kart em família! Conecte no mesmo Wi-Fi que eu, abra o app MK64 Família > "
                + "Sala no Wi-Fi > Entrar, e digite o endereço: " + ip;
    }

    private void copy(String value, String toast) {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("MK64", value));
            Toast.makeText(activity, toast, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(activity, "Não consegui copiar.", Toast.LENGTH_SHORT).show();
        }
    }

    private void refresh(int joined, int players) {
        if (host) {
            headline.setText("ESPERANDO OS OUTROS CELULARES (" + Math.min(joined, players) + "/" + players + ")");
        } else {
            long s = (System.currentTimeMillis() - startedAt) / 1000;
            headline.setText("PROCURANDO A SALA... " + s + "s");
        }
        slots.removeAllViews();
        int n = host ? players : Math.max(players, 0);
        if (!host && n == 0) {
            TextView t = text(activity, "Aguardando o anfitrião", 2.2f, MUTED, false);
            slots.addView(t);
            return;
        }
        for (int i = 0; i < n; i++) {
            boolean on = i < joined;
            TextView t = text(activity, (i == 0 ? "P1 ANFITRIÃO" : "P" + (i + 1) + (on ? " CONECTADO" : " esperando...")),
                    2.2f, on ? LCD : MUTED, false);
            t.setPadding(0, 0, 0, px(0.7f));
            slots.addView(t);
        }
    }

    private void showFailed() {
        headline.setText(host ? "NINGUÉM ENTROU NA SALA" : "NÃO ACHEI A SALA");
        headline.setTextColor(YELLOW);
        slots.removeAllViews();
        slots.addView(text(activity, "Voltando ao menu...", 2.2f, PAPER, false));
        handler.postDelayed(this::cancel, 2500);
    }

    private void dismiss() {
        gone = true;
        handler.removeCallbacksAndMessages(null);
        setVisibility(View.GONE);
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
    }

    /** Leaves the waiting room: back to the lobby (the game process ends with this activity). */
    private void cancel() {
        gone = true;
        handler.removeCallbacksAndMessages(null);
        try {
            activity.startActivity(new android.content.Intent(activity, LobbyActivity.class));
        } catch (Throwable ignored) {
        }
        activity.finish();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }

    // ---- helpers ----
    static Map<String, String> readKv(File f) {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf('=');
                if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
