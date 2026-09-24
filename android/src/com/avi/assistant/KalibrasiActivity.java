package com.avi.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * KALIBRASI SUARA v2 — pendaftaran suara pemilik ala "Voice Match".
 *
 * SATU layar, TIGA keadaan (permintaan pemilik, 2026-09-24):
 *   A) Belum terdaftar — undangan, satu tombol "Mulai".
 *   B) Pendaftaran     — alur OTOMATIS tanpa tombol:
 *        1. kondisi ruangan (1,2 dtk, pemilik diam),
 *        2. membaca satu frasa kaya fonem → ProfilSuara dibangun
 *           (MFCC + nada dasar, murni DSP Java — lihat Dsp.java),
 *        3. sapaan "Hai AVI" 3× → KunciSapa (template DTW).
 *      Take gagal (terlalu pelan/terpotong) = AVI minta ulang take itu
 *      saja, alur tidak kembali ke awal. Selesai = tersimpan otomatis.
 *   C) TERKUNCI        — suara terdaftar; pilihan hanya dua:
 *      "Reset ulang" (jalankan alur dari awal, timpa profil lama) dan
 *      "Hapus kalibrasi" (bersihkan profil+kunci+pref → kembali ke A).
 *
 * Satu sesi AudioRecord 16 kHz menemani seluruh alur (mikrofon dibuka
 * sekali, dilepastikan lepas di finally). SpeechRecognizer TIDAK dipakai
 * di sini — pengujian kecerdasan pengenal bukan bagian pendaftaran suara.
 */
public class KalibrasiActivity extends Activity {

    /** Frasa baca: sengaja kaya fonem p/b/t/d/k/g/c/j/sy + vokal penuh. */
    private static final String FRASA_BACA =
            "Selamat pagi, saya pemilik AVI. Suara ini kunci rumah saya. "
            + "Langit cerah, awan pelan berarak di cakrawala. AVI, nyalakan "
            + "senter, buka musik, dan ingat jadwal saya hari ini.";

    private static final int BLOK = 512;
    private static final long RUANG_MS = 1200;

    private TextView tvJudul, tvNarasi, tvLangkah, tvInstruksi, tvFrasa, tvUmpan, tvDots;
    private ProgressBar pbLevel;
    private Button bUtama, bKedua;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean alurJalan = false;
    private Thread alurThread;
    private Runnable tertunda;   // aksi setelah izin mikrofon diberikan

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kalibrasi);

        tvJudul    = findViewById(R.id.tvJudul);
        tvNarasi   = findViewById(R.id.tvNarasi);
        tvLangkah  = findViewById(R.id.tvLangkah);
        tvInstruksi= findViewById(R.id.tvInstruksi);
        tvFrasa    = findViewById(R.id.tvFrasa);
        tvUmpan    = findViewById(R.id.tvUmpan);
        tvDots     = findViewById(R.id.tvDots);
        pbLevel    = findViewById(R.id.pbLevel);
        bUtama     = findViewById(R.id.bUtama);
        bKedua     = findViewById(R.id.bKedua);

        // A dan C sama-sama membuka alur pendaftaran (C = reset ulang)
        bUtama.setOnClickListener(v -> denganIzin(this::mulaiAlur));
        bKedua.setOnClickListener(v -> hapusKalibrasi());

        tampilkanMenurutKeadaan();
    }

    // ========================= keadaan A dan C =========================

    private boolean terdaftar() { return ProfilSuara.ada(this); }

    private void tampilkanMenurutKeadaan() {
        if (terdaftar()) tampilkanKunci(); else tampilkanUndangan();
    }

    /** Keadaan A — undangan. */
    private void tampilkanUndangan() {
        String nama = AviBrain.namaPemilik(this);
        tvJudul.setText("Kenalkan AVI dengan suara Anda");
        tvNarasi.setText("Setelah terdaftar, AVI memakai suara Anda sebagai "
                + "kunci pribadinya: gerbang Mode Live mengenali sapaan Anda "
                + "sebelum AVI mendengarkan perintah. Kurang dari satu menit.");
        tvLangkah.setText("Siap mendaftar");
        tvInstruksi.setText("Alurnya otomatis tiga langkah — mendengar ruangan "
                + "(diam dulu), membaca satu kalimat, lalu mengunci sapaan "
                + "\u201CHai AVI\u201D tiga kali. Setelah menekan Mulai, Anda "
                + "tidak perlu menyentuh apa pun lagi" + (nama.isEmpty() ? "." : ", " + nama + "."));
        tvFrasa.setVisibility(View.GONE);
        tvDots.setVisibility(View.GONE);
        tvUmpan.setVisibility(View.GONE);
        pbLevel.setProgress(0);
        bUtama.setVisibility(View.VISIBLE);
        bUtama.setText("Mulai pendaftaran");
        bKedua.setVisibility(View.GONE);
    }

    /** Keadaan C — terkunci: hanya Reset ulang / Hapus. */
    private void tampilkanKunci() {
        long w = AviBrain.pref(this).getLong("kal_waktu", 0);
        String waktu = w > 0
                ? new SimpleDateFormat("d MMM yyyy • HH.mm", Locale.getDefault())
                        .format(new Date(w))
                : "—";
        float pitch = AviBrain.pref(this).getFloat("kal_pitch", 0f);
        String mode = GerbangSapa.mode(this);
        String label = "ketat".equals(mode) ? "Ketat"
                : "mati".equals(mode) ? "Nonaktif" : "Lembut";

        tvJudul.setText("Suara Anda terdaftar");
        tvNarasi.setText("Layar ini terkunci. Yang bisa dilakukan hanya "
                + "mereset atau menghapus — persis seperti kunci asli.");
        tvLangkah.setText("Terdaftar ✓  •  terkunci");
        tvInstruksi.setText("Didaftarkan " + waktu
                + (pitch > 0 ? " • nada dasar suara Anda ±" + Math.round(pitch) + " Hz" : "")
                + " • gerbang Mode Live: " + label + ".");
        tvFrasa.setVisibility(View.GONE);
        tvDots.setVisibility(View.GONE);
        tvUmpan.setVisibility(View.GONE);
        pbLevel.setProgress(100);
        bUtama.setVisibility(View.VISIBLE);
        bUtama.setText("Reset ulang");
        bKedua.setVisibility(View.VISIBLE);
        bKedua.setText("Hapus kalibrasi");
    }

    private void hapusKalibrasi() {
        new AlertDialog.Builder(this)
                .setTitle("Hapus kalibrasi?")
                .setMessage("Profil suara & kunci sapaan dihapus. AVI kembali "
                        + "melayani siapa pun sampai Anda mendaftar ulang.")
                .setPositiveButton("Hapus", (d, w) -> {
                    ProfilSuara.berkas(this).delete();
                    KunciSapa.berkas(this).delete();
                    AviBrain.pref(this).edit()
                            .remove("kal_lantai_db").remove("kal_bicara_db")
                            .remove("kal_skor").remove("kal_waktu")
                            .remove("kal_pitch")
                            .apply();
                    Toast.makeText(this, "Kalibrasi dihapus.", Toast.LENGTH_SHORT).show();
                    tampilkanUndangan();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ============================== izin ==============================

    private boolean izinAda() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void denganIzin(Runnable aksi) {
        if (izinAda()) { aksi.run(); return; }
        tertunda = aksi;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int kode, String[] izin, int[] hasil) {
        super.onRequestPermissionsResult(kode, izin, hasil);
        if (kode != 1) return;
        if (hasil.length > 0 && hasil[0] == PackageManager.PERMISSION_GRANTED) {
            if (tertunda != null) { Runnable a = tertunda; tertunda = null; a.run(); }
        } else {
            Toast.makeText(this, "Izin mikrofon ditolak — pendaftaran suara "
                    + "butuh mikrofon.", Toast.LENGTH_LONG).show();
        }
    }

    // ==================== alur pendaftaran (B) ====================

    private void mulaiAlur() {
        if (alurJalan) return;
        alurJalan = true;
        bUtama.setVisibility(View.GONE);   // terkunci selama alur berjalan
        bKedua.setVisibility(View.GONE);
        alurThread = new Thread(this::jalankanAlur, "avi-kalibrasi");
        alurThread.start();
    }

    private void jalankanAlur() {
        AudioRecord ar = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(Dsp.SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    Dsp.SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 8192));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                gagalAlur("Mikrofon tidak bisa dibuka — tutup aplikasi lain "
                        + "yang memakai mikrofon, lalu coba lagi.");
                return;
            }
            ar.startRecording();
            short[] blok = new short[BLOK];

            // ---- LANGKAH 1: kondisi ruangan (pemilik diam) ----
            ui.post(() -> {
                tvLangkah.setText("Langkah 1 dari 3 — kondisi ruangan");
                tvInstruksi.setText("Diam dulu ya — AVI sedang mendengar "
                        + "ruangan selama sedetik lebih…");
                tvFrasa.setVisibility(View.GONE);
                tvDots.setVisibility(View.GONE);
                tvUmpan.setVisibility(View.GONE);
                pbLevel.setProgress(0);
            });
            double jumlah = 0;
            int nLantai = 0;
            long t0 = System.currentTimeMillis();
            while (alurJalan && System.currentTimeMillis() - t0 < RUANG_MS) {
                int n = ar.read(blok, 0, blok.length);
                if (n <= 0) continue;
                jumlah += Dsp.db(blok, 0, n);
                nLantai++;
                pasangMeter(Dsp.db(blok, 0, n));
            }
            if (!alurJalan) return;
            final float lantai = nLantai > 0 ? (float) (jumlah / nLantai) : -55f;

            // ---- LANGKAH 2: baca frasa → profil ----
            ProfilSuara profil = null;
            for (int percobaan = 1; percobaan <= 4 && alurJalan; percobaan++) {
                final int ke = percobaan;
                ui.post(() -> {
                    tvLangkah.setText("Langkah 2 dari 3 — profil suara"
                            + (ke > 1 ? " (ulangan " + ke + ")" : ""));
                    tvInstruksi.setText("Baca kalimat ini dengan suara jelas dan "
                            + "normal — AVI menunggu sampai Anda selesai:");
                    tvFrasa.setVisibility(View.VISIBLE);
                    tvFrasa.setText(FRASA_BACA);
                    tvDots.setVisibility(View.GONE);
                    pbLevel.setProgress(0);
                });
                short[] ucap = rekamUcapan(ar, blok, lantai + 8.0, 14000, 1100);
                if (!alurJalan) return;
                profil = ProfilSuara.bangun(ucap, 0, ucap.length, lantai);
                if (profil != null && profil.frameSuara >= 15) break;
                profil = null;
                ui.post(() -> tvInstruksi.setText("Sepertinya terlalu pelan "
                        + "atau terpotong — sekali lagi ya, "
                        + AviBrain.namaPemilik(this) + "."));
            }
            if (!alurJalan) return;
            if (profil == null) {
                gagalAlur("Suara masih belum tertangkap baik. Cari tempat lebih "
                        + "sunyi, bicara lebih dekat ke ponsel, lalu pendaftaran "
                        + "bisa diulang.");
                return;
            }

            // ---- LANGKAH 3: kunci sapaan 3 take ----
            ArrayList<short[]> take = new ArrayList<>();
            int usaha = 0;
            while (take.size() < 3 && alurJalan && usaha < 8) {
                usaha++;
                final int terisi = take.size();
                ui.post(() -> {
                    tvLangkah.setText("Langkah 3 dari 3 — kunci sapaan");
                    tvInstruksi.setText("Sekarang sapa AVI — ucapkan dengan nada "
                            + "yang sama tiap kali:");
                    tvFrasa.setVisibility(View.VISIBLE);
                    tvFrasa.setText("\u201CHai AVI\u201D");
                    tvDots.setVisibility(View.VISIBLE);
                    tvDots.setText(titikSapa(terisi));
                    pbLevel.setProgress(0);
                });
                short[] ucap = rekamUcapan(ar, blok, lantai + 8.0, 3500, 750);
                if (!alurJalan) return;
                if (ucap.length < 1200 || Dsp.mfcc(ucap, 0, ucap.length).length < 6) {
                    ui.post(() -> tvInstruksi.setText("Tidak terdengar — ucapkan "
                            + "sekali lagi ya."));
                    continue;
                }
                take.add(ucap);
            }
            if (!alurJalan) return;
            if (take.size() < 3) {
                gagalAlur("Sapaan belum lengkap. Pendaftaran bisa diulang "
                        + "kapan saja dari layar ini.");
                return;
            }

            KunciSapa kunci = KunciSapa.bangun(take);
            if (kunci == null || !kunci.simpan(this)) {
                gagalAlur("Gagal menyimpan kunci sapaan.");
                return;
            }
            if (!profil.simpan(this)) {
                gagalAlur("Gagal menyimpan profil suara.");
                return;
            }
            AviBrain.pref(this).edit()
                    .putFloat("kal_lantai_db", lantai)
                    .putFloat("kal_bicara_db", profil.bicaraDb)
                    .putFloat("kal_pitch", profil.pitchMedian)
                    .putLong("kal_waktu", System.currentTimeMillis())
                    .apply();

            // ---- SELESAI: tersimpan otomatis, tanpa tombol ----
            ui.post(() -> {
                tvLangkah.setText("Selesai ✓");
                tvInstruksi.setText("Suara Anda sudah terdaftar. Rawat seperti "
                        + "PIN: jangan biarkan orang lain mendaftarkan suaranya "
                        + "di ponsel Anda.");
                tvFrasa.setVisibility(View.GONE);
                tvDots.setVisibility(View.GONE);
                tvUmpan.setVisibility(View.GONE);
                pbLevel.setProgress(100);
            });
            ui.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) tampilkanKunci();
            }, 1800);
        } catch (Throwable e) {
            gagalAlur("Pendaftaran terganggu: " + e.getClass().getSimpleName());
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) {}
                try { ar.release(); } catch (Exception ignored) {}
            }
        }
    }

    private String titikSapa(int terisi) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) s.append("  ");
            s.append(i < terisi ? "●" : "○");
        }
        return s.toString();
    }

    private void gagalAlur(final String pesan) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            tampilkanMenurutKeadaan();
            tvInstruksi.setText(pesan);
        });
    }

    // ======================= rekam satu ucapan =======================

    /**
     * Tunggu onset (maks 8 dtk) → rekam sampai hening 'heningMs' atau
     * batas 'maksMs'. Meter & nada dasar hidup di tvUmpan/pbLevel.
     */
    private short[] rekamUcapan(AudioRecord ar, short[] blok, double ambang,
                                long maksMs, long heningMs) throws Exception {
        long t0 = System.currentTimeMillis();
        int keras = 0;
        while (alurJalan && System.currentTimeMillis() - t0 < 8000) {
            int n = ar.read(blok, 0, blok.length);
            if (n <= 0) continue;
            double db = Dsp.db(blok, 0, n);
            pasangMeter(db);
            if (db > ambang) { if (++keras >= 3) break; } else keras = 0;
        }
        if (!alurJalan || keras < 3) return new short[0];

        ByteArrayOutputStream buf = new ByteArrayOutputStream(BLOK * 2 * 128);
        long mulai = System.currentTimeMillis();
        long suaraTerakhir = System.currentTimeMillis();
        int hitung = 0;
        while (alurJalan && System.currentTimeMillis() - mulai < maksMs) {
            int n = ar.read(blok, 0, blok.length);
            if (n <= 0) continue;
            for (int i = 0; i < n; i++) {
                int s = blok[i];
                buf.write(s & 0xFF);
                buf.write((s >> 8) & 0xFF);
            }
            long kini = System.currentTimeMillis();
            double db = Dsp.db(blok, 0, n);
            if (db > ambang - 2.0) suaraTerakhir = kini;
            if ((hitung++ & 15) == 0) {
                pasangMeter(db);
                tampilkanNada(blok);
            }
            if (kini - mulai > 900 && kini - suaraTerakhir > heningMs) break;
        }
        byte[] b = buf.toByteArray();
        short[] pcm = new short[b.length / 2];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        return pcm;
    }

    private void pasangMeter(final double db) {
        final int level = (int) Math.max(0, Math.min(100, (db + 60.0) / 50.0 * 100.0));
        ui.post(() -> pbLevel.setProgress(level));
    }

    /** Nada dasar sesaat (tiap ±0,5 dtk) — umpan balik "AVI benar-benar dengar". */
    private void tampilkanNada(short[] blok) {
        double[][] p = Dsp.pitch(blok, 0, blok.length);
        for (double[] frame : p) {
            if (frame[0] > 0) {
                final int hz = (int) Math.round(frame[0]);
                ui.post(() -> {
                    if (!alurJalan) return;
                    tvUmpan.setVisibility(View.VISIBLE);
                    tvUmpan.setText("nada ±" + hz + " Hz");
                });
                return;
            }
        }
    }

    // ========================== siklus hidup ==========================

    @Override
    protected void onPause() {
        // mikrofon tidak boleh hidup di latar — hentikan alur apa pun
        alurJalan = false;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!alurJalan) tampilkanMenurutKeadaan();
    }
}
