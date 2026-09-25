package com.avi.assistant;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

/**
 * Sesi ASISTEN PERANGKAT AVI — jalur VoiceInteractionSession untuk HP
 * NON-low-RAM (b18). Tampilan & kelakuan IDENTIK dengan
 * OrbitAssistActivity: lapisan gelap penuh layar ala Google Assistant,
 * greeting kiri atas, EMPAT TITIK kiri bawah, transkrip + jawaban
 * polos di kiri tengah, usap ke bawah untuk menutup.
 *
 * Ringan seperti Google: onCreateContentView hanya menempel layout
 * statis (tanpa render riwayat, tanpa orb 60fps); mesin menyala lewat
 * akar.post() — SETELAH sesi tergambar. Papan pesan tunggal tetap
 * hidup lewat penyimpanan riwayat AviBrain (dibaca aplikasi AVI).
 *
 * Isi = mesin Mode Live yang sama (LiveEngine): dengar → pikir → jawab
 * → dengar lagi; hening beberapa detik = AVI pamit lalu sesi menutup
 * sendiri dan pemilik kembali ke aplikasi yang tadi dibuka.
 */
public class AviSession extends VoiceInteractionSession implements LiveEngine.Pendengar {

    private View akar;
    private TitikEmpat titik;
    private TextView tvSapa, tvStatus, tvAnda, tvAvi;
    private LiveEngine mesin;
    private GestureDetector usap;

    public AviSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        akar = getLayoutInflater().inflate(R.layout.overlay_avisession, null);
        titik = akar.findViewById(R.id.titikSesi);
        tvSapa = akar.findViewById(R.id.tvSapa);
        tvStatus = akar.findViewById(R.id.tvStatusSesi);
        tvAnda = akar.findViewById(R.id.tvAndaSesi);
        tvAvi = akar.findViewById(R.id.tvAviSesi);

        tvSapa.setText("Hai, " + AviBrain.namaPemilik(getContext()));

        // Google: ketuk area kosong TIDAK menutup; usap ke bawah menutup
        usap = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b,
                                             float vx, float vy) {
                if (vy > 0 && vy > Math.abs(vx) * 1.4f && vy > 900f) {
                    finish();
                    return true;
                }
                return false;
            }
        });
        akar.setOnTouchListener((v, ev) -> usap.onTouchEvent(ev));

        akar.findViewById(R.id.btnTutupSesi).setOnClickListener(v -> finish());
        titik.setOnClickListener(v -> {
            if (mesin == null) return;
            int k = titik.getKeadaan();
            if (k == TitikEmpat.BICARA) mesin.potongTts();      // barge-in
            else if (k == TitikEmpat.SIAP) mesin.dengarkanLagi();
        });
        return akar;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        // jendela transparan menutup seluruh layar; gambarannya scrim
        // gelap milik layout — tanpa dim sistem tambahan.
        Dialog jendela = getWindow();
        if (jendela != null && jendela.getWindow() != null) {
            Window w = jendela.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }

        // satu animasi pendek saja — sesi muncul nyaris seketika
        if (akar != null) {
            akar.setAlpha(0f);
            akar.animate().alpha(1f).setDuration(120L).start();
        }

        if (mesin == null) mesin = new LiveEngine(getContext(), this);
        // b18: mesin menyala SETELAH sesi tergambar (sama seperti jalur
        // activity) — panel tidak pernah menunggu bind SpeechRecognizer
        akar.post(() -> {
            Dialog d = getWindow();
            if (mesin == null || d == null || !d.isShowing()) return;
            if (mesin.izinMicAda()) {
                mesin.mulai();
            } else {
                status("Izin mikrofon belum ada — buka aplikasi AVI sekali dulu.");
            }
        });
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
     *  sampai giliran berikutnya (ala Google). */
    @Override public void giliranBeres() { }

    @Override public void rms(float rmsdb) {
        if (titik != null) titik.setRms(rmsdb);
    }

    @Override public void tetidur() {
        finish();
    }
}
