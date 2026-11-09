# Pusat status sinkronisasi dan perpindahan Mi Fitness

## Pemakaian

Di Dashboard, buka **Pusat status sinkronisasi**. Panel menampilkan lima kategori: detak jantung, tidur, langkah, kalori, dan olahraga. Pengaturan juga menyediakan card **Koneksi & Mi Fitness**.

- **Lengkap**: rekaman kategori tersedia di HP. Untuk tidur, rincian fase juga harus tersedia; durasi tanpa fase diberi penjelasan bahwa rincian belum tersedia. Langkah/kalori memakai ketersediaan hari lokal saat ini; nol yang benar-benar dikirim jam tetap valid.
- **Belum tersedia**: belum ada rekaman yang sesuai, atau rincian yang diperlukan belum disertakan sumber. Daftar kosong yang berhasil dibaca bukan kegagalan unduhan.
- **Gagal diunduh**: file gagal diparsing/diunduh, daftar riwayat tidak dijawab, koneksi terputus sebelum selesai, atau impor Health Connect gagal. Data lama tetap ditampilkan beserta tanggal aslinya.
- **Menyinkronkan**: kategori sedang diperiksa atau diimpor. Pesan kegagalan sebelumnya tidak ditampilkan seolah-olah percobaan baru sudah gagal.

**Data terakhir** berasal dari waktu rekaman, **Diterima** dari waktu data valid berhasil disimpan, dan **Diperiksa** dari akhir pemeriksaan kategori tersebut. Instalasi pertama tidak merekayasa waktu penerimaan untuk rekaman yang sudah tersimpan. HR, langkah, dan kalori mempunyai metadata penerimaan masing-masing; field kosong tidak memperbarui waktunya.

Tombol **Coba lagi** tersedia untuk kategori bermasalah/belum tersedia. Bila identitas file gagal diketahui, hanya file gagal dalam kategori tersebut diminta ulang. Bila daftar/koneksi gagal atau tidak ada identitas file, aplikasi memeriksa ulang daftar dan membatasi antrean unduhan eksplisit ke kategori pilihan. Satu file harian bisa memuat HR, langkah, dan kalori sekaligus; file lain yang otomatis didorong jam tetap diperiksa dan disimpan. Keberhasilan file lain tidak menghapus kegagalan yang belum terselesaikan. Kegagalan impor Mi Fitness saja dapat dicoba ulang tanpa mengambil koneksi Bluetooth; izin READ_SLEEP diminta kembali bila diperlukan lewat tindakan pengguna.

## Beralih aplikasi

**Jeda DirectSync & buka Mi Fitness** memeriksa ketersediaan Mi Fitness, menyimpan mode jeda, menghentikan HR/unduhan dan reconnect DirectSync, menutup socket SPP/GATT termasuk koneksi yang masih disiapkan, lalu membuka Mi Fitness. Unggah watchface yang sedang aktif harus selesai terlebih dahulu. Aplikasi tidak memaksa menghentikan proses Mi Fitness.

Saat kembali, pengguna memilih **Lanjutkan** atau **Tetap dijeda**. Mode jeda bertahan melewati penutupan/restart proses. Membuka Dashboard atau halaman watchface tidak membatalkan jeda. Tombol **Lanjutkan DirectSync** tetap tersedia di panel/pengaturan; pilihan Hubungkan pada Dashboard juga merupakan tindakan melanjutkan yang eksplisit. Resume menggunakan kredensial yang sudah tersimpan dan mengaktifkan kembali pemantauan/reconnect DirectSync. Selesaikan sinkronisasi Mi Fitness sebelum memindahkan koneksi kembali.

## Penyimpanan dan verifikasi

Metadata status disimpan terpisah dari database kesehatan pada preferences `metric_sync_status`; mode koneksi pada `directsync_connection_mode`. Tidak ada migrasi/destruksi tabel kesehatan. Metadata operasi yang belum selesai saat proses mati dipulihkan sebagai terhenti, bukan sukses. Callback koneksi lama tidak menyelesaikan antrean koneksi baru.

Validasi otomatis: **130 tes lulus, 0 gagal, 0 skip**. Regresi mencakup identitas file/kategori, status kosong, metadata field yang hadir, kegagalan antarfile, retry terarah, timeout daftar, pembatalan pengguna, dan pemulihan operasi yang terhenti.

QA ADB di `app/build/qa/sync-center/` menguji pembukaan Mi Fitness, pilihan saat kembali, Tetap dijeda melewati restart proses tanpa koneksi otomatis, dan impor tidur saja tanpa mengubah metadata empat kategori lain. Pengujian badge gagal memakai simulasi pada metadata impor saja; simulasi dibersihkan lewat impor nyata yang berhasil, tanpa membuat rekaman kesehatan contoh. Artefak dan snapshot pribadi tetap di folder build yang diabaikan Git.
