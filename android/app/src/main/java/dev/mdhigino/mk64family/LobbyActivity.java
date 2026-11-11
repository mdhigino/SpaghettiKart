package dev.mdhigino.mk64family;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Random;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import static dev.mdhigino.mk64family.RetroUi.*;

/**
 * First screen of the app, in the arcade style of the design preview: main menu, create a room,
 * join a room, options. It writes netplay.cfg next to the game files and starts the game
 * (MainActivity, its own process). The game reads that file when it starts (src/port/Netplay.h).
 */
public class LobbyActivity extends Activity {
    private static final int PORT = 27015;

    private SharedPreferences prefs;
    private FrameLayout root;
    private String current = "menu";

    // create-room state
    private int players = 2;
    private boolean fullScreen = true;
    private boolean battleAi = false;
    // join-screen state
    private boolean joinOnline = true;
    private final StringBuilder code = new StringBuilder();
    private EditText ipField;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RetroUi.init(this);
        prefs = getSharedPreferences("lobby", MODE_PRIVATE);
        players = Math.max(2, Math.min(4, prefs.getInt("players", 2)));
        fullScreen = prefs.getBoolean("fullscreen", true);
        battleAi = prefs.getBoolean("battleAi", false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        root = new FrameLayout(this);
        root.setBackground(new Backdrop());
        setContentView(root);
        showMenu();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onBackPressed() {
        if ("menu".equals(current)) {
            super.onBackPressed();
        } else {
            showMenu();
        }
    }

    // ------------------------------------------------------------------ layout helpers

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout rowOf() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
    }

    private LinearLayout.LayoutParams weightWrap(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private LinearLayout.LayoutParams gap(int l, int t, int r, int b, boolean fill) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                fill ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, fill ? 1f : 0f);
        p.setMargins(l, t, r, b);
        return p;
    }

    private TextView label(String s) {
        TextView t = text(this, s, 1.0f, MUTED, true);
        t.setPadding(0, px(0.6f), 0, px(0.4f));
        return t;
    }

    /** Page frame: logo + subtitle on the left, optional widget on the right, body, status bar. */
    private void page(String id, String title, String subtitle, View topRight, View body, String hint) {
        current = id;
        root.removeAllViews();

        LinearLayout content = column();
        int side = Math.max(px(3f), px(2f));
        content.setPadding(side, px(4.2f), side, px(1.6f));

        LinearLayout top = rowOf();
        top.setGravity(Gravity.BOTTOM);
        LinearLayout logoBox = column();
        TextView logo = text(this, title, 3.1f, YELLOW, true);
        logo.setShadowLayer(px(0.05f) + 0.01f, px(0.5f), px(0.5f), RED);
        logoBox.addView(logo);
        TextView sub = text(this, subtitle, 1.15f, PAPER, true);
        sub.setPadding(0, px(1f), 0, 0);
        logoBox.addView(sub);
        top.addView(logoBox, weightWrap(1f));
        if (topRight != null) {
            top.addView(topRight);
        }
        content.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(body, gap(0, px(1.4f), 0, px(1.2f), true));

        // status bar
        LinearLayout bar = rowOf();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, px(1.1f), 0, 0);
        boolean wifi = hasWifi();
        bar.addView(dot(wifi ? LCD : YELLOW));
        TextView w = text(this, wifi ? "Wi-Fi conectado" : "Sem Wi-Fi", 1.9f, MUTED, false);
        w.setPadding(px(0.8f), 0, px(2f), 0);
        bar.addView(w);
        String relay = prefs.getString("relay", "");
        TextView sv = text(this, relay.isEmpty() ? "Servidor: não configurado" : "Servidor: " + relay, 1.9f,
                MUTED, false);
        bar.addView(sv);
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(text(this, hint, 1.9f, PAPER, false));
        FrameLayout barBox = new FrameLayout(this);
        View line = new View(this);
        line.setBackgroundColor(EDGE);
        barBox.addView(line, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(0.3f)));
        barBox.addView(bar);
        content.addView(barBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View dot(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(1.1f), px(1.1f)));
        return v;
    }

    private View chipTag(String s) {
        TextView t = text(this, s, 1.0f, 0xFFFFFFFF, true);
        t.setPadding(px(0.7f), px(0.5f), px(0.7f), px(0.5f));
        t.setBackgroundColor(0x73000000);
        return t;
    }

    private FrameLayout withTag(View button, String tag, boolean darkTag) {
        FrameLayout f = new FrameLayout(this);
        f.addView(button, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView t = (TextView) chipTag(tag);
        if (darkTag) {
            t.setTextColor(YELLOW);
            t.setBackgroundColor(0xBF000000);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(0, px(1.3f), px(1.5f), 0);
        f.addView(t, lp);
        return f;
    }

    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setTypeface(body);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 2.5f * U);
        e.setTextColor(PAPER);
        e.setHintTextColor(MUTED);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        e.setPadding(px(1.3f), px(1.1f), px(1.3f), px(1.1f));
        e.setBackground(new Panel(0xFF000000, EDGE, false));
        return e;
    }

    // ------------------------------------------------------------------ screens

    private void showMenu() {
        LinearLayout grid = column();
        grid.setGravity(Gravity.CENTER_VERTICAL);
        grid.setPadding(px(2.6f), 0, 0, 0);

        LinearLayout r1 = rowOf();
        LinearLayout r2 = rowOf();

        View solo = bigButton(this, RED, "JOGAR SOZINHO", "Corrida e Battle neste celular", false);
        solo.setOnClickListener(v -> launchSolo());
        View wifi = bigButton(this, YELLOW, "SALA NO WI-FI", "Mesma rede da sua casa", true);
        wifi.setOnClickListener(v -> showCreate(false));
        View net = bigButton(this, BLUE, "SALA PELA INTERNET", "Servidor da família", false);
        net.setOnClickListener(v -> showCreate(true));
        View opt = bigButton(this, GREEN, "OPÇÕES", "Servidor, tela cheia, controles", false);
        opt.setOnClickListener(v -> showOptions());

        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a.setMargins(0, 0, px(3.4f), px(2.4f));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setMargins(0, 0, 0, px(2.4f));
        r1.addView(withTag(solo, "1P", false), a);
        r1.addView(withTag(wifi, "WI-FI", true), b);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a2.setMargins(0, 0, px(3.4f), 0);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r2.addView(withTag(net, "ONLINE", false), a2);
        r2.addView(withTag(opt, "AJUSTES", false), b2);
        grid.addView(r1);
        grid.addView(r2);

        TextView ver = text(this, "4 JOGADORES", 1.0f, PAPER, true);
        ver.setPadding(px(1.2f), px(0.8f), px(1.2f), px(0.8f));
        ver.setBackground(new Panel(0xFF000000, EDGE, false));
        page("menu", "MK64 FAMÍLIA", "CORRIDA E BATTLE EM CASA", ver, grid, "Toque em uma opção para começar");
    }

    private void showCreate(final boolean online) {
        LinearLayout cols = rowOf();

        // ---- left: rules
        LinearLayout left = column();
        left.setPadding(px(1.7f), px(1.3f), px(1.7f), px(1.3f));
        panel(left, false);

        left.addView(label("REGRA DO BATTLE"));
        LinearLayout rules = rowOf();
        final TextView r1 = chip(this, "CADA UM POR SI", !battleAi);
        TextView r2 = chip(this, "EM TIMES", false);
        final TextView r3 = chip(this, "COM IA", battleAi);
        r2.setAlpha(0.45f); // not implemented in the game yet
        r2.setClickable(false);
        r2.setText("EM TIMES (em breve)");
        r1.setOnClickListener(v -> setAi(false, online));
        r3.setOnClickListener(v -> setAi(true, online));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, px(1.2f), 0);
        rules.addView(r1, cp);
        rules.addView(r2, cp);
        rules.addView(r3, cp);
        left.addView(rules);

        left.addView(label("JOGADORES"));
        LinearLayout nums = rowOf();
        final TextView[] n = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int count = i + 2;
            n[i] = chip(this, String.valueOf(count), players == count);
            n[i].setMinWidth(px(4.2f));
            n[i].setOnClickListener(v -> {
                players = count;
                for (int k = 0; k < 3; k++) {
                    setChipSelected(n[k], k + 2 == players);
                }
                prefs.edit().putInt("players", players).apply();
                showCreate(online);
            });
            nums.addView(n[i], cp);
        }
        left.addView(nums);

        left.addView(label("TELA CHEIA POR JOGADOR"));
        final TextView fs = chip(this, fullScreen ? "LIGADA" : "DESLIGADA", fullScreen);
        fs.setOnClickListener(v -> {
            fullScreen = !fullScreen;
            prefs.edit().putBoolean("fullscreen", fullScreen).apply();
            fs.setText(fullScreen ? "LIGADA" : "DESLIGADA");
            setChipSelected(fs, fullScreen);
        });
        LinearLayout fsRow = rowOf();
        fsRow.addView(fs, cp);
        left.addView(fsRow);
        cols.addView(left, weight(1.55f));

        // ---- right: summary + create
        LinearLayout right = column();
        right.setPadding(px(1.7f), px(1.3f), px(1.7f), px(1.3f));
        panel(right, false);
        right.addView(label("SUA SALA"));
        right.addView(summary(online ? "Internet" : "Wi-Fi local", false));
        right.addView(summary(battleAi ? "BATTLE: COM IA" : "BATTLE: CADA UM POR SI", true));
        if (battleAi) {
            right.addView(summary("IA completa at\u00E9 4 karts", false));
        }
        right.addView(summary(players + " jogadores", false));
        View spacer = new View(this);
        right.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        View go = bigButton(this, GREEN, "CRIAR SALA ▶", null, false);
        ((TextView) ((LinearLayout) go).getChildAt(0)).setGravity(Gravity.CENTER);
        go.setOnClickListener(v -> createRoom(online));
        right.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rp = weight(1f);
        rp.setMargins(px(3f), 0, 0, 0);
        cols.addView(right, rp);

        View steps = steps(1);
        page("create", "CRIAR SALA", online ? "SALA PELA INTERNET" : "SALA NO WI-FI", steps, cols,
                "Os outros celulares seguem a sua escolha");
    }

    private void setAi(boolean on, boolean online) {
        battleAi = on;
        prefs.edit().putBoolean("battleAi", on).apply();
        showCreate(online);
    }

    private TextView summary(String s, boolean accent) {
        TextView t = text(this, s, 2.3f, accent ? YELLOW : PAPER, false);
        t.setPadding(0, 0, 0, px(0.6f));
        return t;
    }

    private View steps(int on) {
        LinearLayout l = rowOf();
        for (int i = 1; i <= 2; i++) {
            View v = new View(this);
            v.setBackgroundColor(i <= on ? YELLOW : EDGE);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(3f), px(0.9f));
            p.setMargins(px(0.3f), 0, px(0.3f), 0);
            l.addView(v, p);
        }
        return l;
    }

    private void showJoin() {
        LinearLayout cols = rowOf();

        LinearLayout left = column();
        left.setPadding(px(1.7f), px(1.3f), px(1.7f), px(1.3f));
        panel(left, false);

        left.addView(label("ONDE JOGAR"));
        LinearLayout where = rowOf();
        TextView wWifi = chip(this, "WI-FI DA CASA", !joinOnline);
        TextView wNet = chip(this, "INTERNET", joinOnline);
        wWifi.setOnClickListener(v -> { joinOnline = false; showJoin(); });
        wNet.setOnClickListener(v -> { joinOnline = true; showJoin(); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, px(1.2f), 0);
        where.addView(wWifi, cp);
        where.addView(wNet, cp);
        left.addView(where);

        if (joinOnline) {
            left.addView(label("CÓDIGO DA SALA"));
            LinearLayout cells = rowOf();
            for (int i = 0; i < 6; i++) {
                TextView c = text(this, i < code.length() ? String.valueOf(code.charAt(i)) : "_", 3.4f,
                        i == code.length() ? YELLOW : LCD, true);
                c.setGravity(Gravity.CENTER);
                c.setPadding(0, px(1.2f), 0, px(1.2f));
                Lcd lcd = new Lcd();
                if (i == code.length()) {
                    lcd.inner = YELLOW;
                }
                c.setBackground(lcd);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                p.setMargins(0, 0, i < 5 ? px(1f) : 0, 0);
                cells.addView(c, p);
            }
            left.addView(cells);
            String relay = prefs.getString("relay", "");
            TextView sv = text(this, relay.isEmpty() ? "Configure o servidor em OPÇÕES" : "Servidor: " + relay,
                    1.9f, relay.isEmpty() ? YELLOW : MUTED, false);
            sv.setPadding(0, px(0.8f), 0, 0);
            left.addView(sv);
        } else {
            left.addView(label("ENDEREÇO DO ANFITRIÃO"));
            ipField = field("ex.: 192.168.0.10", prefs.getString("ip", ""), InputType.TYPE_CLASS_PHONE);
            left.addView(ipField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView tip = text(this, "O anfitrião vê esse endereço na tela da sala.", 1.9f, MUTED, false);
            tip.setPadding(0, px(0.8f), 0, 0);
            left.addView(tip);
        }
        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        View go = bigButton(this, YELLOW, "ENTRAR ▶", null, true);
        ((TextView) ((LinearLayout) go).getChildAt(0)).setGravity(Gravity.CENTER);
        go.setOnClickListener(v -> joinRoom());
        left.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cols.addView(left, weight(1.4f));

        LinearLayout right = column();
        right.setPadding(px(1.7f), px(1.3f), px(1.7f), px(1.3f));
        panel(right, false);
        if (joinOnline) {
            right.addView(label("TECLADO"));
            String[] keys = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "APAGAR", "0", "LIMPAR" };
            for (int r = 0; r < 4; r++) {
                LinearLayout row = rowOf();
                for (int c = 0; c < 3; c++) {
                    final String k = keys[r * 3 + c];
                    TextView key = chip(this, k, false);
                    key.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (k.length() > 1 ? 1.1f : 1.6f) * U);
                    key.setOnClickListener(v -> pressKey(k));
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    p.setMargins(c > 0 ? px(0.9f) : 0, 0, 0, px(0.9f));
                    row.addView(key, p);
                }
                right.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
        } else {
            right.addView(label("COMO FUNCIONA"));
            right.addView(summary("1. Todos no mesmo Wi-Fi.", false));
            right.addView(summary("2. O anfitrião cria a sala.", false));
            right.addView(summary("3. Digite o endereço dele aqui.", false));
        }
        LinearLayout.LayoutParams rp = weight(1f);
        rp.setMargins(px(3f), 0, 0, 0);
        cols.addView(right, rp);

        TextView chip = text(this, joinOnline ? "ONLINE" : "WI-FI", 1.0f, PAPER, true);
        chip.setPadding(px(1.2f), px(0.8f), px(1.2f), px(0.8f));
        chip.setBackground(new Panel(0xFF000000, EDGE, false));
        page("join", "ENTRAR", joinOnline ? "DIGITE O CÓDIGO DA SALA" : "DIGITE O ENDEREÇO DO ANFITRIÃO", chip,
                cols, "Peça o código ao anfitrião");
    }

    private void pressKey(String k) {
        if ("APAGAR".equals(k)) {
            if (code.length() > 0) code.deleteCharAt(code.length() - 1);
        } else if ("LIMPAR".equals(k)) {
            code.setLength(0);
        } else if (code.length() < 6) {
            code.append(k);
        }
        showJoin();
    }

    private void showOptions() {
        LinearLayout cols = rowOf();
        LinearLayout left = column();
        left.setPadding(px(1.7f), px(1.3f), px(1.7f), px(1.3f));
        panel(left, false);
        left.addView(label("SERVIDOR DA FAMÍLIA (endereço:porta)"));
        final EditText relay = field("ex.: 31.97.173.166:27016", prefs.getString("relay", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        left.addView(relay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView tip = text(this, "Usado em \"Sala pela internet\". Fica salvo neste celular.", 1.9f, MUTED, false);
        tip.setPadding(0, px(0.8f), 0, px(0.6f));
        left.addView(tip);

        left.addView(label("TELA CHEIA POR JOGADOR"));
        final TextView fs = chip(this, fullScreen ? "LIGADA" : "DESLIGADA", fullScreen);
        fs.setOnClickListener(v -> {
            fullScreen = !fullScreen;
            fs.setText(fullScreen ? "LIGADA" : "DESLIGADA");
            setChipSelected(fs, fullScreen);
        });
        LinearLayout fsRow = rowOf();
        fsRow.addView(fs);
        left.addView(fsRow);
        left.addView(label("BATTLE COM IA (completa at\u00E9 4 karts)"));
        final TextView ai = chip(this, battleAi ? "LIGADO" : "DESLIGADO", battleAi);
        ai.setOnClickListener(v -> {
            battleAi = !battleAi;
            ai.setText(battleAi ? "LIGADO" : "DESLIGADO");
            setChipSelected(ai, battleAi);
        });
        LinearLayout aiRow = rowOf();
        aiRow.addView(ai);
        left.addView(aiRow);
        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        View save = bigButton(this, GREEN, "SALVAR E VOLTAR", null, false);
        ((TextView) ((LinearLayout) save).getChildAt(0)).setGravity(Gravity.CENTER);
        save.setOnClickListener(v -> {
            String r = relay.getText().toString().trim();
            if (!r.isEmpty() && !r.matches(".+:\\d{2,5}")) {
                relay.setError("Use endereço:porta");
                return;
            }
            prefs.edit().putString("relay", r).putBoolean("fullscreen", fullScreen).putBoolean("battleAi", battleAi)
                    .apply();
            showMenu();
        });
        left.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cols.addView(left, weight(1.4f));

        LinearLayout right = column();
        right.setPadding(px(1.7f), px(1.3f), px(1.7f), px(1.3f));
        panel(right, false);
        right.addView(label("ARQUIVO DO JOGO (mk64.o2r)"));
        right.addView(summary(gameFileStatus(), false));
        TextView imp = chip(this, "IMPORTAR mk64.o2r", false);
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, 77);
        });
        LinearLayout impRow = rowOf();
        impRow.addView(imp);
        right.addView(impRow);
        right.addView(label("SEU ENDEREÇO NO WI-FI"));
        right.addView(summary(localIp(), false));
        right.addView(label("SOBRE"));
        right.addView(summary("Cada celular mostra só o seu piloto.", false));
        right.addView(summary("Os controles na tela ficam no jogo.", false));
        LinearLayout.LayoutParams rp = weight(1f);
        rp.setMargins(px(3f), 0, 0, 0);
        cols.addView(right, rp);

        page("options", "OPÇÕES", "AJUSTES DO APP", null, cols, "Toque em salvar para guardar");
    }

    // ------------------------------------------------------------------ game file

    private String gameFileStatus() {
        File d = getExternalFilesDir(null);
        File f = d == null ? null : new File(d, "mk64.o2r");
        if (f != null && f.isFile() && f.length() > 1000000L) {
            return "OK (" + (f.length() / 1048576L) + " MB)";
        }
        return "Não encontrado";
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 77 || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            File d = getExternalFilesDir(null);
            File tmp = new File(d, "mk64.o2r.tmp");
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            File dst = new File(d, "mk64.o2r");
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
            Toast.makeText(this, "Arquivo do jogo importado!", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Não consegui importar o arquivo.", Toast.LENGTH_LONG).show();
        }
        showOptions();
    }

    // ------------------------------------------------------------------ actions

    private void launchSolo() {
        writeConfig("off", 0, "", "", 0, 3, "", "");
        startGame();
    }

    private void createRoom(boolean online) {
        if (online) {
            String relay = prefs.getString("relay", "");
            if (relay.isEmpty()) {
                Toast.makeText(this, "Configure o endereço do servidor em OPÇÕES.", Toast.LENGTH_LONG).show();
                showOptions();
                return;
            }
            int room = 100000 + new Random().nextInt(900000);
            writeConfig("host", players, "", relay, room, 6, String.valueOf(room), "");
        } else {
            writeConfig("host", players, "", "", 0, 3, "", localIp());
        }
        startGame();
    }

    private void joinRoom() {
        if (joinOnline) {
            String relay = prefs.getString("relay", "");
            if (relay.isEmpty()) {
                Toast.makeText(this, "Configure o endereço do servidor em OPÇÕES.", Toast.LENGTH_LONG).show();
                showOptions();
                return;
            }
            if (code.length() != 6) {
                Toast.makeText(this, "O código tem 6 números.", Toast.LENGTH_SHORT).show();
                return;
            }
            writeConfig("client", 0, "", relay, Integer.parseInt(code.toString()), 6, code.toString(), "");
        } else {
            String ip = ipField == null ? "" : ipField.getText().toString().trim();
            if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                if (ipField != null) ipField.setError("Endereço inválido");
                return;
            }
            prefs.edit().putString("ip", ip).apply();
            writeConfig("client", 0, ip, "", 0, 3, "", ip);
        }
        startGame();
    }

    private void writeConfig(String mode, int n, String host, String relay, int room, int delay, String uiCode,
                             String uiIp) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                try (FileWriter w = new FileWriter(new File(dir, "netplay.cfg"))) {
                    w.write("mode=" + mode + "\n");
                    w.write("players=" + n + "\n");
                    w.write("host=" + host + "\n");
                    w.write("port=" + PORT + "\n");
                    w.write("delay=" + delay + "\n");
                    w.write("fullscreen=" + (fullScreen ? 1 : 0) + "\n");
                    w.write("battle=" + (battleAi ? "ai" : "normal") + "\n");
                    if (!relay.isEmpty()) {
                        w.write("relay=" + relay + "\n");
                        w.write("room=" + room + "\n");
                    }
                    // Only used by the waiting-room screen shown over the game.
                    w.write("ui_code=" + uiCode + "\n");
                    w.write("ui_ip=" + uiIp + "\n");
                }
            }
        } catch (Throwable t) {
            // Without the file the game just starts in single-device mode.
        }
    }

    private void startGame() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ------------------------------------------------------------------ info

    private boolean hasWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) {
            return false;
        }
    }

    private String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "(conecte-se ao Wi-Fi)";
    }
}
