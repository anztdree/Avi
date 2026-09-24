package com.avi.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Mesin Mode Live AVI — inti percakapan bebas mengalir ala Gemini Live.
 * Bicara → jawab → bicara lagi, tanpa menekan apa pun.
 *
 * Loop: MENDENGARKAN (STT + transkrip real-time) → BERPIKIR (AI streaming)
 * → BICARA (teks muncul bertahap + TTS per kalimat) → jeda ±300 ms
 * → MENDENGARKAN lagi.
 *
 * POLA IDLE SIRI/GA (koreksi pemilik): setelah beberapa detik tanpa
 * aktivitas, AVI TIDUR — sesi ditutup dengan sopan, bukan terus-menerus
 * mendengarkan. Hening ±5 detik (batas internal recognizer) setelah
 * giliran terakhir = sinyal tidur. Sebelum tidur AVI pamit singkat bila
 * sempat terjadi percakapan; kalau belum ada ucapan sama sekali, langsung
 * tidur tanpa suara.
 *
 * Anti-echo (riset Coval 2026, arsitektur client-only): mikrofon TIDAK
 * PERNAH menyala saat TTS berbicara; dibuka lagi ±300 ms setelah kalimat
 * terakhir selesai agar gema meluruh dulu.
 * Barge-in pragmatis: sentuh orb saat AVI bicara = langsung diam & mendengar.
 * Seluruh ucapan & jawaban tersimpan sebagai transkrip di riwayat chat
 * (AviBrain.tanyaStream yang menyimpan).
 *
 * Mesin ini netral UI: dipakai LiveActivity (layar penuh) dan AviSession
 * (overlay transparan asisten perangkat). Host hanya menerima peristiwa
 * lewat Pendengar — semua callback datang di thread utama.
 */
public class LiveEngine {

    /** Peristiwa mesin untuk host UI — semua dipanggil di thread utama. */
    public interface Pendengar {
        void keadaan(int keadaanOrb);          // lihat OrbView.SIAP dst.
        void status(String teks);              // baris status kecil
        void transkripAnda(String teks);       // ucapan user (parsial/final)
        void teksAvi(String teks);             // jawaban AVI (mengalir)
        void rms(float rmsdb);                 // kerasnya suara (animasi orb)
        void tetidur();                        // idle → host menutup sesi
    }

    private static final String ID_PAMIT = "pamit";
    private static final String TEKS_PAMIT =
            "Baik, AVI pamit dulu. Panggil AVI lagi kapan saja.";

    private final Context ctx;
    private final Pendengar p;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer pengenal;
    private TextToSpeech tts;
    private boolean ttsSiap = false;
    private boolean hidup = false;
    private boolean sudahTidur = false;
    private boolean punyaPercakapan = false;
    private int salahDengar = 0;
    private int keadaan = OrbView.SIAP;

    // sinkronisasi loop: dua jalur harus beres (stream AI + antrean TTS)
    private int sesi = 0;                 // naik tiap giliran baru / barge-in
    private boolean streamSelesai = false;
    private boolean ttsSelesai = true;
    private int nomorUcap = 0;
    private String idUcapTerakhir = null;

    private final StringBuilder aliran = new StringBuilder(); // teks bersih sejauh ini
    private int sudahDiucap = 0;                              // batas index terucap

    public LiveEngine(Context context, Pendengar pendengar) {
        ctx = context.getApplicationContext();
        p = pendengar;
    }

    public boolean izinMicAda() {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ============================ siklus hidup ============================

    public void mulai() {
        hidup = true;
        siapkanTts();
        siapkanPengenal();
        if (izinMicAda()) {
            jadwalMendengarkan(500);
        } else {
            p.status("Izin mikrofon belum ada — berikan lewat Pengaturan ponsel.");
        }
    }

    public void hentikan() {
        hidup = false;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
        if (pengenal != null) {
            try { pengenal.destroy(); } catch (Exception ignored) {}
            pengenal = null;
        }
    }

    public int getKeadaan() { return keadaan; }

    private void setKeadaan(int k) {
        keadaan = k;
        p.keadaan(k);
    }

    // ============================ TTS (bicara) ============================

    private void siapkanTts() {
        tts = new TextToSpeech(ctx, ok -> {
            if (!hidup) return;
            ttsSiap = ok == TextToSpeech.SUCCESS;
            if (!ttsSiap) return;
            try { tts.setLanguage(new Locale("id", "ID")); } catch (Exception ignored) {}
            tts.setSpeechRate(AviBrain.pref(ctx).getInt("tts_rate", 100) / 100f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { ucapBeres(id); }
                @Override public void onError(String id) { ucapBeres(id); }
                @Override public void onError(String id, int kode) { ucapBeres(id); }
            });
        });
    }

    private void ucapkan(String potongan) {
        String s = potongan.trim();
        if (s.isEmpty() || !ttsSiap || tts == null) return;
        nomorUcap++;
        idUcapTerakhir = "live" + sesi + "_" + nomorUcap;
        ttsSelesai = false;
        tts.speak(s, TextToSpeech.QUEUE_ADD, null, idUcapTerakhir);
    }

    private void ucapBeres(String id) {
        handler.post(() -> {
            if (!hidup) return;
            if (ID_PAMIT.equals(id)) { tidurSekarang(); return; }
            if (id == null || !id.equals(idUcapTerakhir)) return;
            ttsSelesai = true;
            cobaLanjutDengar();
        });
    }

    /** Kedua jalur (stream AI + TTS) sudah beres → buka mikrofon lagi. */
    private void cobaLanjutDengar() {
        if (!hidup || sudahTidur) return;
        if (streamSelesai && ttsSelesai && keadaan == OrbView.BICARA) {
            jadwalMendengarkan(300);   // beri jeda agar gema TTS meluruh
        }
    }

    /** Barge-in: host menyentuh orb saat AVI bicara. */
    public void potongTts() {
        if (!hidup || sudahTidur) return;
        sesi++;                        // buang seluruh callback giliran lama
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        streamSelesai = true;
        ttsSelesai = true;
        p.transkripAnda("");
        p.teksAvi("");
        p.status("Dipotong — silakan bicara, " + AviBrain.namaPemilik(ctx));
        jadwalMendengarkan(120);
    }

    /** Lanjut mendengarkan bila sebelumnya dijeda (orb disentuh saat SIAP). */
    public void dengarkanLagi() {
        if (hidup && !sudahTidur && keadaan == OrbView.SIAP) mulaiMendengarkan();
    }

    // ====================== streaming AI (berpikir) ======================

    private void ajukanKeAi(String teks) {
        punyaPercakapan = true;
        sesi++;
        final int sesiIni = sesi;
        streamSelesai = false;
        ttsSelesai = true;
        nomorUcap = 0;
        idUcapTerakhir = null;
        aliran.setLength(0);
        sudahDiucap = 0;

        setKeadaan(OrbView.BERPIKIR);
        p.status("AVI sedang berpikir…");
        p.teksAvi("");

        AviBrain.tanyaStream(ctx, teks, new AviBrain.StreamBalas() {
            @Override public void token(String teksSejauhIni) {
                if (!hidup || sesi != sesiIni) return;
                terimaToken(teksSejauhIni);
            }
            @Override public void selesai(String teksAkhir) {
                if (!hidup || sesi != sesiIni) return;
                streamBeres(teksAkhir);
            }
        });
    }

    private void terimaToken(String teksPenuh) {
        String bersih = AviBrain.buangTagAksi(teksPenuh);
        aliran.setLength(0);
        aliran.append(bersih);
        p.teksAvi(bersih);

        if (keadaan != OrbView.BICARA) {
            setKeadaan(OrbView.BICARA);
            p.status("AVI berbicara — sentuh orb untuk memotong");
        }

        // TTS per kalimat: potong di tanda baca akhir (min 40 karakter antar potong)
        while (true) {
            int potong = cariPotongan(aliran, sudahDiucap);
            if (potong < 0) break;
            String pot = aliran.substring(sudahDiucap, potong).trim();
            if (!pot.isEmpty()) ucapkan(pot);
            sudahDiucap = potong;
        }
    }

    private void streamBeres(String teksAkhir) {
        streamSelesai = true;
        String tampil = teksAkhir == null ? "" : teksAkhir.trim();
        if (tampil.isEmpty()) {
            tampil = "(balasan kosong dari model — coba ulangi, "
                    + AviBrain.namaPemilik(ctx) + ".)";
        }
        p.teksAvi(tampil);

        if (!ttsSiap) {                 // tanpa mesin TTS: lanjut langsung
            jadwalMendengarkan(250);
            return;
        }
        String sisa = aliran.length() > sudahDiucap
                ? aliran.substring(sudahDiucap).trim() : "";
        sudahDiucap = aliran.length();
        if (sisa.isEmpty()) {
            if (ttsSelesai) jadwalMendengarkan(300);   // antrean TTS sudah licin
            // else: tunggu onDone kalimat terakhir → cobaLanjutDengar()
        } else {
            ucapkan(sisa);             // onDone-nya akan memicu lanjut dengar
        }
    }

    /** Cari akhir kalimat setelah minimal 40 karakter dari posisi 'dari'. */
    private static int cariPotongan(StringBuilder s, int dari) {
        int mulaiCari = Math.max(dari, 0) + 40;
        for (int i = mulaiCari; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                if (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1))) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    // ==================== STT (mendengarkan) ====================

    private void siapkanPengenal() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            p.status("Pengenalan suara tidak tersedia di ponsel ini.");
            return;
        }
        pengenal = SpeechRecognizer.createSpeechRecognizer(ctx);
        pengenal.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { salahDengar = 0; }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) { if (hidup) p.rms(rmsdB); }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override public void onPartialResults(Bundle parsial) {
                if (!hidup || keadaan != OrbView.MENDENGARKAN) return;
                ArrayList<String> daftar = parsial
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (daftar != null && !daftar.isEmpty()
                        && !daftar.get(0).trim().isEmpty()) {
                    p.transkripAnda(daftar.get(0));    // transkrip real-time
                }
            }

            @Override public void onResults(Bundle hasil) {
                if (!hidup) return;
                ArrayList<String> daftar = hasil
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String teks = (daftar == null || daftar.isEmpty())
                        ? "" : daftar.get(0).trim();
                if (teks.isEmpty()) { jadwalMendengarkan(150); return; }
                p.transkripAnda(teks);
                ajukanKeAi(teks);
            }

            @Override public void onError(int error) {
                if (!hidup || sudahTidur) return;
                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // IDLE — pola Siri/GA: beberapa detik tanpa aktivitas, tidur
                    if (!punyaPercakapan) { tidurSekarang(); return; }
                    mulaiPamit();
                    return;
                }
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    bangunUlangPengenal(400);
                    return;
                }
                salahDengar++;
                if (salahDengar >= 12) {
                    // mikrofon memang bermasalah — tidur, jangan berputar tanpa akhir
                    p.status("Mikrofon bermasalah — AVI tidur dulu, "
                            + AviBrain.namaPemilik(ctx) + ".");
                    tidurSekarang();
                } else if (salahDengar >= 5) {
                    // jeda aman setelah galat beruntun, lalu coba lagi otomatis
                    p.status("AVI masih di sini — mendengarkan lagi sebentar lagi, "
                            + AviBrain.namaPemilik(ctx) + ".");
                    jadwalMendengarkan(1500);
                } else {
                    bangunUlangPengenal(600);
                }
            }
        });
    }

    private void mulaiMendengarkan() {
        if (!hidup || sudahTidur) return;
        if (pengenal == null) siapkanPengenal();
        if (pengenal == null) return;
        setKeadaan(OrbView.MENDENGARKAN);
        p.status("Mendengarkan… bicara saja, " + AviBrain.namaPemilik(ctx));
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            pengenal.startListening(it);
        } catch (Exception e) {
            bangunUlangPengenal(500);
        }
    }

    private final Runnable pengingatDengar = this::mulaiMendengarkan;

    private void jadwalMendengarkan(int delayMs) {
        handler.removeCallbacks(pengingatDengar);
        handler.postDelayed(pengingatDengar, delayMs);
    }

    private void bangunUlangPengenal(int delayMs) {
        handler.postDelayed(() -> {
            if (!hidup || sudahTidur) return;
            if (pengenal != null) {
                try { pengenal.destroy(); } catch (Exception ignored) {}
                pengenal = null;
            }
            siapkanPengenal();
            mulaiMendengarkan();
        }, delayMs);
    }

    // ==================== idle → tidur (pola Siri/GA) ====================

    /** Hening ±5 detik setelah percakapan: pamit singkat, lalu tidur. */
    private void mulaiPamit() {
        if (sudahTidur) return;
        setKeadaan(OrbView.SIAP);
        p.status("AVI istirahat — sesi berakhir karena hening.");
        p.transkripAnda("");
        if (ttsSiap && tts != null) {
            tts.speak(TEKS_PAMIT, TextToSpeech.QUEUE_ADD, null, ID_PAMIT);
        }
        // pengaman: meski onDone TTS tidak datang, tetap tidur tepat waktu
        handler.postDelayed(this::tidurSekarang, 4500);
    }

    private void tidurSekarang() {
        if (sudahTidur) return;
        sudahTidur = true;
        p.tetidur();      // host yang menutup UI; host memanggil hentikan()
    }
}
