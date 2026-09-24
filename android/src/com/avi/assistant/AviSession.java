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
 * Sesi ASISTEN PERANGKAT AVI — tampilan "Orbit": layar penuh imersif
 * saat pemilik MENAHAN TOMBOL HOME.
 *
 * Sengaja BERBEDA dari Google Assistant (ya itu poinnya — GA punya wajah
 * sendiri, AVI juga punya wajah sendiri): kanvas gelap JARVIS dengan
 * pendaran sian/indigo, orb raksasa yang bernapas di tengah layar,
 * ucapan pemilik besar di tengah, jawaban AVI di kartu kaca, dan animasi
 * masuk yang lembut setiap kali dipanggil.
 *
 * Isi = mesin Mode Live yang sama (LiveEngine): dengar → pikir → jawab →
 * dengar lagi; hening beberapa detik = AVI pamit lalu sesi menutup
 * sendiri dan pemilik kembali ke aplikasi yang tadi dibuka.
 */
public class AviSession extends VoiceInteractionSession implements LiveEngine.Pendengar {

    private View akar;
    private OrbView orb;
    private TextView tvStatus, tvAnda, tvAvi;
    private LiveEngine mesin;

    public AviSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        akar = getLayoutInflater().inflate(R.layout.overlay_avisession, null);
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
        return akar;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        // jendela MENUTUPI LAYAR — kanvas gelap kita sendiri yang menutupi
        // aplikasi di bawah (tanpa dim tambahan dari sistem).
        Dialog jendela = getWindow();
        if (jendela != null && jendela.getWindow() != null) {
            Window w = jendela.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }

        // animasi masuk: seluruh panggung naik + memudar, orb melebar
        // dengan pendaran singkat — sesi terasa "dipanggil", bukan muncul.
        if (akar != null) {
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
