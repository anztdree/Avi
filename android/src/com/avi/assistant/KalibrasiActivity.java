package com.avi.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * KALIBRASI SUARA AVI — wizard agar asisten mengenali suara & perintah
 * pemiliknya sendiri (permintaan pemilik, 2026-09-24). Tiga langkah:
 *
 *  1. KENYARINGAN — AudioRecord murni (tanpa recognizer, mikrofon tidak
 *     boleh diperebutkan): ±4,5 detik; 0,8 detik pertama = lantai ruangan,
 *     sisanya = suara pemilik; level meter bergerak langsung. Hasilnya
 *     verdict praktis (jelas / agak pelan / terlalu pelan).
 *  2. UJI PERINTAH — tiga perintah nyata (sapaan, pertanyaan, aksi) lewat
 *     SpeechRecognizer Android; tampil PERSIS teks yang didengar ponsel
 *     + lulus/tidak per perintah → skor N/3. Tidak ada perintah yang
 *     dieksekusi di sini — hanya mengukur pengenalan.
 *  3. UJI BEBAS — ucapkan apa saja, lihat transkripnya.
 *
 * Hasil kalibrasi (lantai dB, kenyaringan bicara dB, skor, waktu) disimpan
 * lokal di SharedPreferences ponsel — tanpa unggah, tanpa server. Teks
 * yang dipakai pembanding sengaja longgar: pengenal Android sering
 * menulis nama & istilah dengan ejaan berbeda (mis. "AVI" → "abi"/"af").
 */
public class KalibrasiActivity extends Activity {

    /** Perintah uji: [frasa yang diucapkan, keterangan pendek]. */
    private static final String[][] UJI = {
            {"Hai AVI", "sapaan panggilan asisten"},
            {"Jam berapa sekarang", "pertanyaan harian"},
            {"Nyalakan senter", "perintah aksi perangkat"}
    };
    private static final long DURASI_UKUR_MS = 4500;
    private static final long LANTAI_MS = 800;

    private ProgressBar pbLevel;
    private TextView tvHasil1, tvTarget, tvFrasa, tvHasil2, tvHasil3, tvRingkasan;
    private Button bLangkah1, bLangkah2, bUlangi, bLangkah3, bSimpanKal;

    private SpeechRecognizer pengenal;
    private boolean sedangDengar = false;
    private boolean modeUjiAktif = false;   // true = uji perintah, false = uji bebas

    // ===== langkah 1 =====
    private boolean sedangUkur = false;
    private volatile boolean batalkanUkur = false;
    private boolean langkah1Selesai = false;
    private float lantaiDb = -60f, bicaraDb = -60f;

    // ===== langkah 2 =====
    private int idxUji = 0;
    private final boolean[] lulusUji = new boolean[UJI.length];
    private boolean ujiSelesai = false;

    private Runnable tertunda;   // aksi setelah izin mikrofon diberikan

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kalibrasi);

        pbLevel    = findViewById(R.id.pbLevel);
        tvHasil1   = findViewById(R.id.tvHasil1);
        tvTarget   = findViewById(R.id.tvTarget);
        tvFrasa    = findViewById(R.id.tvFrasa);
        tvHasil2   = findViewById(R.id.tvHasil2);
        tvHasil3   = findViewById(R.id.tvHasil3);
        tvRingkasan= findViewById(R.id.tvRingkasan);
        bLangkah1  = findViewById(R.id.bLangkah1);
        bLangkah2  = findViewById(R.id.bLangkah2);
        bUlangi    = findViewById(R.id.bUlangi);
        bLangkah3  = findViewById(R.id.bLangkah3);
        bSimpanKal = findViewById(R.id.bSimpanKal);

        bLangkah1.setOnClickListener(v -> {
            if (sedangUkur) return;
            denganIzin(this::mulaiUkur);
        });
        bLangkah2.setOnClickListener(v -> {
            if (sedangUkur || sedangDengar) return;
            denganIzin(() -> mulaiDengar(true));
        });
        bUlangi.setOnClickListener(v -> {
            if (sedangUkur || sedangDengar) return;
            idxUji = 0;
            ujiSelesai = false;
            for (int i = 0; i < lulusUji.length; i++) lulusUji[i] = false;
            refreshUji();
            perbaruiRingkasan();
        });
        bLangkah3.setOnClickListener(v -> {
            if (sedangUkur || sedangDengar) return;
            denganIzin(() -> mulaiDengar(false));
        });
        bSimpanKal.setOnClickListener(v -> simpan());

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvHasil2.setText("Pengenalan suara tidak tersedia di ponsel ini — "
                    + "Langkah 2 & 3 tidak bisa dijalankan.");
            bLangkah2.setEnabled(false);
            bUlangi.setEnabled(false);
            bLangkah3.setEnabled(false);
        }

        pengenal = SpeechRecognizer.createSpeechRecognizer(this);
        pengenal.setRecognitionListener(new recognitionListener());

        refreshUji();
        perbaruiRingkasan();
    }

    // ============================ izin ============================

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
            Toast.makeText(this, "Izin mikrofon ditolak — kalibrasi butuh "
                    + "mikrofon untuk mengenali suara Anda.", Toast.LENGTH_LONG).show();
        }
    }

    // ================== LANGKAH 1: kenyaringan ==================

    private void mulaiUkur() {
        if (sedangUkur) return;
        sedangUkur = true;
        batalkanUkur = false;
        bLangkah1.setEnabled(false);
        bLangkah2.setEnabled(false);
        bUlangi.setEnabled(false);
        bLangkah3.setEnabled(false);
        bLangkah1.setText("Mengukur…");
        tvHasil1.setText("Mendengarkan — baca kalimatnya sekarang…");
        pbLevel.setProgress(0);

        final int laju = 44100;
        new Thread(() -> {
            int minBuf = AudioRecord.getMinBufferSize(laju,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord ar = null;
            try {
                ar = new AudioRecord(MediaRecorder.AudioSource.MIC, laju,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 8192));
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                    selesaiUkurGagal("Mikrofon tidak bisa dibuka — tutup aplikasi "
                            + "lain yang memakai mikrofon lalu coba lagi.");
                    return;
                }
                ar.startRecording();
                short[] blok = new short[2048];
                long t0 = System.currentTimeMillis();
                double jumlahLantai = 0;
                int nLantai = 0;
                List<Double> suara = new ArrayList<>();

                while (true) {
                    long t = System.currentTimeMillis() - t0;
                    if (batalkanUkur || t >= DURASI_UKUR_MS) break;
                    int n = ar.read(blok, 0, blok.length);
                    if (n <= 0) continue;
                    double jumlah = 0;
                    for (int i = 0; i < n; i++) {
                        double v = blok[i] / 32768.0;
                        jumlah += v * v;
                    }
                    double db = 20.0 * Math.log10(Math.sqrt(jumlah / n) + 1e-9);
                    if (t < LANTAI_MS) { jumlahLantai += db; nLantai++; }
                    else suara.add(db);
                    final int prog = (int) Math.min(100, t * 100 / DURASI_UKUR_MS);
                    final int level = levelDari(db);
                    runOnUiThread(() -> {
                        if (sedangUkur) {
                            pbLevel.setProgress(prog);
                            pbLevel.setSecondaryProgress(level);
                        }
                    });
                }
                try { ar.stop(); } catch (Exception ignored) {}

                if (batalkanUkur) {
                    runOnUiThread(this::resetTombolUkur);
                    return;
                }
                lantaiDb = nLantai > 0 ? (float) (jumlahLantai / nLantai) : -60f;
                bicaraDb = hitungBicara(suara);
                runOnUiThread(this::tampilkanHasilUkur);
            } catch (Exception e) {
                selesaiUkurGagal("Pengukuran gagal: " + e.getMessage());
            } finally {
                if (ar != null) { try { ar.release(); } catch (Exception ignored) {} }
            }
        }, "kalibrasi-ukur").start();
    }

    /** Kenyaringan suara = persentil-85 dari dBFS per blok (anti outlier). */
    private float hitungBicara(List<Double> suara) {
        if (suara.isEmpty()) return -60f;
        Collections.sort(suara);
        int i = (int) Math.floor(suara.size() * 0.85);
        if (i >= suara.size()) i = suara.size() - 1;
        return (float) (double) suara.get(i);
    }

    private int levelDari(double db) {
        double level = (db + 60.0) / 45.0 * 100.0;   // -60..-15 dBFS → 0..100
        return (int) Math.max(0, Math.min(100, level));
    }

    private void tampilkanHasilUkur() {
        sedangUkur = false;
        resetTombolUkur();
        pbLevel.setProgress(100);
        String verdict;
        if (bicaraDb - lantaiDb < 6f) {
            verdict = "Suara Anda hampir tidak terdengar — bicara lebih keras "
                    + "atau dekatkan ponsel, lalu ulangi.";
        } else if (bicaraDb > -22f) {
            verdict = "Sangat jelas. Kondisi ideal — AVI akan mudah mendengar Anda.";
        } else if (bicaraDb > -28f) {
            verdict = "Bagus. Kenyaringan ini nyaman untuk AVI.";
        } else if (bicaraDb > -34f) {
            verdict = "Agak pelan — masih bisa, tapi dekatkan ponsel saat bicara.";
        } else {
            verdict = "Terlalu pelan — saran kerasnya suara atau dekatkan ponsel.";
        }
        tvHasil1.setText(String.format(Locale.US,
                "Kenyaringan suara: %.0f dB (lantai ruangan %.0f dB). %s",
                bicaraDb, lantaiDb, verdict));
        langkah1Selesai = true;
        perbaruiRingkasan();
    }

    private void selesaiUkurGagal(String pesan) {
        sedangUkur = false;
        runOnUiThread(() -> {
            resetTombolUkur();
            tvHasil1.setText(pesan);
        });
    }

    private void resetTombolUkur() {
        bLangkah1.setEnabled(true);
        bLangkah1.setText("Ukur ulang suara");
        boolean pengenalOk = SpeechRecognizer.isRecognitionAvailable(this);
        bLangkah2.setEnabled(pengenalOk);
        bUlangi.setEnabled(pengenalOk);
        bLangkah3.setEnabled(pengenalOk);
    }

    // ================== LANGKAH 2 & 3: recognizer ==================

    private void mulaiDengar(boolean modeUji) {
        if (sedangDengar) return;
        sedangDengar = true;
        modeUjiAktif = modeUji;
        bLangkah2.setEnabled(false);
        bUlangi.setEnabled(false);
        bLangkah3.setEnabled(false);
        bSimpanKal.setEnabled(false);

        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        if (modeUji) {
            tvHasil2.setText("Mendengarkan… ucapkan: \u201C" + UJI[idxUji][0] + "\u201D");
        } else {
            tvHasil3.setText("Mendengarkan… ucapkan apa saja.");
        }
        try {
            pengenal.startListening(it);
        } catch (Exception e) {
            sedangDengar = false;
            aktifkanTombolDengar();
            String pesan = "Pengenal tidak bisa dijalankan — coba lagi sebentar.";
            if (modeUji) tvHasil2.setText(pesan); else tvHasil3.setText(pesan);
        }
    }

    private void aktifkanTombolDengar() {
        bLangkah2.setEnabled(true);
        bUlangi.setEnabled(true);
        bLangkah3.setEnabled(true);
        perbaruiRingkasan();   // ikut mengaktifkan/menonaktifkan Simpan
    }

    private class recognitionListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle p) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override public void onPartialResults(Bundle parsial) {
            ArrayList<String> daftar = parsial
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (daftar == null || daftar.isEmpty()) return;
            String teks = daftar.get(0);
            if (teks == null || teks.trim().isEmpty()) return;
            // transkrip langsung: pemilik melihat pengenal "mengikuti" ucapannya
            if (modeUjiAktif) {
                tvHasil2.setText("Terbaca: \u201C" + teks + "\u201D");
            } else {
                tvHasil3.setText("Terbaca: \u201C" + teks + "\u201D");
            }
        }

        @Override public void onResults(Bundle hasil) {
            sedangDengar = false;
            aktifkanTombolDengar();
            ArrayList<String> daftar = hasil
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String teks = (daftar == null || daftar.isEmpty())
                    ? "" : daftar.get(0).trim();

            if (teks.isEmpty()) {
                tvHasil2.setText("Tidak terdengar apa pun — coba lagi lebih dekat "
                        + "atau lebih keras, " + AviBrain.namaPemilik(KalibrasiActivity.this) + ".");
                return;
            }

            if (!modeUjiAktif) {
                tvHasil3.setText("Terbaca: \u201C" + teks + "\u201D");
                return;
            }

            boolean lulus = lulusUjian(idxUji, teks);
            lulusUji[idxUji] = lulus;
            String verdict = lulus
                    ? "✓ Lulus — ponsel mengenali perintah Anda."
                    : "✗ Tidak lulus — yang terbaca: \u201C" + teks
                      + "\u201D. Coba lebih jelas (sentuh Ulangi semua untuk mengulang).";
            tvHasil2.setText("Yang didengar ponsel: \u201C" + teks + "\u201D\n" + verdict);

            if (idxUji < UJI.length - 1) {
                idxUji++;
                refreshUji();
            } else {
                ujiSelesai = true;
                refreshUji();
            }
            perbaruiRingkasan();
        }

        @Override public void onError(int error) {
            sedangDengar = false;
            aktifkanTombolDengar();
            String pesan;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    pesan = "Tidak terdengar — coba lagi lebih dekat atau lebih keras.";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    pesan = "Izin mikrofon belum ada — sentuh tombol lagi untuk meminta.";
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    pesan = "Pengenal sibuk — tunggu sebentar lalu coba lagi.";
                    break;
                default:
                    pesan = "Pengenalan terganggu (kode " + error + ") — coba lagi.";
            }
            if (!modeUjiAktif) {
                tvHasil3.setText(pesan);
            } else {
                tvHasil2.setText(pesan);
            }
        }
    }

    /** Pembanding longgar — pengenal Android sering menulis dengan ejaan lain. */
    private boolean lulusUjian(int i, String dengar) {
        String d = dengar.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ");
        switch (i) {
            case 0:   // "Hai AVI" — bisa tertulis avi/abi/af/abdi/api…
                for (String w : d.split("\\s+")) {
                    if (w.startsWith("av") || w.startsWith("ab")
                            || w.startsWith("af") || w.equals("ai")) return true;
                }
                return false;
            case 1:   // "Jam berapa sekarang"
                return d.contains("jam");
            default:  // "Nyalakan senter"
                return d.contains("sent") || d.contains("lenter");
        }
    }

    private void refreshUji() {
        if (ujiSelesai) {
            int skor = jumlahLulus();
            tvTarget.setText("Selesai — skor perintah Anda: " + skor + " dari "
                    + UJI.length + (skor == UJI.length
                        ? ". Sempurna, " + AviBrain.namaPemilik(this) + "."
                        : ". Skor penuh bagus, tapi N/3 pun tetap tersimpan."));
            tvFrasa.setText("Perintah mana pun bisa diulang — sentuh \u201CUlangi semua\u201D.");
        } else {
            tvTarget.setText("Perintah " + (idxUji + 1) + " dari " + UJI.length
                    + " (" + UJI[idxUji][1] + ") — ucapkan dengan jelas:");
            tvFrasa.setText("\u201C" + UJI[idxUji][0] + "\u201D");
        }
    }

    private int jumlahLulus() {
        int n = 0;
        for (boolean b : lulusUji) if (b) n++;
        return n;
    }

    // ====================== ringkasan & simpan ======================

    private void perbaruiRingkasan() {
        boolean bolehSimpan = langkah1Selesai && ujiSelesai;
        bSimpanKal.setEnabled(bolehSimpan);
        if (!langkah1Selesai) {
            tvRingkasan.setText("Selesaikan Langkah 1 (kenyaringan) dan Langkah 2 "
                    + "(uji perintah) untuk mengisi ringkasan.");
        } else if (!ujiSelesai) {
            tvRingkasan.setText(String.format(Locale.US,
                    "Kenyaringan: %.0f dB ✓ — lanjut ke uji perintah (Langkah 2).",
                    bicaraDb));
        } else {
            tvRingkasan.setText(String.format(Locale.US,
                    "Kenyaringan suara: %.0f dB • perintah lulus: %d/%d. "
                    + "Bagus — sentuh Simpan agar AVI memakai profil Anda.",
                    bicaraDb, jumlahLulus(), UJI.length));
        }
    }

    private void simpan() {
        if (!langkah1Selesai || !ujiSelesai) return;
        AviBrain.pref(this).edit()
                .putFloat("kal_lantai_db", lantaiDb)
                .putFloat("kal_bicara_db", bicaraDb)
                .putInt("kal_skor", jumlahLulus())
                .putLong("kal_waktu", System.currentTimeMillis())
                .apply();
        Toast.makeText(this, "Kalibrasi suara tersimpan ✓", Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onPause() {
        // mikrofon tidak boleh hidup di latar — hentikan apa pun yang berjalan
        if (sedangDengar && pengenal != null) {
            try { pengenal.cancel(); } catch (Exception ignored) {}
            sedangDengar = false;
            aktifkanTombolDengar();
        }
        if (sedangUkur) {
            batalkanUkur = true;   // thread ukur berhenti & melepas mikrofon
            tvHasil1.postDelayed(() -> {
                if (!sedangUkur) resetTombolUkur();
            }, 600);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (pengenal != null) {
            try { pengenal.destroy(); } catch (Exception ignored) {}
            pengenal = null;
        }
        super.onDestroy();
    }
}
