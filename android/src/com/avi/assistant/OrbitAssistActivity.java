package com.avi.assistant;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * PINTU ASISTEN versi PANEL GOOGLE (b17).
 *
 * Keluhan pemilik (2026-09-25): tahan tombol home terasa BERAT dan penuh
 * bug karena rantai trampoline — activity dibuka → startForegroundService
 * (kanal notifikasi + notifikasi + startForeground + jendela overlay +
 * gelembung) → activity ditutup lagi → pemilik HARUS MENGETUK gelembung
 * lagi baru lembar obrolan muncul. Tiga transisi + satu ketukan manual.
 *
 * Google melakukan SATU hal: tahan home → panel asisten LANGSUNG, satu
 * transisi, tanpa layanan, tanpa notifikasi. Sekarang AVI sama persis:
 * activity translucent ini menampilkan panel ala Google Assistant
 * SEKETIKA (kartu mengapung bersudut 28dp, greeting "Hai, [nama]!",
 * orb gradien multi-warna), mesin menyala di onResume — panel tidak
 * pernah menunggu apa pun.
 *
 * GELEMBUNG ORB TIDAK DIHAPUS: OrbLayanan tetap ada sebagai fitur
 * opt-in dari Pengaturan → Orb melayang (untuk dipakai di atas aplikasi
 * lain), hanya saja TIDAK lagi dipanggil oleh tahan tombol home.
 *
 * Batas platform (riset AOSP 12): di perangkat low-RAM (umum di itel/
 * Transsion) sistem meluncurkan asisten sebagai ACTIVITY ber-intent
 * ACTION_ASSIST — activity inilah titik masuk asisten di HP pemilik.
 * Di HP non-low-RAM, jalur AviSession (VoiceInteractionSession) memakai
 * layout dan mesin yang sama — tampilan identik.
 */
public class OrbitAssistActivity extends Activity implements LiveEngine.Pendengar {

    private View akar, lembar;
    private OrbView orb;
    private TextView tvSapa, tvStatus, tvAnda, tvAvi;
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
        siapkanPanelGoogle();   // b17: LANGSUNG — tanpa trampoline/layanan
    }

    // ============ panel ala Google: tampil seketika, ringan ============

    private void siapkanPanelGoogle() {
        if (layarSiap) return;
        layarSiap = true;
        setContentView(R.layout.overlay_avisession);

        ViewGroup konten = findViewById(android.R.id.content);
        akar = konten.getChildAt(0);
        lembar = akar.findViewById(R.id.lembarSesi);
        orb = akar.findViewById(R.id.orbSesi);
        tvSapa = akar.findViewById(R.id.tvSapa);
        tvStatus = akar.findViewById(R.id.tvStatusSesi);
        tvAnda = akar.findViewById(R.id.tvAndaSesi);
        tvAvi = akar.findViewById(R.id.tvAviSesi);
        gulirPapan = akar.findViewById(R.id.gulirPapan);
        papanPesan  = akar.findViewById(R.id.papanPesan);

        // sesi selalu gelap → orb gradien ala Google (b17)
        orb.setWarnaOrb(0xFF38BDF8);
        orb.setGradienGoogle(true);
        // greeting ala "Hi, how can I help?"
        tvSapa.setText("Hai, " + AviBrain.namaPemilik(this) + "!");
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

        // animasi masuk: kartu naik dari pangkal + orb melebar — satu
        // transisi pendek (Google: panel muncul nyaris seketika)
        akar.setAlpha(0f);
        akar.animate().alpha(1f).setDuration(150L).start();
        lembar.setTranslationY(dip(120));
        lembar.animate().translationY(0f)
                .setDuration(240L)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
        orb.setScaleX(0.82f);
        orb.setScaleY(0.82f);
        orb.animate().scaleX(1f).scaleY(1f)
                .setDuration(320L)
                .setInterpolator(new OvershootInterpolator(1.05f))
                .start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!layarSiap) return;
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
