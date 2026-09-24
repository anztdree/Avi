package com.avi.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * MODE LIVE AVI — layar penuh, satu dari dua wajah LiveEngine
 * (pasangannya: AviSession, kartu transparan asisten perangkat).
 *
 * Loop dikelola LiveEngine: MENDENGARKAN → BERPIKIR → BICARA → dengar lagi;
 * hening beberapa detik = AVI pamit lalu tidur (pola Siri/GA).
 * Layar ini hanya menggambar: orb, status, transkrip real-time.
 */
public class LiveActivity extends Activity implements LiveEngine.Pendengar {

    private OrbView orb;
    private TextView tvStatus, tvAnda, tvAvi;
    private ScrollView scrollTranskrip;
    private LiveEngine mesin;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        orb = findViewById(R.id.orbLive);
        tvStatus = findViewById(R.id.tvStatusLive);
        tvAnda = findViewById(R.id.tvAndaLive);
        tvAvi = findViewById(R.id.tvAviLive);
        scrollTranskrip = findViewById(R.id.scrollTranskrip);
        orb.setWarnaOrb(AviBrain.warnaAksen(this));

        findViewById(R.id.btnTutupLive).setOnClickListener(v -> finish());
        orb.setOnClickListener(v -> {
            if (mesin == null) return;
            int k = orb.getKeadaan();
            if (k == OrbView.BICARA) mesin.potongTts();        // barge-in
            else if (k == OrbView.SIAP) mesin.dengarkanLagi(); // lanjut setelah jeda
        });

        mesin = new LiveEngine(this, this);
        if (mesin.izinMicAda()) {
            mesin.mulai();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11);
        }
    }

    @Override
    protected void onDestroy() {
        if (mesin != null) { mesin.hentikan(); mesin = null; }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int kode, String[] izin, int[] keputusan) {
        super.onRequestPermissionsResult(kode, izin, keputusan);
        if (kode == 11 && izin.length > 0
                && Manifest.permission.RECORD_AUDIO.equals(izin[0])
                && keputusan[0] == PackageManager.PERMISSION_GRANTED) {
            mesin.mulai();
        } else if (kode == 11) {
            tvStatus.setText("Izin mikrofon belum ada — berikan lewat Pengaturan ponsel.");
        }
    }

    // ================= peristiwa dari mesin (thread utama) =================

    @Override public void keadaan(int k) { orb.setKeadaan(k); }

    @Override public void status(String teks) { tvStatus.setText(teks); }

    @Override public void transkripAnda(String teks) {
        tvAnda.setText(teks);
        scrollKeBawah();
    }

    @Override public void teksAvi(String teks) {
        tvAvi.setText(teks);
        scrollKeBawah();
    }

    @Override public void rms(float rmsdb) { orb.setRms(rmsdb); }

    @Override public void tetidur() { finish(); }

    private void scrollKeBawah() {
        scrollTranskrip.post(() -> scrollTranskrip.fullScroll(View.FOCUS_DOWN));
    }
}
