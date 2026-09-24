package com.avi.assistant;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/**
 * LAYAR ORBIT sebagai ACTIVITY — jalur asisten perangkat untuk perangkat
 * yang ditandai low-RAM (umum di HP itel/Transsion seperti itel S23).
 *
 * KENAPA ADA FILE INI (hasil penelusuran kode sumber AOSP 12):
 * AssistManager.startAssist() membaca isi Settings.Secure.ASSISTANT —
 * bila isinya BUKAN VoiceInteractionService yang aktif, sistem TIDAK
 * menampilkan sesi overlay, melainkan meluncurkan ACTIVITY ber-intent
 * ACTION_ASSIST (startAssistActivity). Di perangkat low-RAM, jalur
 * VoiceInteractionService bahkan dilewati saat kualifikasi
 * (AssistantRoleBehavior.getQualifyingPackagesAsUser) — asisten selalu
 * dijalankan sebagai activity, persis model "Google Assistant Go".
 *
 * Jadi wajah "Orbit" yang tampil sebagai VoiceInteractionSession di
 * perangkat normal, di sini dihidupkan DI DALAM activity: kanvas gelap
 * JARVIS + pendaran sian/indigo + orb bernapas + LiveEngine yang sama
 * persis (dengar → pikir → jawab → dengar lagi; hening = AVI pamit lalu
 * layar menutup sendiri dan pemilik kembali ke aplikasi sebelumnya —
 * activity ini translucent, tanpa jejak di Recents).
 *
 * Dua jalur, satu wajah: normal → AviSession (overlay sesi);
 * low-RAM → OrbitAssistActivity (activity). Tampilannya identik.
 */
public class OrbitAssistActivity extends Activity implements LiveEngine.Pendengar {

    private View akar;
    private OrbView orb;
    private TextView tvStatus, tvAnda, tvAvi;
    private LiveEngine mesin;
    private boolean mesinJalan;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.overlay_avisession);

        ViewGroup konten = findViewById(android.R.id.content);
        akar = konten.getChildAt(0);
        orb = akar.findViewById(R.id.orbSesi);
        tvStatus = akar.findViewById(R.id.tvStatusSesi);
        tvAnda = akar.findViewById(R.id.tvAndaSesi);
        tvAvi = akar.findViewById(R.id.tvAviSesi);
        // sesi selalu di kanvas gelap → orb sian elektrik (bukan warna tema)
        orb.setWarnaOrb(0xFF38BDF8);

        akar.findViewById(R.id.btnTutupSesi).setOnClickListener(v -> finish());
        orb.setOnClickListener(v -> {
            if (mesin == null) return;
            int k = orb.getKeadaan();
            if (k == OrbView.BICARA) mesin.potongTts();        // barge-in
            else if (k == OrbView.SIAP) mesin.dengarkanLagi();
        });

        // animasi masuk: seluruh panggung naik + memudar, orb melebar —
        // identik dengan sesi overlay supaya kedua jalur terasa sama.
        akar.setAlpha(0f);
        akar.setTranslationY(dip(30));
        akar.animate().alpha(1f).translationY(0f)
                .setDuration(260L)
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

    @Override public void rms(float rmsdb) {
        if (orb != null) orb.setRms(rmsdb);
    }

    @Override public void tetidur() {
        finish();
    }
}
