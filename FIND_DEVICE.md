# Pencarian HP dan smartwatch

Pengaturan ? Cari perangkat. Tombol Bunyikan / getarkan jam mengirim perintah sistem Xiaomi 2/18 dengan System.findDevice = 0. Penghentian memakai nilai 1; aplikasi mengirim stop setelah 30 detik. Respons stop dari jam memperbarui kontrol aplikasi. UI membedakan permintaan terkirim dari respons jam.

Menu Cari HP pada smartwatch mengirim 2/17 dengan nilai 0 untuk mulai dan 1 untuk berhenti. Pesan dengan System hadir namun nilai default 0 dihilangkan dibaca sebagai mulai Cari HP. Pesan tanpa System atau nilai tidak dikenal tidak dianggap sebagai perintah mulai. Respons kosong Cari Jam tidak dianggap sebagai bukti jam memulai bunyi. Hanya koneksi DirectSync yang sudah terautentikasi dan tidak dijeda yang dapat memulai alarm.

HP memutar suara alarm Android secara berulang dan bergetar, dengan batas 60 detik. Volume dan Jangan Ganggu tidak diubah. Pemutaran yang diizinkan mengikuti sistem; perangkat tanpa suara/getaran yang tersedia dapat memiliki keterbatasan. Pemberitahuan pencarian menyediakan tombol Hentikan dan membuka kartu Cari perangkat. Tombol Hentikan pada kartu HP juga mengirim stop ke jam. Notifikasi memerlukan izin notifikasi yang sudah digunakan aplikasi. Tidak ada akses lokasi tambahan.

Pemutaran dihentikan saat pengguna menghentikannya, batas waktu berakhir, fokus audio hilang, koneksi ditutup, layanan dihentikan, atau DirectSync dijeda untuk Mi Fitness. MediaPlayer, getaran, audio focus, dan wakelock dibersihkan. Permintaan HP berulang tidak memperpanjang batas waktu. Pencarian jam diblokir selama unggah watchface. Koneksi Bluetooth harus aktif; fitur ini tidak menyediakan pencarian jarak jauh melalui internet.

Referensi protokol: [XiaomiSystemService](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiSystemService.java). Unit test FindDeviceProtocolTest memeriksa byte perintah mulai/berhenti, pesan kosong/tidak dikenal, dan pembedaan respons jam dari permintaan bunyi HP.

## Kompatibilitas Redmi Watch 3 yang diuji

Pengguna mengonfirmasi bahwa perintah native Cari Jam tidak menghasilkan bunyi maupun getaran pada perangkat ini. Jika jam tidak mengonfirmasi pencarian native dalam 1,5 detik, aplikasi mengirim pengingat Cari jam yang diberi judul jelas, maksimal 6 kali tiap 5 detik selama batas 30 detik. Pengguna mengonfirmasi pengingat muncul dan disertai getaran. UI memakai label Kirim sinyal ke jam, tidak menjanjikan suara native. Pengingat memakai ID khusus dan dibersihkan ketika pencarian berakhir; menutup salah satu pengingat di jam juga menghentikan pencarian. DND/getaran jam tetap mengikuti pengaturan perangkat.

Uji perangkat oleh pengguna juga mengonfirmasi bahwa menu Cari HP di smartwatch membuat HP berbunyi dan bergetar. Tidak ada perubahan volume HP atau reset perangkat.
