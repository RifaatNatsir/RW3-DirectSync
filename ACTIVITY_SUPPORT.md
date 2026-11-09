# Aktivitas dan olahraga

## Pemeriksaan tidur terakhir, 9 November 2026

Sesi yang dicari pengguna adalah 9 November, 01:56–05:45 (rentang 229 menit). Pada awal pemeriksaan ADB, database masih berisi 24 sesi dan sesi terakhir 8 November 00:11–08:30. Setelah perbaikan aplikasi dan restart biasa smartwatch oleh pengguna, sesi tersebut berhasil diterima dari file jam `B0B7F06A200420`, 7.660 byte dengan CRC valid. Database kini berisi **25 sesi**, dan UI menampilkan **3j 49m**, **09 Nov 01:56 – 09 Nov 05:45**. Waktu/durasi berasal dari file terverifikasi, bukan input manual pengguna.

Perbaikan transport/antrean:

- Pembaca RFCOMM mempertahankan preamble SPP V1/V2 yang terpotong antarpembacaan, termasuk sisa satu byte `BA`/`A5` dan dua byte `BA DC`. Regresi menguji semua posisi pemotongan serta frame gabungan/byte per byte.
- Urutan daftar hari ini → riwayat → mode harian alternatif diserialkan. Permintaan file menunggu aliran jam tenang tiga detik; mode alternatif tidak menyebabkan pengulangan daftar riwayat. Timeout respons daftar dibatasi 15 detik.
- Awal file baru bisa memulihkan assembler setelah file terdahulu terputus. ID asli file dorongan parsial dicatat dan diprioritaskan untuk unduhan ulang, tanpa mengonsumsi permintaan olahraga lain.
- Selesainya penyimpanan file sebelumnya tidak membatalkan timeout file berikutnya yang sudah mulai diterima. Callback penyimpanan dari koneksi lama tidak mengubah antrean/status koneksi baru.
- Setelah reconnect, throttle permintaan direset dan auto-sync menunggu transfer awal/unggah yang sedang berlangsung. Burst HR dijeda selama pengambilan riwayat untuk menghindari perintah sensor di tengah unduhan.
- Riwayat diperiksa lagi saat membuka aplikasi bila permintaan terakhir lebih dari lima menit, serta pada siklus monitor 30 menit selama tersambung.

Pengujian langsung menunjukkan timer pemulihan kini aktif dan file `944DEF6A200420` dijadwalkan ulang setelah transfer berhenti di chunk 1/4. Sebelum restart, kiriman jam berulang kali berhenti pada file 8 November tersebut sebelum file 9 November diterima. Setelah restart biasa, jam mengirim ketiga file 7–9 November sampai tuntas. Ini mendukung dugaan antrean kirim jam sempat tersendat; tidak membuktikan file 8 November rusak. Mi Fitness diuji dengan koneksi DirectSync dilepas: sebelum restart, sinkronisasi vendor melaporkan selesai tetapi log pembacaan laporannya tetap menunjukkan nol sesi 9 November. Koneksi kedua aplikasi sempat saling menutup; jalur DirectSync diuji terpisah. Validasi build terkini: **116 tes lulus, 0 gagal, 0 skip**, APK terbaru terpasang, tanpa crash AndroidRuntime.

File tidur 9 November membawa penanda mulai/bangun yang lengkap dan HR rata-rata 67 BPM, tetapi tidak menyertakan ringkasan/klasifikasi Deep, Light, dan REM. Pembacaan langsung tersebut disimpan sebagai `hasStageData=false`. Setelah restart, **Mi Fitness juga berhasil menampilkan sesi 9 November selama 3 jam 49 menit**. Jalur Health Connect yang sudah tersedia kemudian membaca 25 sesi Mi Fitness dan melengkapi fase 7–9 November tanpa duplikasi. Sesi 9 November kini memiliki **60 menit Deep, 169 menit Light, dan tidak ada interval REM dalam laporan yang diterima** (`hasStageData=true`, sumber Mi Fitness). Bar fase menggunakan laporan tersebut, bukan klasifikasi buatan dari sensor mentah.

Snapshot ADB dan screenshot UI sesudah pemulihan ada di `app/build/qa/sleep-latest-fix/verification.json`, `sleep-final-device.png`, dan `mifitness-after-restart-device.png`. Saat memeriksa Mi Fitness, DirectSync diputuskan dahulu. Setelah impor selesai, koneksi dikembalikan ke DirectSync agar dua aplikasi tidak bersaing menggunakan kanal jam yang sama.

Tidak ada reset pabrik atau penghapusan database. DirectSync tidak menambah atau mengirim perintah ACK yang menghapus file kesehatan untuk mencoba mengosongkan antrean. Pengujian Mi Fitness menggunakan tombol Sync normal. Salinan diagnostik pribadi disimpan hanya pada `app/build/qa/sleep-latest-fix/` yang diabaikan Git. Pemeriksaan mengacu pada implementasi framing dan file Xiaomi di Gadgetbridge; [ACK data dapat menghapus salinan perangkat](https://gadgetbridge.org/basics/topics/multiple/).

Tombol **Sync semua data** mengambil daftar file hari ini dan riwayat, kemudian mengunduh file yang didukung. Sambungkan jam melalui **Connect** terlebih dahulu. Setiap file diperiksa CRC32, padding, versi, dan batas payload sebelum disimpan. Nilai dengan validity bit kosong disimpan sebagai `null`, bukan angka nol.

## Data yang didukung

- Ringkasan harian versi 3–5: langkah, kalori aktif, HR istirahat/rata-rata/min/maks, rata-rata SpO₂/stres, dan jam berdiri bila ditandai valid oleh jam.
- Pengukuran manual versi 1–2: HR, SpO₂, dan stres. Versi 1 menerima header RW3 `e0` dan header `ff`. Suhu dan tipe pengukuran lain belum didukung.
- Ringkasan keluarga olahraga freestyle (subtipe file 8), versi 5/7/8/9/10: waktu mulai/selesai, durasi aktif, kalori aktif, HR rata-rata/min/maks, dan kode jenis olahraga. Kode yang tidak dikenal tetap ditampilkan sebagai nomor. GPS/rute, pace, jarak, dan keluarga file olahraga lain belum didukung.
- Tidur tetap memakai parser tidur yang terpisah. File olahraga tidak membuat sesi tidur.

Dashboard menampilkan ringkasan harian terakhir beserta tanggalnya, pengukuran manual terakhir untuk tiap metrik, serta hingga 20 sesi olahraga terbaru. Metrik yang belum tersedia ditampilkan sebagai tanda kosong, tanpa angka contoh.

## Perbaikan total harian dan unduhan olahraga

Detail harian sekarang juga menghasilkan total langkah (increment 14-bit per menit) dan kalori aktif (6 bit terbawah field aktivitas per menit), hanya jika field ditandai hadir dan valid. Field yang tidak dilaporkan tetap `null`; angka nol yang eksplisit tetap valid. Parser tetap memeriksa checksum, versi, panjang, dan field opsional. Pada pemeriksaan perangkat, penjumlahan file hari ini menghasilkan **383 langkah dan 30 kkal**, cocok dengan angka yang dikonfirmasi pengguna dari jam. Target tampilan disesuaikan dengan target yang dikonfirmasi pengguna: **6.000 langkah dan 300 kkal**. Menit bergerak tidak dihitung dengan tebakan dari jumlah langkah.

Laporan protobuf langsung juga menyimpan total langkah/kalori yang benar-benar disertakan jam, termasuk saat BPM belum valid. Snapshot harian dengan ID sama diperbarui jika isinya berubah; sinkronisasi identik mempertahankan status ekspor. Layout RW3 versi 3 menyimpan kalori tepat setelah langkah (slot 1); layout versi 5 menggunakan slot 13. Perbedaan versi diuji agar field kalori tidak kembali kosong.

File detail harian dapat berupa potongan sebagian hari, bukan selalu rekaman sejak tengah malam. Migrasi Room 4 → 5 menambah tabel internal `daily_activity_minutes` dengan timestamp sebagai primary key. Data per menit yang bertumpuk dideduplikasi; total menit tidak dijumlahkan lagi dengan snapshot kumulatif. Dashboard menggunakan nilai terbesar yang valid antara total menit unik dan penghitung kumulatif hari yang sama. Snapshot parsial atau laporan nol tidak menurunkan penghitung yang sudah tercatat; pergantian tanggal lokal memulai total hari baru. Field yang belum dikirim tetap unknown. Cache aktivitas yang terverifikasi diproses ulang untuk memulihkan data lama dan memperbaiki field kalori v3.

Tabel menit adalah data internal untuk penggabungan; payload webhook tetap menggunakan bentuk rekaman aktivitas yang sudah ada. Pada pemeriksaan perangkat setelah upgrade, **383 langkah dan 45 kkal** kembali tampil walaupun jam belum tersambung; setelah tersambung dan menerima data baru, total tetap **383 langkah** dan naik menjadi **48 kkal**. Migrasi mempertahankan 24 sesi tidur dan dua sesi olahraga. Panel riwayat juga diperiksa di HP: halaman pertama menampilkan `1 / 4` dan tombol Berikutnya berpindah ke `2 / 4`.

Antrean unduhan menyelesaikan permintaan hanya bila identitas file yang diterima cocok. File HR/tidur yang didorong jam tetap diproses tanpa melewati permintaan olahraga yang sedang menunggu. Timeout mengulang identitas yang sama maksimal tiga percobaan sebelum melanjutkan antrean. Perbaikan ini sudah menghasilkan **dua sesi olahraga** di database perangkat, sesuai dua sesi di jam.

Kartu sesi menggunakan layout tersendiri: jenis olahraga, tanggal, rentang jam, durasi aktif, kalori, serta tiga kolom HR minimum/rata-rata/maksimum. Daftar tidak lagi dipotong menjadi lima kartu. Validasi regresi mencakup **53 tes**: dua tes tambahan total menit harian, tiga tes presence/zero protobuf, serta empat tes antrean (file sela, duplikasi, retry, reset koneksi), di samping tes sebelumnya.

## Tidur terbaru dan riwayat

Kartu tidur terakhir memakai bar proporsi Deep, Light, dan REM beserta durasi tiap fase. Fase yang tidak dilaporkan ditampilkan sebagai bar netral dan tanda kosong. Riwayat di dashboard dibatasi tiga kartu ringkas dengan mini bar; **Lihat semua** membuka panel berhalaman (tujuh sesi per halaman). Mengetuk kartu membuka detail durasi, waktu mulai/bangun, fase, dan sumber. Semua 24 sesi tetap tersimpan.

Validasi terbaru mencakup **65 tes**, termasuk lima regresi penggabungan aktivitas harian (file parsial/nol, overlap menit, snapshot kumulatif, field kosong, pergantian tanggal) dan perbedaan posisi kalori antara versi 3 dan 5.

File detail tidur RW3 dapat membawa flag bernilai `1` meskipun sudah ada penanda mulai dan bangun yang lengkap. Parser sekarang memeriksa marker event 2/3 dan normalisasi timestamp detik/milidetik/nanodetik terhadap rentang header. Rekaman diterima sebagai sesi selesai hanya bila penanda bangun tepat sesuai waktu akhir dan ada penanda mulai yang valid. CRC32, batas payload, versi, dan rentang waktu tetap diperiksa. Rekaman tanpa bukti selesai tetap ditolak.

Jika file hanya berisi waktu mulai/bangun dan sensor, durasi dihitung dari dua timestamp jam tersebut. Status `hasStageData = false` membedakannya dari ringkasan fase: tidak dibuat data deep/light/REM, skor tidak ditebak, dan UI menampilkan bahwa fase tidak disertakan. Laporan Mi Fitness dengan fase yang sudah tersedia tidak ditimpa oleh rekaman sensor yang lebih miskin data. File tidur yang sudah pernah diunduh diproses ulang saat aplikasi dibuka, termasuk ketika jam tidak tersambung.

Tombol **Ambil riwayat Mi Fitness** membaca sesi tidur Mi Fitness melalui Health Connect pada Android 14+, setelah izin `READ_SLEEP` diberikan. Pembacaan dibatasi pada sumber `com.xiaomi.wearable`, rentang 30 hari yang diizinkan Android, dan menggunakan pagination. Aplikasi tidak menulis atau menghapus Health Connect. Data yang berhasil diimpor tetap tersedia di database lokal. Halaman penjelasan penggunaan data tersedia dari dialog izin Android.

Migrasi Room 3 → 4 menambahkan `hasStageData` tanpa menghapus data sebelumnya. Sesi dideduplikasi berdasarkan waktu mulai; unduhan/impor identik mempertahankan sesi serta status ekspor fase. UI menampilkan tidur terakhir, daftar riwayat bertanggal beserta sumbernya, dan rata-rata sesi yang tersedia selama tujuh hari terakhir. Hari tanpa data tidak dihitung sebagai tidur nol menit.

Pemeriksaan perangkat menghasilkan **24 sesi tersimpan**. Pada 2–8 November 2026 terdapat **5 sesi** (tanggal bangun 3, 5, 6, 7, 8 November), rata-rata **483 menit / 8 jam 3 menit**. Sesi 8 November terbaca langsung dari file jam: **00:11–08:30, 499 menit / 8 jam 19 menit**, sesuai konfirmasi pengguna. Fase untuk sesi 7–8 November belum disertakan sumber dan tetap ditandai tidak tersedia. Tidak ada timestamp mulai duplikat; dua sesi olahraga sebelumnya tetap tersimpan.

Validasi parser dan konversi mencakup **59 tes**, termasuk rekaman berflag ambigu dengan marker bangun yang cocok, marker yang tidak cocok, format timestamp, fase Health Connect, interval tanpa fase, dan fase yang bertumpuk. Parser juga dijalankan pada tiga file tidur nyata dari perangkat. Berkas pribadi hasil pemeriksaan berada di folder build yang diabaikan Git.

Referensi: [Gadgetbridge SleepDetailsParser](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/SleepDetailsParser.java), [Android Health Connect: membaca data](https://developer.android.com/health-and-fitness/health-connect/read-data).

## Riwayat detak jantung dan grafik

File detail harian (activity/daily/details, flags `00`) versi 1–4 sekarang dibaca sebagai sampel HR per menit. Parser memeriksa CRC32, padding, panjang payload, bitmap presence/validity, field opsional, dan nilai BPM. Timestamp berasal dari waktu file jam ditambah indeks menit, bukan waktu file diunduh. Nilai nol, 255, atau field tanpa validity bit tidak dijadikan pengukuran.

Riwayat diminta otomatis setelah autentikasi/reconnect, atau melalui **Sinkronkan** di Dashboard. Pengambilan mencakup daftar hari ini dan daftar riwayat yang disediakan jam. Saat HP terputus, jam harus tetap merekam HR melalui pengaturan pengukuran otomatis di jam; aplikasi baru dapat mengambil hasilnya setelah koneksi kembali tersedia. Data yang sudah dihapus dari memori jam tidak dapat dipulihkan dari protokol ini.

Sampel historis menggunakan primary key negatif yang stabil berdasarkan timestamp; ID pengukuran langsung tetap positif. Insert ulang memakai `IGNORE`, sehingga unduhan identik tidak menambah duplikat atau mereset status webhook. Skema database tidak berubah.

Grafik menyediakan periode 24 jam dan 7 hari. Garis menampilkan rata-rata per 5 menit atau 30 menit untuk mengurangi kepadatan, sementara statistik menggunakan sampel asli. Sentuh grafik untuk melihat waktu dan BPM sampel asli terdekat. Jeda lebih dari 20 menit antarrekaman memutus garis. Tidak ada kurva simulasi pada kondisi kosong. Query riwayat memakai rentang waktu tanpa batas 1.500 rekaman dan diperbarui setiap menit.

Grafik HR menggunakan gaya area merah dengan isian yang lebih tegas, grid horizontal/vertikal, serta penanda di ujung garis. Garis putus-putus menunjukkan rata-rata sampel pada periode terpilih, bukan ambang kesehatan atau angka contoh. Sumbu horizontal tetap waktu. Minimum, rata-rata, dan maksimum tampil pada tiga kartu tersendiri berwarna biru, ungu, dan merah. Pengujian tampilan di perangkat mencakup periode 24 jam, 7 hari, dan inspeksi sampel melalui sentuhan; build APK berhasil.

Dashboard memprioritaskan HR, komposisi tidur, dan aktivitas. Koneksi, notifikasi, musik, dan ekspor tersedia pada tab Pengaturan; log berada pada Terminal. Status bar mengikuti tema gelap. File tidur yang belum selesai dicatat pada log tanpa mengubah koneksi aktif menjadi status gagal.

Validasi: `testDebugUnitTest assembleDebug --offline` mencakup 44 tes, termasuk tujuh tes riwayat HR (v1–4, bitmap RW3, field opsional, timestamp, validity, CRC, payload terpotong, dan versi tidak dikenal). Parser juga dijalankan pada empat file detail harian nyata dari cache perangkat. Dalam verifikasi perangkat, sinkronisasi memasukkan 3.780 sampel historis dengan nol timestamp historis duplikat. File pribadi hasil verifikasi berada di folder build yang diabaikan Git, bukan fixture atau aset APK.

Referensi format: [Gadgetbridge DailyDetailsParser](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailyDetailsParser.java), [XiaomiComplexActivityParser](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/XiaomiComplexActivityParser.java).

Migrasi Room 2 → 3 menambah tabel `activity_records` tanpa menghapus data lama. Pengukuran manual dideduplikasi berdasarkan jenis dan timestamp; ringkasan berdasarkan ID file. Unduhan ulang identik tidak mereset status ekspor.

## Ekspor webhook

Payload bertambah `schema_version: 2`, `activity_record_count`, dan `activity_records`. Array HR dan fase tidur lama tetap tersedia. Setiap rekaman aktivitas menyertakan kategori, timestamp milidetik, metrik nullable, `source: "watch"`, `source_file_id`, dan `format_version`. `active_calories` memakai kkal; `active_seconds` memakai detik. Ekspor maksimal 200 rekaman per kategori tabel per permintaan; tombol ekspor dapat ditekan kembali untuk sisa antrean. Rekaman ditandai terkirim setelah HTTP 2xx.

## Verifikasi dan batasnya

Tes memeriksa ringkasan harian RW3, validity bitmap, pengukuran manual v1/v2, ringkasan olahraga v7, durasi tidak valid, CRC rusak, payload terpotong, konversi Health Connect, serta rekaman tidur selesai. Fixture sintetis dipisahkan dari data pribadi perangkat. Hasil pemeriksaan perangkat untuk riwayat tidur dan olahraga dijelaskan pada bagian terkait di atas.

Referensi struktur protokol (implementasi Kotlin dibuat di proyek ini):

- [Gadgetbridge DailySummaryParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailySummaryParser.java)
- [Gadgetbridge WorkoutSummaryParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/WorkoutSummaryParser.java)
- [Gadgetbridge ManualSamplesParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/ManualSamplesParser.java)

Rekaman Bluetooth/bugreport tidak disertakan dalam APK atau fixture tes.
