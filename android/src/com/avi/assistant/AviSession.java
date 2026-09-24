package com.avi.assistant;

import android.content.Context;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/**
 * Sesi ASISTEN PERANGKAT AVI — tampilan "Orbit Lembar Bawah": seperti
 * Google Assistant, sesi MUNCUL HANYA DI PANGKAL LAYAR dalam kartu kaca
 * bersudut atas bulat; aplikasi sebelumnya tetap terlihat di atasnya.
 *
 * Sentuh di luar lembar = sesi menutup (pola lembar bawah). Di dalam
 * lembar: orb yang bernapas, status mesin, ucapan pemilik, dan jawaban
 * AVI di kartu kaca — plus animasi naik lembut setiap kali dipanggil.
 *
 * Isi = mesin Mode Live yang sama (LiveEngine): dengar → pikir → jawab →
 * dengar lagi; hening beberapa detik = AVI pamit lalu sesi menutup
 * sendiri dan pemilik kembali ke aplikasi yang tadi dibuka.
 */
public class AviSession extends VoiceInteractionSession implements LiveEngine.Pendengar {

    private View akar, lembar;
    private OrbView orb;
    private TextView tvStatus, tvAnda, tvAvi;
    private LiveEngine mesin;

    public AviSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        akar = getLayoutInflater().inflate(R.layout.overlay_avisession, null);
        lembar = akar.findViewById(R.id.lembarSesi);
        orb = akar.findViewById(R.id.orbSesi);
        tvStatus = akar.findViewById(R.id.tvStatusSesi);
        tvAnda = akar.findViewById(R.id.tvAndaSesi);
        tvAvi = akar.findViewById(R.id.tvAviSesi);
        // sesi selalu gelap → orb sian elektrik (bukan warna tema)
        orb.setWarnaOrb(0xFF38BDF8);

        // sentuh luar lembar (area transparan) = tutup sesi; klik di
        // dalam lembar ditelan lembar sendiri (clickable=true di XML)
        akar.setOnClickListener(v -> finish());
        akar.findViewById(R.id.btnTutupSesi).setOnClickListener(v -> finish());
        orb.setOnClickListener(v -> {
            if (mesin == null) return;
            int k = orb.getKeadaan();
            if (k == OrbView.BICARA) mesin.potongTts();        // barge-in
            else if (k == OrbView.SIAP) mesin.dengarkanLagi();
        });
        return akar;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        // jendela transparan menutup seluruh area aplikasi HANYA untuk
        // menangkap sentuhan; gambarannya milik lembar bawah — aplikasi
        // di atas lembar tetap terlihat jelas (tanpa dim sistem).
        Dialog jendela = getWindow();
        if (jendela != null && jendela.getWindow() != null) {
            Window w = jendela.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }

        // animasi masuk: lembar naik dari pangkal layar + memudar, orb
        // melebar dengan pendaran singkat — terasa "dipanggil", bukan muncul.
        if (akar != null) {
            akar.setAlpha(0f);
            akar.animate().alpha(1f)
                    .setDuration(180L)
                    .start();
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

        if (mesin == null) mesin = new LiveEngine(getContext(), this);
        if (mesin.izinMicAda()) {
            mesin.mulai();
        } else {
            status("Izin mikrofon belum ada — buka aplikasi AVI sekali dulu.");
        }
    }

    @Override
    public void onHide() {
        if (mesin != null) mesin.hentikan();
    }

    @Override
    public void onDestroy() {
        if (mesin != null) { mesin.hentikan(); mesin = null; }
        super.onDestroy();
    }

    private float dip(float nilai) {
        return nilai * getContext().getResources().getDisplayMetrics().density;
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
