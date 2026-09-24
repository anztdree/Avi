package com.avi.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * OrbLayanan — ORB AVI YANG SUNGGUH MELAYANG DI ATAS APLIKASI LAIN.
 *
 * Latar belakang permintaan pemilik (2026-09-24): tahan tombol home
 * sebelumnya meluncurkan OrbitAssistActivity — terasa seperti "membuka
 * aplikasi AVI", bukan asisten. Sekarang activity itu hanya TRAMPOLIN:
 * memulai layanan ini lalu menutup diri, sehingga aplikasi yang sedang
 * dipakai TIDAK tertutup. Layanan ini menggambar jendela
 * SYSTEM_ALERT_WINDOW: orb kecil di pojok bawah + lembar obrolan
 * compact yang muncul DI ATAS aplikasi apa pun (ala chat head).
 *
 * Ketentuan privasi tetap sama: mikrofon hanya menyala saat lembar
 * obrolan terbuka (LiveEngine), mati total saat kembali ke orb.
 *
 * Gestur orb:   ketuk = buka lembar obrolan;  geser = pindah posisi;
 *               tahan ±0,6 dtk = AVI pamit, orb ditutup.
 * Lembar:       sentuhan DI LUAR kartu tetamewas ke aplikasi di bawah
 *               (jendela hanya sebesar kartunya); ✕ = kecilkan ke orb.
 */
public class OrbLayanan extends Service implements LiveEngine.Pendengar {

    public static final String AKSI_ORB  = "com.avi.assistant.LAYANGAN_ORB";
    public static final String AKSI_BUKA = "com.avi.assistant.LAYANGAN_BUKA";
    public static final String AKSI_TUTUP = "com.avi.assistant.LAYANGAN_TUTUP";

    private static final String SALURAN = "avi_layangan";
    private static final int NOTIF_ID = 20;

    /** Dipakai Pengaturan untuk status tanpa binding. */
    public static volatile boolean LAYANAN_HIDUP = false;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private LayoutInflater inflater;

    private View orbAkar;                       // gelembung kecil (bisa null)
    private WindowManager.LayoutParams lpOrb;

    private ViewGroup lembar;                   // lembar obrolan (bisa null)
    private WindowManager.LayoutParams lpLembar;
    private OrbView orbLembar, orbKecil;
    private TextView tvStatus, tvAnda, tvAvi;
    private ScrollView gulirPapan;              // papan pesan bersama
    private LinearLayout papanPesan;
    private View pemisahPapan;                  // garis antara riwayat & giliran hidup
    private boolean pemisahTerpasang;

    private LiveEngine mesin;
    private boolean mesinHidup = false;

    // ============================ siklus hidup ============================

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        inflater = LayoutInflater.from(this);
        LAYANAN_HIDUP = true;
        // panaskan TTS sejak orb muncul — ketuk gelembung tidak perlu
        // menunggu mesin suara bangun lagi (balasan terasa lebih cepat)
        LiveEngine.panaskanTts(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int bendera, int idAwal) {
        if (!Settings.canDrawOverlays(this)) {
            // izin dicabut di tengah jalan — tutup dengan rapi
            Toast.makeText(this, "Izin muncul di atas aplikasi dinonaktifkan — "
                    + "orb AVI ditutup.", Toast.LENGTH_LONG).show();
            matikanTotal();
            return START_NOT_STICKY;
        }
        mulaiLatarDepan();

        String aksi = intent != null ? intent.getAction() : AKSI_ORB;
        if (AKSI_TUTUP.equals(aksi)) {
            matikanTotal();
            return START_NOT_STICKY;
        }
        if (AKSI_BUKA.equals(aksi)) {
            bukaLembar();          // tahan home → langsung obrolan melayang
        } else {
            pasangOrb();           // hanya gelembung kecil
        }
        return START_NOT_STICKY;   // pemanggil ulang = tahan tombol home
    }

    /** Layanan latar depan WAJIB — dipanggil paling awal (aturan 5 detik). */
    private void mulaiLatarDepan() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel kanal = new NotificationChannel(SALURAN,
                    "AVI melayang", NotificationManager.IMPORTANCE_LOW);
            kanal.setDescription("Menjaga orb AVI tetap hidup di atas aplikasi");
            kanal.setShowBadge(false);
            nm.createNotificationChannel(kanal);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, SALURAN)
                : new Notification.Builder(this);
        Intent buka = new Intent(this, MainActivity.class);
        int piBendera = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piBendera |= android.app.PendingIntent.FLAG_IMMUTABLE;
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("AVI siap dipanggil")
                .setContentText("Ketuk orb untuk mengobrol di aplikasi mana pun.")
                .setContentIntent(android.app.PendingIntent.getActivity(
                        this, 0, buka, piBendera))
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, b.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, b.build());
        }
    }

    @Override
    public void onDestroy() {
        LAYANAN_HIDUP = false;
        hentikanMesin();
        lepasView(orbAkar); orbAkar = null;
        lepasView(lembar); lembar = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConfigurationChanged(Configuration cfgBaru) {
        super.onConfigurationChanged(cfgBaru);
        // layar berputar — klem posisi orb ke dalam batas baru
        if (orbAkar != null && lpOrb != null) {
            int lebar = getResources().getDisplayMetrics().widthPixels;
            int tinggi = getResources().getDisplayMetrics().heightPixels;
            lpOrb.x = klem(lpOrb.x, 0, lebar - dip(58));
            lpOrb.y = klem(lpOrb.y, 0, tinggi / 2);
            try { wm.updateViewLayout(orbAkar, lpOrb); } catch (Exception ignored) {}
        }
    }

    // ============================ orb kecil ============================

    private void pasangOrb() {
        if (orbAkar != null) return;                 // sudah terpasang
        if (lembar != null) return;                  // lembar terbuka — orb nanti
        lpOrb = paramOrbBaru();
        orbAkar = inflater.inflate(R.layout.layangan_orb, null);
        orbKecil = orbAkar.findViewById(R.id.orbMelayang);
        orbKecil.setWarnaOrb(0xFF38BDF8);
        orbAkar.setOnTouchListener(this::sentuhOrb);
        try { wm.addView(orbAkar, lpOrb); } catch (Exception e) {
            orbAkar = null;   // jendela gagal — biarkan layanan tenang
        }
    }

    private WindowManager.LayoutParams paramOrbBaru() {
        int tipe = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY   // API 26+
                : WindowManager.LayoutParams.TYPE_PHONE;               // API 24-25
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                tipe,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = dip(18);
        lp.y = dip(96);
        return lp;
    }

    /** Sentuh orb: geser = pindah; ketuk = buka lembar; tahan = pamit. */
    private boolean sentuhOrb(View v, MotionEvent ev) {
        float rawX = ev.getRawX(), rawY = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                orbSentuhAwal = rawX; orbSentuhAwalY = rawY;
                orbPosAwal = lpOrb.x; orbPosAwalY = lpOrb.y;
                orbGeser = false;
                ui.postDelayed(this::pamitOrb, 600);   // kandidat tahan-lama
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = rawX - orbSentuhAwal;
                float dy = rawY - orbSentuhAwalY;
                if (!orbGeser && Math.hypot(dx, dy) > dip(9)) {
                    orbGeser = true;
                    ui.removeCallbacks(this::pamitOrb);
                }
                if (orbGeser) {
                    int lebar = getResources().getDisplayMetrics().widthPixels;
                    int tinggi = getResources().getDisplayMetrics().heightPixels;
                    // gravity END: x tumbuh ke kiri; gravity BOTTOM: y tumbuh ke bawah
                    lpOrb.x = klem((int) (orbPosAwal - dx), 0, lebar - dip(58));
                    lpOrb.y = klem((int) (orbPosAwalY + dy), dip(24), tinggi / 2);
                    try { wm.updateViewLayout(orbAkar, lpOrb); } catch (Exception ignored) {}
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(this::pamitOrb);
                if (!orbGeser && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    bukaLembar();
                }
                return true;
        }
        return false;
    }
    private float orbSentuhAwal, orbSentuhAwalY;
    private int orbPosAwal, orbPosAwalY;
    private boolean orbGeser;

    /** Tahan lama → AVI pamit: orb ditutup total. */
    private void pamitOrb() {
        Toast.makeText(this, "Orb AVI ditutup — panggil lagi lewat tahan "
                + "tombol home.", Toast.LENGTH_SHORT).show();
        matikanTotal();
    }

    // ========================= lembar obrolan =========================

    private void bukaLembar() {
        if (lembar != null) return;
        lepasView(orbAkar); orbAkar = null; orbKecil = null;   // orb digantikan

        lpLembar = paramLembarBaru();
        lembar = (ViewGroup) inflater.inflate(R.layout.layangan_lembar, null);
        orbLembar = lembar.findViewById(R.id.orbSesi);
        tvStatus = lembar.findViewById(R.id.tvStatusSesi);
        tvAnda   = lembar.findViewById(R.id.tvAndaSesi);
        tvAvi    = lembar.findViewById(R.id.tvAviSesi);
        gulirPapan = lembar.findViewById(R.id.gulirPapan);
        papanPesan  = lembar.findViewById(R.id.papanPesan);
        orbLembar.setWarnaOrb(0xFF38BDF8);

        // SATU PAPAN PESAN: riwayat yang sama persis dengan aplikasi AVI
        PapanPesan.render(this, papanPesan, gulirPapan, true);
        pemisahTerpasang = false;

        lembar.findViewById(R.id.btnTutupSesi).setOnClickListener(v -> tutupLembar());
        orbLembar.setOnClickListener(v -> {
            if (mesin == null) return;
            int k = orbLembar.getKeadaan();
            if (k == OrbView.BICARA) mesin.potongTts();       // barge-in
            else if (k == OrbView.SIAP) mesin.dengarkanLagi();
        });

        try { wm.addView(lembar, lpLembar); } catch (Exception e) {
            lembar = null; pasangOrb(); return;
        }

        // animasi masuk identik sesi Orbit: lembar naik + orb melebar
        lembar.setTranslationY(dip(160));
        lembar.animate().translationY(0f).setDuration(280L)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        orbLembar.setScaleX(0.82f); orbLembar.setScaleY(0.82f);
        orbLembar.animate().scaleX(1f).scaleY(1f).setDuration(360L)
                .setInterpolator(new OvershootInterpolator(1.05f)).start();

        nyalakanMesin();
    }

    private WindowManager.LayoutParams paramLembarBaru() {
        int tipe = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                tipe,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }

    /** ✕ / AVI tidur → kecilkan kembali ke orb. */
    private void tutupLembar() {
        hentikanMesin();
        lepasView(lembar); lembar = null;
        orbLembar = null; tvStatus = null; tvAnda = null; tvAvi = null;
        gulirPapan = null; papanPesan = null; pemisahPapan = null;
        pasangOrb();
    }

    /** Pamit total: orb + layanan dimatikan (panggil ulang via home). */
    private void matikanTotal() {
        hentikanMesin();
        lepasView(lembar); lembar = null;
        lepasView(orbAkar); orbAkar = null;
        stopForeground(true);
        stopSelf();
    }

    private void lepasView(View v) {
        if (v == null || wm == null) return;
        try { wm.removeView(v); } catch (Exception ignored) {}
    }

    private int dip(int nilai) {
        return Math.round(nilai * getResources().getDisplayMetrics().density);
    }

    private static int klem(int v, int min, int maks) {
        return v < min ? min : (v > maks ? maks : v);
    }

    // ============================= mesin ==============================

    private void nyalakanMesin() {
        if (mesinHidup) return;
        mesin = new LiveEngine(getApplicationContext(), this);
        mesinHidup = true;
        if (mesin.izinMicAda()) {
            mesin.mulai();
        } else {
            status("Izin mikrofon belum ada — buka aplikasi AVI sekali dulu.");
        }
    }

    private void hentikanMesin() {
        if (mesin != null) { mesin.hentikan(); mesin = null; }
        mesinHidup = false;
    }

    // ================= peristiwa mesin (thread utama) =================

    @Override public void keadaan(int k) {
        if (orbLembar != null) orbLembar.setKeadaan(k);
        if (orbKecil != null) orbKecil.setKeadaan(k);
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
        // garis halus pemisah riwayat lama vs giliran yang sedang hidup
        if (!pemisahTerpasang && papanPesan != null) {
            pemisahPapan = PapanPesan.pemisahHidup(this, true);
            papanPesan.addView(pemisahPapan);
            pemisahTerpasang = true;
            if (gulirPapan != null) gulirPapan.post(() ->
                    gulirPapan.fullScroll(View.FOCUS_DOWN));
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

    /** Giliran selesai — pasangan pertanyaan+jawaban sudah tersimpan ke
     *  riwayat bersama oleh AviBrain.tanyaStream → papan digambar ulang
     *  supaya pesan baru menyatu dengan riwayat aplikasi. */
    @Override public void giliranBeres() {
        if (papanPesan != null) {
            PapanPesan.render(this, papanPesan, gulirPapan, true);
        }
        if (pemisahPapan != null && papanPesan != null) {
            papanPesan.removeView(pemisahPapan);   // pemisah baru nanti dipasang lagi
        }
        pemisahTerpasang = false;
        pemisahPapan = null;
        // giliran sudah masuk papan — baris hidup tidak perlu dobel
        if (tvAnda != null) tvAnda.setVisibility(View.GONE);
        if (tvAvi != null) tvAvi.setVisibility(View.GONE);
    }

    @Override public void rms(float rmsdb) {
        if (orbLembar != null) orbLembar.setRms(rmsdb);
    }

    @Override public void tetidur() {
        tutupLembar();     // AVI tidur → kembali jadi orb kecil
    }
}
