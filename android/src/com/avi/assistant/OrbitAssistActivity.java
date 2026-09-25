package com.avi.assistant;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/**
 * PINTU ASISTEN versi UI GOOGLE SEBENARNYA (b18).
 *
 * Riwayat keluhan pemilik (2026-09-25): b17 masih "berat" dan masih
 * terasa seperti orb melayang. Audit menemukan akarnya: (1) panel b17
 * masih kartu mengapung + orb gradien 100dp yang menggambar ulang diri
 * 60 KALI per detik; (2) riwayat percakapan dirender di onCreate SEBELUM
 * layar tergambar; (3) mesin (bind SpeechRecognizer) menyala di onResume
 * yang berjalan SEBELUM frame pertama — panel menunggu semuanya.
 *
 * Google saat tahan home melakukan SESUKMUNGKIN HAMPIR NOL: lapisan
 * gelap penuh layar, greeting kiri atas, EMPAT TITIK kiri bawah, teks
 * jawaban polos. Tanpa kartu, tanpa orb, tanpa riwayat. Sekarang AVI
 * persis begitu:
 *   - onCreate hanya menempel layout (semuanya statis) → frame pertama
 *     nyaris instan;
 *   - mesin menyala lewat akar.post() — SETELAH panel benar-benar
 *     tergambar di layar;
 *   - TitikEmpat hanya beranimasi SAAT bekerja, mati total saat SIAP;
 *   - menutup: tombol ✕, usap ke bawah (pola Google), atau AVI tidur.
 *
 * Papan pesan bersama TIDAK digambar di panel (Google juga tidak
 * menampilkan riwayat) — aturan papan tunggal tetap hidup: giliran
 * obrolan tetap tersimpan ke riwayat aplikasi AVI lewat AviBrain.
 *
 * Batas platform (riset AOSP 12): di perangkat low-RAM (umum di itel/
 * Transsion) sistem meluncurkan asisten sebagai ACTIVITY ber-intent
 * ACTION_ASSIST — activity inilah titik masuk di HP pemilik. Di HP
 * non-low-RAM jalur AviSession memakai layout & mesin yang sama.
 */
public class OrbitAssistActivity extends Activity implements LiveEngine.Pendengar {

    private View akar;
    private TitikEmpat titik;
    private TextView tvSapa, tvStatus, tvAnda, tvAvi;
    private LiveEngine mesin;
    private boolean mesinJalan;
    private GestureDetector usap;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.overlay_avisession);   // statis — instan

        akar = findViewById(R.id.akarSesi);
        titik = findViewById(R.id.titikSesi);
        tvSapa = findViewById(R.id.tvSapa);
        tvStatus = findViewById(R.id.tvStatusSesi);
        tvAnda = findViewById(R.id.tvAndaSesi);
        tvAvi = findViewById(R.id.tvAviSesi);

        tvSapa.setText("Hai, " + AviBrain.namaPemilik(this));

        // Google: ketuk area kosong TIDAK menutup; usap ke bawah menutup
        usap = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b,
                                             float vx, float vy) {
                if (vy > 0 && vy > Math.abs(vx) * 1.4f && vy > 900f) {
                    pamit();
                    return true;
                }
                return false;
            }
        });
        akar.setOnTouchListener((v, ev) -> usap.onTouchEvent(ev));

        findViewById(R.id.btnTutupSesi).setOnClickListener(v -> pamit());
        titik.setOnClickListener(v -> {
            if (mesin == null) return;
            int k = titik.getKeadaan();
            if (k == TitikEmpat.BICARA) mesin.potongTts();      // barge-in
            else if (k == TitikEmpat.SIAP) mesin.dengarkanLagi();
        });

        // satu animasi pendek saja — panel muncul nyaris seketika
        akar.setAlpha(0f);
        akar.animate().alpha(1f).setDuration(120L).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mesin == null) mesin = new LiveEngine(this, this);
        if (!mesinJalan) {
            mesinJalan = true;
            // b18: mesin menyala SETELAH frame pertama tergambar —
            // dulu onResume langsung bind SpeechRecognizer sehingga
            // panel tiba lambat ("berat") di low-RAM
            akar.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (mesin.izinMicAda()) {
                    mesin.mulai();
                } else {
                    status("Izin mikrofon belum ada — buka aplikasi AVI sekali dulu.");
                }
            });
        }
    }

    @Override
    protected void onPause() {
        // mikrofon tidak boleh hidup di latar — privasi & anti-gema
        if (mesin != null) { mesin.hentikan(); mesinJalan = false; }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mesin != null) { mesin.hentikan(); mesin = null; }
        super.onDestroy();
    }

    private void pamit() { finish(); }

    // ================= peristiwa dari mesin (thread utama) =================

    @Override public void keadaan(int k) {
        if (titik != null) titik.setKeadaan(k);
    }

    @Override public void status(String teks) {
        if (tvStatus != null) tvStatus.setText(teks);
    }

    @Override public void transkripAnda(String teks) {
        if (tvAnda == null) return;
        if (teks == null || teks.trim().length() == 0) {
            tvAnda.setVisibility(View.GONE);
            return;
        }
        tvAnda.setVisibility(View.VISIBLE);
        tvAnda.setText(teks);
    }

    @Override public void teksAvi(String teks) {
        if (tvAvi == null) return;
        if (teks == null || teks.trim().length() == 0) {
            tvAvi.setVisibility(View.GONE);
            return;
        }
        tvAvi.setVisibility(View.VISIBLE);
        tvAvi.setText(teks);
    }

    /** Giliran selesai — pasangan sudah tersimpan ke riwayat bersama
     *  oleh AviBrain.tanyaStream. Transkrip & jawaban dibiarkan terlihat
     *  sampai giliran berikutnya (ala Google); papan lengkap ada di
     *  aplikasi AVI. */
    @Override public void giliranBeres() { }

    @Override public void rms(float rmsdb) {
        if (titik != null) titik.setRms(rmsdb);
    }

    @Override public void tetidur() { pamit(); }
}
