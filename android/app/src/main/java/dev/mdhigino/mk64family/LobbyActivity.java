package dev.mdhigino.mk64family;

import java.io.File;
import java.io.FileWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First screen. Chooses between playing alone, hosting a Wi-Fi room, or joining one, writes
 * netplay.cfg next to the game files, then starts the game (MainActivity, its own process).
 * The game reads netplay.cfg when it starts (see src/port/Netplay.h).
 */
public class LobbyActivity extends Activity {
    private static final int PORT = 27015;
    private int players = 2;
    private TextView playersLabel;
    private EditText ipField;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences prefs = getSharedPreferences("lobby", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.setBackgroundColor(Color.rgb(20, 22, 30));

        root.addView(text("MK64 Família", 26, true));
        root.addView(text("Todos os celulares na mesma rede Wi-Fi.", 14, false));

        root.addView(button("Jogar sozinho", v -> launch("off", 0, "")));

        root.addView(text("\nCRIAR SALA (celular do anfitrião)", 16, true));
        root.addView(text("Seu endereço: " + localIp(), 15, false));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(button("–", v -> setPlayers(players - 1)));
        playersLabel = text("", 18, true);
        playersLabel.setPadding(dp(16), 0, dp(16), 0);
        row.addView(playersLabel);
        row.addView(button("+", v -> setPlayers(players + 1)));
        root.addView(row);
        setPlayers(prefs.getInt("players", 2));
        root.addView(button("Criar sala", v -> {
            prefs.edit().putInt("players", players).apply();
            launch("host", players, "");
        }));

        root.addView(text("\nENTRAR EM SALA", 16, true));
        root.addView(text("Digite o endereço que aparece no celular do anfitrião:", 14, false));
        ipField = new EditText(this);
        ipField.setHint("ex.: 192.168.0.10");
        ipField.setInputType(InputType.TYPE_CLASS_PHONE);
        ipField.setTextColor(Color.WHITE);
        ipField.setHintTextColor(Color.GRAY);
        ipField.setText(prefs.getString("ip", ""));
        root.addView(ipField);
        root.addView(button("Entrar", v -> {
            String ip = ipField.getText().toString().trim();
            if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                ipField.setError("Endereço inválido");
                return;
            }
            prefs.edit().putString("ip", ip).apply();
            launch("client", 0, ip);
        }));

        root.addView(text("\nDica: o anfitrião deve tocar em \"Criar sala\" PRIMEIRO. "
                + "Depois os outros entram. O jogo começa quando todos entrarem.", 13, false));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void setPlayers(int n) {
        players = Math.max(2, Math.min(4, n));
        playersLabel.setText(players + " jogadores");
    }

    private void launch(String mode, int n, String host) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                try (FileWriter w = new FileWriter(new File(dir, "netplay.cfg"))) {
                    w.write("mode=" + mode + "\n");
                    w.write("players=" + n + "\n");
                    w.write("host=" + host + "\n");
                    w.write("port=" + PORT + "\n");
                    w.write("delay=3\n");
                }
            }
        } catch (Throwable t) {
            // Without the file the game just starts in single-device mode.
        }
        startActivity(new Intent(this, MainActivity.class));
        finish();
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

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setOnClickListener(l);
        return bt;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
