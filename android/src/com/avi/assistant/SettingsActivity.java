package com.avi.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengaturan AVI: penyedia AI + API key, pilih model (fetch dari penyedia),
 * nama pemilik (teks bebas), tema cerah/gelap, warna aksen, TTS,
 * asisten perangkat, bersihkan riwayat.
 * Semua perubahan tersimpan seketika — tidak perlu tombol simpan.
 */
public class SettingsActivity extends Activity {

    private RadioGroup rgPenyedia, rgTema, rgAksen;
    private RadioButton rbGemini, rbNvidia, rbOpenrouter, rbCerah, rbGelap;
    private RadioButton rbABiru, rbAHijau, rbAUngu, rbAOranye;
    private EditText etKey, etNamaPemilik;
    private TextView tvModel, tvPetunjukKey, tvRate, tvAsistenStatus;
    private Switch swTts;
    private SeekBar sbRate;
    private Button bModel, bTes, bBersihkan, bAsisten;

    private boolean sedangMengisi = false;   // cegah TextWatcher menimpa nilai
    private boolean sedangUji = false;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        rgPenyedia   = findViewById(R.id.rgPenyedia);
        rgTema       = findViewById(R.id.rgTema);
        rbGemini     = findViewById(R.id.rbGemini);
        rbNvidia     = findViewById(R.id.rbNvidia);
        rbOpenrouter = findViewById(R.id.rbOpenrouter);
        rbCerah      = findViewById(R.id.rbCerah);
        rbGelap      = findViewById(R.id.rbGelap);
        etKey        = findViewById(R.id.etKey);
        etNamaPemilik= findViewById(R.id.etNamaPemilik);
        tvModel      = findViewById(R.id.tvModel);
        tvPetunjukKey= findViewById(R.id.tvPetunjukKey);
        tvRate       = findViewById(R.id.tvRate);
        swTts        = findViewById(R.id.swTts);
        sbRate       = findViewById(R.id.sbRate);
        bModel       = findViewById(R.id.bModel);
        bTes         = findViewById(R.id.bTes);
        bBersihkan   = findViewById(R.id.bBersihkan);
        rgAksen      = findViewById(R.id.rgAksen);
        rbABiru      = findViewById(R.id.rbABiru);
        rbAHijau     = findViewById(R.id.rbAHijau);
        rbAUngu      = findViewById(R.id.rbAUngu);
        rbAOranye    = findViewById(R.id.rbAOranye);
        tvAsistenStatus = findViewById(R.id.tvAsistenStatus);
        bAsisten     = findViewById(R.id.bAsisten);

        sbRate.setMax(100);                    // 50% .. 150% dipetakan dari 0..100
        muatNilai();
        pasangAksi();
    }

    // ================= muat =================

    private void muatNilai() {
        sedangMengisi = true;

        String prov = AviBrain.penyedia(this);
        if (prov.equals("nvidia")) rbNvidia.setChecked(true);
        else if (prov.equals("openrouter")) rbOpenrouter.setChecked(true);
        else rbGemini.setChecked(true);

        etKey.setText(AviBrain.pref(this).getString("apikey." + prov, ""));
        petunjukKey(prov);

        String model = AviBrain.modelAktif(this);
        tvModel.setText(model.isEmpty() ? "Belum dipilih" : "Aktif: " + model);

        String nama = AviBrain.pref(this).getString("nama_pemilik", "");
        etNamaPemilik.setText(nama);

        if (AviBrain.temaGelap(this)) rbGelap.setChecked(true);
        else rbCerah.setChecked(true);

        swTts.setChecked(AviBrain.pref(this).getBoolean("tts_on", false));
        int laju = AviBrain.pref(this).getInt("tts_rate", 100);
        sbRate.setProgress(Math.max(0, Math.min(100, laju - 50)));
        tvRate.setText("Kecepatan suara: " + laju + "%");

        String aksen = AviBrain.pref(this).getString("aksen", "biru");
        switch (aksen) {
            case "hijau":  rbAHijau.setChecked(true); break;
            case "ungu":   rbAUngu.setChecked(true); break;
            case "oranye": rbAOranye.setChecked(true); break;
            default:       rbABiru.setChecked(true);
        }

        sedangMengisi = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        muatStatusAsisten();   // segarkan bila pemilik baru saja mengubah asisten
    }

    private void petunjukKey(String prov) {
        switch (prov) {
            case "nvidia":
                tvPetunjukKey.setText("API key NVIDIA NIM (gratis) — buat di build.nvidia.com");
                break;
            case "openrouter":
                tvPetunjukKey.setText("API key OpenRouter — buat di openrouter.ai/keys");
                break;
            default:
                tvPetunjukKey.setText("API key Gemini (punya kuota gratis) — buat di aistudio.google.com/apikey");
        }
    }

    // ================= aksi =================

    private void pasangAksi() {
        rgPenyedia.setOnCheckedChangeListener((grup, id) -> {
            if (sedangMengisi) return;
            String prov = id == R.id.rbNvidia ? "nvidia"
                    : id == R.id.rbOpenrouter ? "openrouter" : "gemini";
            AviBrain.pref(this).edit().putString("penyedia", prov).apply();
            sedangMengisi = true;
            etKey.setText(AviBrain.pref(this).getString("apikey." + prov, ""));
            petunjukKey(prov);
            String model = AviBrain.modelAktif(this);
            tvModel.setText(model.isEmpty() ? "Belum dipilih" : "Aktif: " + model);
            sedangMengisi = false;
        });

        etKey.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (sedangMengisi) return;
                AviBrain.pref(SettingsActivity.this)
                        .edit()
                        .putString("apikey." + AviBrain.penyedia(SettingsActivity.this),
                                s.toString().trim())
                        .apply();
            }
        });

        etNamaPemilik.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (sedangMengisi) return;
                AviBrain.pref(SettingsActivity.this)
                        .edit()
                        .putString("nama_pemilik", s.toString().trim())
                        .apply();
            }
        });

        bModel.setOnClickListener(v -> dialogPilihModel());

        bTes.setOnClickListener(v -> {
            if (sedangUji) return;
            sedangUji = true;
            bTes.setText("Menguji…");
            AviBrain.ujiKoneksi(this, hasil -> {
                sedangUji = false;
                bTes.setText("Tes koneksi");
                Toast.makeText(this, hasil, Toast.LENGTH_LONG).show();
            });
        });

        rgTema.setOnCheckedChangeListener((grup, id) -> {
            if (sedangMengisi) return;
            boolean gelap = id == R.id.rbGelap;
            AviBrain.pref(this).edit().putString("tema", gelap ? "gelap" : "cerah").apply();
            recreate();     // tema diterapkan lewat attachBaseContext
        });

        swTts.setOnCheckedChangeListener((tombol, nyala) -> {
            if (sedangMengisi) return;
            AviBrain.pref(this).edit().putBoolean("tts_on", nyala).apply();
        });

        sbRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int nilai, boolean dariUser) {
                int laju = 50 + nilai;
                tvRate.setText("Kecepatan suara: " + laju + "%");
                if (!sedangMengisi) {
                    AviBrain.pref(SettingsActivity.this).edit().putInt("tts_rate", laju).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        bBersihkan.setOnClickListener(v -> {
            AviBrain.kosongkanRiwayat(this);
            Toast.makeText(this, "Riwayat chat dibersihkan.", Toast.LENGTH_SHORT).show();
        });

        // ===== aksen warna =====
        rgAksen.setOnCheckedChangeListener((grup, id) -> {
            if (sedangMengisi) return;
            String aksen = id == R.id.rbAHijau ? "hijau"
                    : id == R.id.rbAUngu ? "ungu"
                    : id == R.id.rbAOranye ? "oranye" : "biru";
            AviBrain.pref(this).edit().putString("aksen", aksen).apply();
            Toast.makeText(this, "Aksen “" + aksen + "” dipasang.", Toast.LENGTH_SHORT).show();
        });

        // ===== Asisten perangkat (tahan tombol home ala Google Assistant) =====
        bAsisten.setOnClickListener(v -> bukaPengaturanAsisten());
    }

    // ================= asisten perangkat =================

    private void muatStatusAsisten() {
        String aktif = Settings.Secure.getString(
                getContentResolver(), "voice_interaction_service");
        if (aktif != null && aktif.contains(getPackageName())) {
            tvAsistenStatus.setText("AKTIF — AVI adalah asisten perangkat ini. "
                    + "Tahan tombol home di layar mana pun (navigasi gestur: "
                    + "sapu dari sudut kiri/kanan bawah) untuk memanggil AVI.");
            bAsisten.setText("Buka pengaturan asisten perangkat");
        } else {
            tvAsistenStatus.setText("Belum aktif. Pilih AVI di halaman berikutnya "
                    + "agar bisa dipanggil lewat tahan tombol home — tanpa layanan "
                    + "latar, tanpa boros baterai.");
            bAsisten.setText("Jadikan AVI asisten perangkat");
        }
    }

    private void bukaPengaturanAsisten() {
        try {
            startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception ignored) {}
        }
    }

    // ================= dialog pilih model =================

    private void dialogPilihModel() {
        final String prov = AviBrain.penyedia(this);

        LinearLayout kotak = new LinearLayout(this);
        kotak.setOrientation(LinearLayout.VERTICAL);
        int p = px(14);
        kotak.setPadding(p, p, p, 0);

        final EditText etCari = new EditText(this);
        etCari.setHint("Cari model / ketik manual");
        etCari.setTextSize(14f);
        kotak.addView(etCari);

        final TextView tvInfo = new TextView(this);
        tvInfo.setTextSize(12f);
        tvInfo.setTextColor(getResources().getColor(R.color.avi_teks_samping, getTheme()));
        tvInfo.setText("Memuat daftar model…");
        kotak.addView(tvInfo);

        final ListView daftar = new ListView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(340));
        lp.topMargin = px(8);
        daftar.setLayoutParams(lp);
        kotak.addView(daftar);

        final List<String> semua = new ArrayList<>();
        final List<String> tampil = new ArrayList<>();
        final ArrayAdapter<String> adptr = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, tampil);
        daftar.setAdapter(adptr);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pilih model — " + AviBrain.namaPenyedia(this))
                .setView(bungkusScroll(kotak))
                .setNegativeButton("Tutup", null)
                .create();

        Runnable[] terapkanCarian = new Runnable[1];
        terapkanCarian[0] = () -> {
            String q = etCari.getText().toString().trim().toLowerCase();
            tampil.clear();
            for (String id : semua) {
                if (q.isEmpty() || id.toLowerCase().contains(q)) tampil.add(id);
            }
            adptr.notifyDataSetChanged();
        };

        etCari.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { terapkanCarian[0].run(); }
        });

        daftar.setOnItemClickListener((induk, v, posisi, id) -> {
            String pilih = tampil.get(posisi);
            AviBrain.pref(this).edit().putString("model." + prov, pilih).apply();
            tvModel.setText("Aktif: " + pilih);
            Toast.makeText(this, "Model dipilih: " + pilih, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();

        AviBrain.daftarModel(this, (model, galat) -> {
            if (isFinishing()) return;
            if (galat != null) {
                tvInfo.setText(galat + " — tetap bisa mengetik nama model manual di atas, "
                        + "lalu sentuh \"Pakai sebagai model\".");
                tambahTombolManual(kotak, prov, etCari, dialog);
                return;
            }
            semua.clear();
            semua.addAll(model);
            if ("gemini".equals(prov)) {
                tvInfo.setText("Paling atas: " + AviBrain.MODEL_REKOMENDASI_GEMINI
                        + " (teruji responsnya cepat).");
            } else {
                tvInfo.setText("Sentuh nama model untuk memilihnya.");
            }
            terapkanCarian[0].run();
        });
    }

    private void tambahTombolManual(LinearLayout kotak, String prov,
                                    EditText etCari, AlertDialog dialog) {
        if (kotak.findViewWithTag("manual") != null) return;
        Button bManual = new Button(this);
        bManual.setTag("manual");
        bManual.setText("Pakai teks di atas sebagai model");
        bManual.setAllCaps(false);
        bManual.setOnClickListener(v -> {
            String nama = etCari.getText().toString().trim();
            if (nama.isEmpty()) {
                Toast.makeText(this, "Ketik dulu nama modelnya, Sir.", Toast.LENGTH_SHORT).show();
                return;
            }
            AviBrain.pref(this).edit().putString("model." + prov, nama).apply();
            tvModel.setText("Aktif: " + nama);
            Toast.makeText(this, "Model dipilih: " + nama, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(8);
        lp.bottomMargin = px(8);
        kotak.addView(bManual, lp);
    }

    private ScrollView bungkusScroll(LinearLayout isi) {
        ScrollView sc = new ScrollView(this);
        sc.addView(isi, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
