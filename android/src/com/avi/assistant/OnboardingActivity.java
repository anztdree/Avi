package com.avi.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding terpandu 3 langkah (ala asisten modern), bisa dilewati:
 * 1) pilih penyedia AI  2) tempel API key  3) ambil & pilih model.
 * Muncul sekali di awal bila konfigurasi masih kosong.
 */
public class OnboardingActivity extends Activity {

    private ViewFlipper flipper;
    private RadioGroup rgProv;
    private EditText etKey;
    private TextView tvPetunjuk, tvStatusModel;
    private ListView lvModel;
    private Button bLanjut;
    private String prov = "gemini";

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        flipper = findViewById(R.id.flipper);
        rgProv = findViewById(R.id.rgProv);
        etKey = findViewById(R.id.etKeyOnboard);
        tvPetunjuk = findViewById(R.id.tvPetunjukOnboard);
        tvStatusModel = findViewById(R.id.tvStatusModel);
        lvModel = findViewById(R.id.lvModel);
        bLanjut = findViewById(R.id.bLanjut);

        prov = AviBrain.penyedia(this);
        if ("nvidia".equals(prov)) rgProv.check(R.id.rbProvNvidia);
        else if ("openrouter".equals(prov)) rgProv.check(R.id.rbProvOpenrouter);
        else rgProv.check(R.id.rbProvGemini);
        etKey.setText(AviBrain.pref(this).getString("apikey." + prov, ""));

        rgProv.setOnCheckedChangeListener((g, id) -> {
            prov = id == R.id.rbProvNvidia ? "nvidia"
                    : id == R.id.rbProvOpenrouter ? "openrouter" : "gemini";
            AviBrain.pref(this).edit().putString("penyedia", prov).apply();
            etKey.setText(AviBrain.pref(this).getString("apikey." + prov, ""));
            petunjuk();
        });

        ((TextView) findViewById(R.id.tvSalamOnboard)).setText(
                "Halo, " + AviBrain.namaPemilik(this) + "!");

        findViewById(R.id.bLewati).setOnClickListener(v -> selesai());
        bLanjut.setOnClickListener(v -> langkahBerikut());
        findViewById(R.id.bAmbilModel).setOnClickListener(v -> ambilModel());

        petunjuk();
    }

    private void petunjuk() {
        switch (prov) {
            case "nvidia":
                tvPetunjuk.setText("API key NVIDIA NIM (gratis) — buat di build.nvidia.com");
                break;
            case "openrouter":
                tvPetunjuk.setText("API key OpenRouter — buat di openrouter.ai/keys");
                break;
            default:
                tvPetunjuk.setText("API key Gemini (punya kuota gratis) — buat di aistudio.google.com/apikey");
        }
    }

    private void langkahBerikut() {
        int anak = flipper.getDisplayedChild();
        if (anak == 0) {
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Tempel dulu API key-nya, "
                        + AviBrain.namaPemilik(this) + ".", Toast.LENGTH_SHORT).show();
                return;
            }
            AviBrain.pref(this).edit().putString("apikey." + prov, key).apply();
            flipper.showNext();
            bLanjut.setText("Selesai");
            ambilModel();
        } else if (anak == 1) {
            String model = AviBrain.modelAktif(this);
            if (model.isEmpty()) {
                Toast.makeText(this, "Pilih dulu modelnya — atau sentuh \"Lewati\".",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            selesai();
        }
    }

    private void ambilModel() {
        tvStatusModel.setText("Mengambil daftar model…");
        AviBrain.daftarModel(this, (daftar, galat) -> {
            if (isFinishing() || isDestroyed()) return;
            if (galat != null) {
                tvStatusModel.setText(galat);
                return;
            }
            tvStatusModel.setText("Sentuh model yang ingin dipakai. Rekomendasi: "
                    + (AviBrain.penyedia(this).equals("gemini")
                    ? AviBrain.MODEL_REKOMENDASI_GEMINI : daftar.get(0)));
            final List<String> tampil = new ArrayList<>(daftar);
            lvModel.setAdapter(new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, tampil) {
                @Override
                public View getView(int posisi, View ubah, ViewGroup induk) {
                    View v = super.getView(posisi, ubah, induk);
                    ((TextView) v).setTextSize(13f);
                    return v;
                }
            });
        });
    }

    private void selesai() {
        AviBrain.pref(this).edit().putBoolean("onboarding_done", true).apply();
        Toast.makeText(this, "AVI siap, " + AviBrain.namaPemilik(this) + "!",
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
