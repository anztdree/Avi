package com.avi.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * PINTU ASISTEN versi ACTIVITY — kini TRAMPOLIN (b13).
 *
 * Latar belakang permintaan pemilik (2026-09-24): "kok malah munculkan
 * orb dan balik buka AVI itu ama aja bohong" — dulu activity ini
 * MENJADI layarnya sendiri, jadi tahan tombol home terasa seperti
 * membuka aplikasi AVI. Sekarang: bila izin "muncul di atas aplikasi
 * lain" sudah ada, activity ini hanya MEMULAI OrbLayanan (jendela
 * melayang SYSTEM_ALERT_WINDOW: orb + lembar obrolan compact DI ATAS
 * aplikasi apa pun) lalu menutup diri — aplikasi yang sedang dipakai
 * tidak pernah tertutup.
 *
 * Tanpa izin melayang: ditawarkan SEKALI (dialog), lalu jatuh ke jalur
 * lama — lembar bawah di dalam activity ini (tetap berfungsi normal).
 * Izin dapat diberikan kapan saja lewat Pengaturan → Orb melayang.
 *
 * KENAPA ADA FILE INI (hasil penelusuran kode sumber AOSP 12):
 * AssistManager.startAssist() membaca isi Settings.Secure.ASSISTANT —
 * bila isinya BUKAN VoiceInteractionService yang aktif, sistem TIDAK
 * menampilkan sesi overlay, melainkan meluncurkan ACTIVITY ber-intent
 * ACTION_ASSIST (startAssistActivity). Di perangkat low-RAM (umum di
 * HP itel/Transsion seperti itel S23) jalur VoiceInteractionService
 * bahkan dilewati saat kualifikasi — asisten selalu dijalankan sebagai
 * activity, persis model "Google Assistant Go". Jadi activity inilah
 * titik masuk asisten di HP pemilik — dan kini ia hanya trampolin.
 */
public class OrbitAssistActivity extends Activity implements LiveEngine.Pendengar {

    private static final String PREF_TAWARAN = "izin_layang_ditawarkan";

    private View akar, lembar;
    private OrbView orb;
    private TextView tvStatus, tvAnda, tvAvi;
    private ScrollView gulirPapan;              // papan pesan bersama
    private LinearLayout papanPesan;
    private LiveEngine mesin;
    private boolean mesinJalan;
    private boolean layarSiap = false;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (cobaLayananMelayang()) {   // true = layanan melayang menyala
            finish();                  // kembali ke aplikasi yang dipakai
            return;
        }
        // tanpa izin melayang (atau tanpa izin mikrofon) → jalur lama
        siapkanLembarActivity();
    }

    /**
     * Coba mulai OrbLayanan. Bila izin overlay belum ada, tawarkan
     * sekali saja (sisanya dialog jangan mengganggu tiap tahan home).
     * @return true bila layanan berhasil dimulai.
     */
    private boolean cobaLayananMelayang() {
        boolean izinMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!izinMic) return false;    // jalur lama menampilkan pesan mic

        if (Settings.canDrawOverlays(this)) {
            try {
                Intent it = new Intent(this, OrbLayanan.class);
                // ATURAN PEMILIK (b14): tahan tombol home yang muncul CUMA
                // gelembung orb kecil — JANGAN langsung buka lembar. Ketuk
                // gelembungnya baru papan pesan muncul.
                it.setAction(OrbLayanan.AKSI_ORB);
                startForegroundService(it);
                return true;
            } catch (Exception e) {
                return false;          // sistem menolak start — jalur lama
            }
        }

        if (!AviBrain.pref(this).getBoolean(PREF_TAWARAN, false)) {
            AviBrain.pref(this).edit().putBoolean(PREF_TAWARAN, true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Orb melayang di atas aplikasi?")
                    .setMessage("Beri AVI izin \u201Cmuncul di atas aplikasi "
                            + "lain\u201D — nanti tahan tombol home akan "
                            + "memunculkan orb & obrolan AVI DI ATAS aplikasi "
                            + "yang sedang dipakai, tanpa pindah aplikasi.")
                    .setPositiveButton("Beri izin", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                            } catch (Exception ignored) {}
                        }
                        finish();
                    })
                    .setNegativeButton("Nanti", (d, w) -> {
                        siapkanLembarActivity();   // jalur lama kali ini
                    })
                    .setCancelable(false)
                    .show();
            return false;   // activity tetap hidup menampung dialog
        }
        return false;
    }

    // ============ jalur lama: lembar bawah di dalam activity ============

    private void siapkanLembarActivity() {
        if (layarSiap) return;
        layarSiap = true;
        setContentView(R.layout.overlay_avisession);

        ViewGroup konten = findViewById(android.R.id.content);
        akar = konten.getChildAt(0);
        lembar = akar.findViewById(R.id.lembarSesi);
        orb = akar.findViewById(R.id.orbSesi);
        tvStatus = akar.findViewById(R.id.tvStatusSesi);
        tvAnda = akar.findViewById(R.id.tvAndaSesi);
        tvAvi = akar.findViewById(R.id.tvAviSesi);
        gulirPapan = akar.findViewById(R.id.gulirPapan);
        papanPesan  = akar.findViewById(R.id.papanPesan);
        // sesi selalu gelap → orb sian elektrik (bukan warna tema)
        orb.setWarnaOrb(0xFF38BDF8);
        // SATU PAPAN PESAN: riwayat yang sama persis dengan aplikasi AVI
        PapanPesan.render(this, papanPesan, gulirPapan, true);

        // sentuh luar lembar (area transparan) = tutup; klik di dalam
        // lembar ditelan lembar sendiri (clickable=true di XML)
        akar.setOnClickListener(v -> finish());
        akar.findViewById(R.id.btnTutupSesi).setOnClickListener(v -> finish());
        orb.setOnClickListener(v -> {
            if (mesin == null) return;
            int k = orb.getKeadaan();
            if (k == OrbView.BICARA) mesin.potongTts();        // barge-in
            else if (k == OrbView.SIAP) mesin.dengarkanLagi();
        });

        // animasi masuk: lembar naik dari pangkal layar + memudar, orb
        // melebar — identik dengan sesi overlay supaya kedua jalur
        // terasa sama.
        akar.setAlpha(0f);
        akar.animate().alpha(1f).setDuration(180L).start();
        lembar.setTranslationY(dip(160));
        lembar.animate().translationY(0f)
                .setDuration(280L)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
        orb.setScaleX(0.82f);
        orb.setScaleY(0.82f);
        orb.animate().scaleX(1f).scaleY(1f)
                .setDuration(360L)
                .setInterpolator(new OvershootInterpolator(1.05f))
                .start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!layarSiap) return;      // trampoline / dialog izin — tanpa mesin
        if (mesin == null) mesin = new LiveEngine(this, this);
        if (!mesinJalan) {
            mesinJalan = true;
            if (mesin.izinMicAda()) {
                mesin.mulai();
            } else {
                status("Izin mikrofon belum ada — buka aplikasi AVI sekali dulu.");
            }
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

    private float dip(float nilai) {
        return nilai * getResources().getDisplayMetrics().density;
    }

    // ================= peristiwa dari mesin (thread utama) =================

    @Override public void keadaan(int k) {
        if (orb != null) orb.setKeadaan(k);
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

    /** Giliran selesai — pasangan sudah masuk riwayat bersama → papan
     *  digambar ulang supaya menyatu dengan aplikasi AVI. */
    @Override public void giliranBeres() {
        if (papanPesan != null) {
            PapanPesan.render(this, papanPesan, gulirPapan, true);
        }
        if (tvAnda != null) tvAnda.setVisibility(View.GONE);
        if (tvAvi != null) tvAvi.setVisibility(View.GONE);
    }

    @Override public void rms(float rmsdb) {
        if (orb != null) orb.setRms(rmsdb);
    }

    @Override public void tetidur() {
        finish();
    }
}
