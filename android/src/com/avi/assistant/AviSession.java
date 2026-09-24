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
import android.widget.TextView;

/**
 * Sesi ASISTEN PERANGKAT AVI — kartu obrolan TRANSPARAN melayang di atas
 * aplikasi mana pun, persis tampilan Google Assistant saat dipanggil
 * lewat tahan tombol home / sapu sudut bawah.
 *
 * Isi kartu = mesin Mode Live yang sama (LiveEngine): dengar → pikir →
 * jawab → dengar lagi; hening beberapa detik = AVI pamit lalu kartu
 * menutup sendiri, dan pemilik kembali ke aplikasi yang tadi dibuka.
 */
public class AviSession extends VoiceInteractionSession implements LiveEngine.Pendengar {

    private OrbView orb;
    private TextView tvStatus, tvAnda, tvAvi;
    private LiveEngine mesin;

    public AviSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        View isi = getLayoutInflater().inflate(R.layout.overlay_avisession, null);
        orb = isi.findViewById(R.id.orbSesi);
        tvStatus = isi.findViewById(R.id.tvStatusSesi);
        tvAnda = isi.findViewById(R.id.tvAndaSesi);
        tvAvi = isi.findViewById(R.id.tvAviSesi);
        orb.setWarnaOrb(AviBrain.warnaAksen(getContext()));

        isi.findViewById(R.id.btnTutupSesi).setOnClickListener(v -> finish());
        orb.setOnClickListener(v -> {
            if (mesin == null) return;
            int k = orb.getKeadaan();
            if (k == OrbView.BICARA) mesin.potongTts();        // barge-in
            else if (k == OrbView.SIAP) mesin.dengarkanLagi();
        });
        return isi;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        // jendela transparan tanpa selubung gelap — kartu melayang bersih
        Dialog jendela = getWindow();
        if (jendela != null && jendela.getWindow() != null) {
            Window w = jendela.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (mesin == null) mesin = new LiveEngine(getContext(), this);
        if (mesin.izinMicAda()) {
            mesin.mulai();
        } else {
            tvStatus.setText("Izin mikrofon belum ada — buka aplikasi AVI sekali dulu.");
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

    // ================= peristiwa dari mesin (thread utama) =================

    @Override public void keadaan(int k) {
        if (orb != null) orb.setKeadaan(k);
    }

    @Override public void status(String teks) {
        if (tvStatus != null) tvStatus.setText(teks);
    }

    @Override public void transkripAnda(String teks) {
        if (tvAnda != null) tvAnda.setText(teks);
    }

    @Override public void teksAvi(String teks) {
        if (tvAvi != null) tvAvi.setText(teks);
    }

    @Override public void rms(float rmsdb) {
        if (orb != null) orb.setRms(rmsdb);
    }

    @Override public void tetidur() {
        finish();
    }
}
