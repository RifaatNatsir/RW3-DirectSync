# Tren dan timeline tidur

Dashboard → **Tidur terakhir → Tren & timeline tidur**. Mengetuk kartu pada riwayat tidur membuka sesi tersebut langsung pada timeline.

- Pilihan 7 hari dan 30 hari memakai rentang berjalan, dengan navigasi ke periode lebih lama yang memiliki rekaman.
- Durasi dikelompokkan berdasarkan tanggal bangun dalam zona waktu HP. Total per hari mencakup seluruh sesi, termasuk tidur singkat; rata-rata hanya membagi dengan jumlah hari yang memiliki data. Jumlah hari dan sesi ditampilkan. Hari kosong tetap kosong, bukan nol jam.
- Konsistensi memakai sesi terlama pada setiap tanggal bangun. Jam utama adalah median waktu lokal yang dibuka pada celah terbesar lingkaran 24 jam. Variasi adalah rata-rata jarak terpendek terhadap jam utama. Contoh 23:50 dan 00:10 menghasilkan median 00:00 dan variasi 10 menit. Variasi disembunyikan jika hanya satu hari tersedia. Grafik jam mulai dan bangun memutus garis pada hari kosong.
- Batang durasi memilih tanggal; pemilih sesi dapat membuka setiap sesi pada periode tersebut, termasuk beberapa sesi pada tanggal yang sama.
- Timeline menggunakan `start_timestamp` dan `end_timestamp` fase asli, dibatasi pada interval sesi dan `source_file_id` yang sama. Tidak menggunakan pembagian proporsi total menit. Deep, Light, REM, dan terjaga memakai warna masing-masing. Celah, konflik fase, klasifikasi yang tidak dikenal, atau sesi tanpa rincian fase tampil netral sebagai **Tanpa fase**. Tidak ada interpolasi klasifikasi tidur.
- Mengetuk timeline menampilkan waktu fase hingga detik. **Rincian fase** menyediakan urutan teks yang dapat dibaca pembaca layar. Ringkasan menit tetap memakai nilai sumber; pembulatan ringkasan tidak mengubah waktu timeline.
- Pembacaan Room bersifat reaktif dan lokal. Halaman ini tidak mengubah data kesehatan atau kepemilikan koneksi Bluetooth, tidak memerlukan koneksi jam, izin baru, maupun migrasi skema database. Metadata sesi dibaca tanpa batas 100 kartu riwayat; rincian fase hanya diamati untuk satu sesi terpilih.
- Periode, tanggal, dan sesi dipertahankan saat Activity dibuat ulang.

Implementasi utama: `data/SleepInsights.kt`, `ui/SleepInsightsActivity.kt`, `ui/custom/SleepCharts.kt`. Unit test `SleepInsightsTest` mencakup pergantian tanggal/zona waktu, rentang 30 hari lintas bulan, tidur singkat, median melewati tengah malam, fase submenit, celah, pemisahan sumber, tumpang tindih, dan sesi tanpa klasifikasi. Build dan unit test: `gradlew.bat testDebugUnitTest assembleDebug --offline`.
