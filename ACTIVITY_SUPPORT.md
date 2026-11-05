# Aktivitas dan olahraga

Tombol **Sync semua data** mengambil daftar file hari ini dan riwayat, kemudian mengunduh file yang didukung. Sambungkan jam melalui **Connect** terlebih dahulu. Setiap file diperiksa CRC32, padding, versi, dan batas payload sebelum disimpan. Nilai dengan validity bit kosong disimpan sebagai `null`, bukan angka nol.

## Data yang didukung

- Ringkasan harian versi 3–5: langkah, kalori aktif, HR istirahat/rata-rata/min/maks, rata-rata SpO₂/stres, dan jam berdiri bila ditandai valid oleh jam.
- Pengukuran manual versi 1–2: HR, SpO₂, dan stres. Versi 1 menerima header RW3 `e0` dan header `ff`. Suhu dan tipe pengukuran lain belum didukung.
- Ringkasan keluarga olahraga freestyle (subtipe file 8), versi 5/7/8/9/10: waktu mulai/selesai, durasi aktif, kalori aktif, HR rata-rata/min/maks, dan kode jenis olahraga. Kode yang tidak dikenal tetap ditampilkan sebagai nomor. GPS/rute, pace, jarak, dan keluarga file olahraga lain belum didukung.
- Tidur tetap memakai parser tidur yang terpisah. File olahraga tidak membuat sesi tidur.

Dashboard menampilkan ringkasan harian terakhir beserta tanggalnya, pengukuran manual terakhir untuk tiap metrik, serta 20 sesi olahraga terbaru. Tidak ada angka demo atau salinan nilai Mi Fitness yang ditanam ke aplikasi.

Migrasi Room 2 → 3 menambah tabel `activity_records` tanpa menghapus data lama. Pengukuran manual dideduplikasi berdasarkan jenis dan timestamp; ringkasan berdasarkan ID file. Unduhan ulang identik tidak mereset status ekspor.

## Ekspor webhook

Payload bertambah `schema_version: 2`, `activity_record_count`, dan `activity_records`. Array HR dan fase tidur lama tetap tersedia. Setiap rekaman aktivitas menyertakan kategori, timestamp milidetik, metrik nullable, `source: "watch"`, `source_file_id`, dan `format_version`. `active_calories` memakai kkal; `active_seconds` memakai detik. Ekspor maksimal 200 rekaman per kategori tabel per permintaan; tombol ekspor dapat ditekan kembali untuk sisa antrean. Rekaman ditandai terkirim setelah HTTP 2xx.

## Verifikasi dan batasnya

Delapan tes baru memeriksa layout ringkasan harian RW3, validity bitmap, pengukuran manual v1/v2, ringkasan olahraga v7, durasi tidak valid, CRC rusak, dan payload terpotong/versi tidak didukung. Fixture olahraga sintetis menguji format; angka sesi olahraga aktual masih perlu dibandingkan dengan jam setelah pemasangan dan sinkronisasi. Rekaman Bluetooth yang telah diperoleh tidak berisi payload sesi tidur 7 jam 29 menit; perubahan ini tidak mengklaim telah menyelesaikan pengambilan sesi tersebut.

Referensi struktur protokol (implementasi Kotlin dibuat di proyek ini):

- [Gadgetbridge DailySummaryParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailySummaryParser.java)
- [Gadgetbridge WorkoutSummaryParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/WorkoutSummaryParser.java)
- [Gadgetbridge ManualSamplesParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/ManualSamplesParser.java)

Rekaman Bluetooth/bugreport tidak disertakan dalam APK atau fixture tes.
