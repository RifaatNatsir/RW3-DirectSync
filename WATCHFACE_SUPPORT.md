# Dokumentasi Fitur Watchface & Wallpaper Redmi Watch 3

## Pembaruan 9 November 2026: koneksi, warna angka, dan bayangan

Status siap unggah sekarang berasal dari koneksi SPP yang sudah terautentikasi, terpisah dari status pekerjaan sinkronisasi. Streaming HR, pembacaan riwayat, atau ekspor tidak lagi membuat halaman Watchface menampilkan jam terputus. Tombol segarkan bisa menghubungkan ulang saat belum siap. Pemeriksaan service tetap memvalidasi socket/enkripsi sebelum menghapus atau mengirim paket; putus koneksi menghentikan transfer dengan pesan yang jelas.

Kelima desain — Green Glass, Fairy flower, Abstraction, Triangulation, dan Handwriting — menyediakan card **Tampilan angka**:

- Warna asli, palet warna, atau input HEX `#RRGGBB`, termasuk hitam.
- Sakelar **Bayangan halus**, dengan feather Gaussian 1–6 px.
- Pilihan warna, bayangan, dan feather disimpan per desain; perubahan langsung masuk ke pratinjau dan berkas `.bin`, termasuk ekspor saat jam offline.

Styling mengganti resource sprite angka, tanggal, weekday, dan pemisah yang terkait. Bentuk font, ukuran frame, jarak angka, posisi, serta widget dinamis asli tetap dipertahankan. Bayangan dibatasi ke ukuran frame asli agar tata letak jam tidak bergeser. AOD Green Glass dan Abstraction tetap memakai resource asli; ikon dekoratif dan bentuk berotasi tidak ikut diwarnai. Fairy flower menggunakan mask grayscale pada beberapa frame BGRA ber-background hitam; mask tersebut dikonversi menjadi alpha sebelum tint, sehingga pilihan angka hitam tetap berbentuk angka.

Handwriting memakai parser/template biner yang sama dari asset yang sudah ada (`rw3_wallpaper_template.bin`, ID `265246003`), termasuk tiga layout normal dan jarak digit bertanda negatif. Pratinjau memakai font asli; angka langkah tidak direkayasa jika data belum tersedia. Empat paket vendor tambahan tetap diimpor lokal, tidak ditambahkan ke APK.

Validasi terbaru: **105 tes lulus, 0 gagal, 0 skip**, build debug berhasil. APK dipasang melalui `adb install -r`. Di HP, halaman tetap menampilkan **Terhubung** dan tombol pasang aktif saat HR streaming. Kelima desain diuji melalui kontrol warna/bayangan dan ekspor native; output diperiksa ulang untuk ID, frame/alpha, bayangan, serta pelestarian seluruh byte asli selain referensi resource yang sengaja diganti. Pengaturan desain/tampilan sebelum QA dipulihkan persis. Pengujian ini tidak mengirim paket modifikasi ke smartwatch.

Artefak QA lokal: `app/build/qa/watchface-research/connection-streaming-verification.json` serta `styled/styled-watchfaces.png` dan `styled/styled-watchfaces-verification.json` dalam folder yang sama. Catatan 94/87 tes dan batas implementasi di bawah merekam tahapan sebelumnya; bagian ini menjelaskan implementasi terkini.

## Empat template foto: Green Glass, Fairy flower, Abstraction, Triangulation

Pilihan **Desain jam** kini berupa pemilih dengan dialog daftar berisi empat template berikut, ditambah opsi Handwriting yang sudah ada. Pilihan terakhir diingat. Semua template asli diimpor satu kali dan disimpan privat per ID; paket vendor tidak dibundel ke APK.

| Desain | ID | Ukuran asli | Slot normal yang memakai foto |
|---|---|---:|---|
| Green Glass | `265210144` | 1.926.987 byte | 0 |
| Fairy flower | `265210032` | 155.035 byte | 0 |
| Abstraction | `265210171` | 528.821 byte | 0, 2, 3 |
| Triangulation | `265210196` | 102.423 byte | 0 |

Ketiga paket baru diambil dari cache unduhan resmi Mi Fitness menggunakan ADB, setelah mencocokkan ID, `fileSize` dan `fileHash`. Salinan asli tersedia dalam Unduhan HP dengan nama `FairyFlower-265210032.bin`, `Abstraction-265210171.bin`, dan `Triangulation-265210196.bin`. Watchface yang aktif pada awal pengambilan (Fairy flower) dipulihkan setelah proses selesai.

SHA-256 rilis yang diterima:
- Green Glass: `08274c34a003c705d1f03006801bb386b70edc0fa0046cc88517d268f70ea2bb`
- Fairy flower: `51311a8af80736bbf71ab81d02397981e641722ab5be0c1b257953d0196dce7f`
- Abstraction: `06a497756875fbd271a5f3fdf0cd83eedb28a68bc34d7e027353c212a6596351`
- Triangulation: `7cbf1f1bb5b4204a30350bdb021eed34092e64e2693833fbdf1237d9890cecb4`

Implementasi terbaru:
- `PhotoWatchfaceProfile` mengikat ID, ukuran, hash, dan daftar slot foto yang diverifikasi untuk setiap rilis. Berkas salah versi, terpotong, atau milik desain lain ditolak.
- `PhotoWatchfaceBinary` membaca descriptor, elemen, sprite, gambar, dan widget secara terbatasi. Foto dan thumbnail baru ditambahkan di akhir paket, lalu hanya referensi background/thumbnail slot normal yang diperbarui. Seluruh payload asli dan alamat resource lain tetap sama. AOD slot 1 pada Green Glass dan Abstraction tidak diubah.
- `PhotoWatchfaceTemplate` merender pratinjau dari sprite asli, termasuk hari/minggu, tanda titik dua, tanda tanggal, dan komponen segitiga berotasi Triangulation. Battery pada pratinjau Abstraction memakai nilai dari service; jika belum diketahui, angkanya tidak direkayasa. Widget battery asli dalam paket tetap dinamis.
- Tanda `/` Green Glass dan ikon petir Abstraction yang menyatu dengan background dipertahankan lewat mask yang dibatasi ke lokasi terverifikasi. Gambar dekorasi latar diganti dengan foto pengguna; komponen jam tetap asli.
- Sumber tanggal dibaca sesuai mapping RW3: `1012` = bulan, `1812` = tanggal, `2012` = hari (Minggu indeks 0). Ini juga memperbaiki urutan bulan/tanggal pada pratinjau Green Glass versi awal.
- Ekspor dan unggah memakai ID desain yang dipilih, bukan ID Handwriting. Nama ekspor `<Desain>-Custom-<ID>.bin`. Pergantian desain tidak membawa data retry dari desain sebelumnya.
- File lokal Green Glass versi aplikasi sebelumnya tetap dapat dimigrasikan ke penyimpanan template baru. Impor ulang hanya diperlukan jika file template belum tersedia.

Validasi otomatis terbaru: **94 tes lulus, 0 gagal, 0 skip** pada mesin pengembangan yang memiliki empat fixture asli lokal. Pengujian mencakup decoding semua frame, referensi sprite, kelestarian widget/shape, penggantian tiga slot normal Abstraction dengan AOD tetap utuh, penolakan template salah, serta mapping tanggal dan battery, dan pelestarian ikon petir abu-abu RGB565. APK debug berhasil dibangun. Fixture vendor dan hasil QA tetap di `app/build/qa/watchface-research/` yang diabaikan Git; pengujian fixture dilewati pada mesin yang tidak memiliki berkas asli.

Pemeriksaan perangkat: keempat pilihan berhasil dipilih melalui dialog, dirender dengan foto uji, dan diekspor dari aplikasi ke Unduhan HP. Berkas `RW3-QA-<Desain>-<ID>.bin` diambil ulang melalui ADB dan diperiksa terhadap paket aslinya: ID benar, background/thumbnail berdimensi benar, seluruh payload asli di luar referensi foto tetap identik. Tidak ada crash AndroidRuntime. Pratinjau gabungan dan laporan verifikasi berada di `app/build/qa/watchface-research/four-custom-watchfaces.png` serta `four-custom-watchfaces-verification.json`.

Paket hasil modifikasi belum dikirim ke smartwatch dalam pengujian ini. Pengambilan paket melibatkan pemasangan ulang watchface resmi saja; watchface aktif awal dipulihkan. Foto uji pada hasil ekspor adalah fixture untuk QA.

Referensi mapping dan struktur:
- https://raw.githubusercontent.com/ooflet/Mi-Create/main/src/data/sources.json (`redmi_watch_3`)
- https://github.com/m0tral/UnpackMiColorFace/blob/master/Decompiler/FaceV2Decompiler.cs

Bagian berikut merekam tahapan Green Glass sebelumnya; implementasi empat template di atas menggantikan batas satu template pada tahap tersebut.

## Pembaruan: Green Glass dengan foto sendiri (8 November 2026)

Halaman Watchface sekarang menyediakan pilihan **Handwriting** dan **Green Glass**. Green Glass memakai ID asli `265210144` dan gambar angka asli dari paket, bukan overlay font Android yang menyerupainya.

Cara menggunakan:
1. Pilih **Green Glass**.
2. Pada penggunaan pertama, pilih **Pilih berkas Green Glass asli**, lalu pilih `GreenGlass-265210144.bin` dari Unduhan. Paket disimpan lokal dalam penyimpanan privat aplikasi; tidak dibundel ke APK atau dikirim ke server.
3. Pilih foto, geser/perbesar/perkecil, lalu lihat pratinjau dengan jam dan tanggal.
4. **Simpan berkas watchface (.bin)** tersedia juga saat jam tidak tersambung.
5. **Pasang Green Glass ke jam** mengganti watchface Green Glass yang memiliki ID sama, setelah dialog konfirmasi. Paket asli tetap tersedia secara lokal. Pemasangan paket hasil modifikasi belum diuji pada smartwatch dalam pembaruan ini; pemeriksaan perangkat mencakup APK, pratinjau, dan ekspor.

Batas dukungan:
- Hanya Green Glass asli berukuran 1.926.987 byte dengan SHA-256 `08274c34a003c705d1f03006801bb386b70edc0fa0046cc88517d268f70ea2bb` yang diterima sebagai template. Paket versi lain atau paket modifikasi ditolak sebagai template.
- Penggantian berlaku pada latar slot utama dan thumbnail utamanya. Varian kedua dipertahankan sepenuhnya. Editor bebas posisi/font/widget belum tersedia.
- Angka jam, menit, hari, bulan, tabel sprite, widget dan elemen asli tetap dipertahankan. Tanda `/` tanggal yang menyatu dengan gambar asli diekstrak dan digambar kembali di atas foto.
- Blok gambar baru menggunakan Xiaomi LVGL V2 / RGB565. Blok ditambahkan pada akhir paket; hanya empat word header/tabel diperbarui (referensi thumbnail utama dua buah, offset dan panjang background utama). Semua alamat dan byte komponen lama lainnya tetap sama.
- Pratinjau memakai sprite angka dari paket asli dan koordinat elemen yang sama. Thumbnail menggunakan 08:16 dan 04/06 seperti thumbnail original; jam pada watchface tetap widget dinamis.
- Paket asli tersedia di HP pada `Download/GreenGlass-265210144.bin` dan untuk QA lokal pada `app/build/qa/watchface-research/GreenGlass-265210144.bin`. Folder QA diabaikan Git.

Perbaikan transfer yang menyertai fitur:
- Unggah tidak lagi dimulai tanpa ACK permohonan upload.
- ACK dengan MD5 berbeda diabaikan dan posisi resume di luar batas ditolak.
- Selesai menulis chunk tidak lagi langsung berarti sukses. Aplikasi menunggu daftar watchface dari jam yang menandai ID tujuan aktif; jika tidak terkonfirmasi dalam batas waktu, UI menampilkan kegagalan verifikasi. Pembacaan ID aktif tidak memverifikasi isi visual setiap piksel.
- Antrean aktivitas dilanjutkan setelah operasi selesai, gagal, atau dibatalkan.
- Jalur input file untuk pengujian ADB hanya diproses pada build debuggable.

Validasi: **87 pengujian unit lulus, tanpa skip**, termasuk fixture Green Glass asli lokal (seluruh 40 sprite), stream RLE rusak, penggantian background dengan pelestarian byte sumber daya, serta status aktivasi yang tidak dikonfirmasi. Build debug berhasil. Fixture asli tidak dimasukkan ke Git; pengujian integrasi tersebut di-skip jika fixture lokal tidak tersedia di mesin lain.

Pemeriksaan HP selesai:
- APK debug dipasang dengan `install -r`; aplikasi terhubung kembali ke Redmi Watch 3, tanpa crash AndroidRuntime.
- Template asli dimuat ke penyimpanan privat aplikasi; pilihan Green Glass menampilkan angka jam/tanggal asli di atas gambar uji.
- Dialog CreateDocument berhasil menyimpan `Download/GreenGlass-Custom-265210144.bin` (1.967.496 byte).
- Hasil ekspor diambil lewat ADB. Seluruh byte paket asli selain empat word referensi gambar tetap identik; widget, sprite dan slot kedua tidak berubah.
- Pratinjau: `app/build/qa/watchface-research/green-glass-preview-device.png`. Paket dan laporan verifikasi: `GreenGlass-Custom-265210144.bin` dan `GreenGlass-Custom-verification.json` di folder QA yang sama.
- Gambar uji tidak diunggah ke smartwatch. Keberhasilan visual paket modifikasi di smartwatch belum diverifikasi.

Bagian di bawah merupakan dokumentasi awal. Keterangan awal tentang sukses otomatis setelah chunk terkirim telah digantikan oleh konfirmasi ID aktif yang dijelaskan di atas. Validator impor umum masih pemeriksaan dasar; validasi hash template Green Glass adalah jalur terpisah yang ketat.

## 1. Ringkasan Fitur
Fitur **Watchface & Wallpaper** pada RW3-DirectSync memungkinkan pengguna memasang foto dari galeri smartphone sebagai wallpaper kustom pada jam tangan pintar **Redmi Watch 3 (`lchz.watch.m65sa`)**, mempratinjau tampilan layar AMOLED dengan overlay jam yang presisi, serta mengelola dan mengimpor paket watchface biner yang kompatibel secara langsung tanpa melalui server pihak ketiga.

---

## 2. Spesifikasi Layar & Format Gambar
- **Model Perangkat:** Redmi Watch 3 (Codename: `lchz.watch.m65sa`). *Bukan Redmi Watch 3 Active.*
- **Resolusi Layar Asli:** 390 × 450 piksel (AMOLED Curved Rectangle).
- **Rasio Aspek:** 13:15 (≈ 0,8667).
- **Pemrosesan Gambar (`WallpaperImageProcessor`):**
  - Menggunakan dekoder aman `inSampleSize` berbasis pangkat dua untuk mencegah *Out-of-Memory* (OOM) pada gambar kamera beresolusi tinggi.
  - Membaca dan mengoreksi orientasi EXIF (rotasi 90°, 180°, atau 270°).
  - Melakukan cropping terpusat dengan rasio tepat 390:450 dan penskalaan hardware-accelerated.
  - Merender overlay jam digital realistis (jam, hari/tanggal bahasa Indonesia, indikator langkah harian) yang sesuai dengan tata letak template Redmi Watch 3.

---

## 3. Arsitektur Protokol Xiaomi Wear

### 3.1 Perintah Kontrol Watchface (`CMD_TYPE_WATCHFACE = 4`)
| Subtipe | Arah | Nama Protokol | Fungsi |
|---|---|---|---|
| `0` | HP ➔ Jam | `CMD_SUBTYPE_WATCHFACE_LIST` | Meminta daftar watchface yang terpasang di memori jam. |
| `0` | Jam ➔ HP | `WatchfaceList` | Jam mengembalikan daftar berulang `WatchfaceInfo(id, name, active, canDelete)`. |
| `1` | HP ➔ Jam | `CMD_SUBTYPE_WATCHFACE_SET` | Mengaktifkan watchface tertentu berdasarkan `watchfaceId`. |
| `2` | HP ➔ Jam | `CMD_SUBTYPE_WATCHFACE_DELETE`| Menghapus watchface kustom dari jam tangan. |
| `4` | HP ➔ Jam | `WatchfaceInstallStart` | Mengawali proses instalasi watchface dengan parameter `(watchfaceId, totalSizeBytes)`. |
| `4` | HP ➔ Jam | `WatchfaceInstallFinish` | Mengakhiri instalasi paket setelah transfer seluruh chunk data selesai. |
| `4` | Jam ➔ HP | `WatchfaceInstallStatus` | Konfirmasi hasil verifikasi dari jam (kode `0` / `2` = Sukses, selainnya = Gagal). |

### 3.2 Protokol Pengunggahan Data (`CMD_TYPE_DATA_UPLOAD = 22`)
| Subtipe | Arah | Nama Protokol | Fungsi |
|---|---|---|---|
| `0` | HP ➔ Jam | `DataUploadRequest` | Mengirim permohonan unggah: `type = 16` (Watchface), `md5sum` (16 byte), `size` (total byte). |
| `0` | Jam ➔ HP | `DataUploadAck` | Jam membalas dengan `md5sum`, `resumePosition` (biasanya 0), dan `chunkSize` (biasanya 4096 byte). |

### 3.3 Pengemasan Biner Wallpaper (`WallpaperBinPackager`) & Transmisi Chunk (`SPP V1 Channel 5`)
- **Pengemasan Biner Template (`rw3_wallpaper_template.bin`):**
  - Foto pengguna (390×450 piksel) dan thumbnail pratinjau (234×270 piksel) dikonversi ke format **Xiaomi LVGL RLE16 V2** (`bitmapType = 3`, `compressionType = 4`, piksel RGB565 Little-Endian dengan masking sudut lengkung `radius = 86` / `51`).
  - Blok gambar disuntikkan ke dalam paket biner watchface Redmi Watch 3 (`id = 265246003`, `name = Custom Photo`) beserta pembaruan offset tabel sumber daya.
- **Amplop MassData (26-Byte Envelope):**
  - Seluruh biner `.bin` dibungkus dengan header `[0x00, 0x10, md5(16B), fullSize(4B LE)]`, diikuti payload biner dari `resumePosition`, dan diakhiri `CRC32(4B LE)`.
- **Transport & Kanal:**
  - Pada Redmi Watch 3 (SPP V1), chunk dikirimkan melalui **SPP V1 Channel 5** (`CHANNEL_MASS = 5`, header `BA DC FE 05 ... EF`) dengan 4-byte part header `[totalParts(2B LE), currentPart(2B LE)]` (`1..totalParts`) pada setiap potongan `chunkSize` (8192 byte).
  - Pada perangkat SPP V2, mendukung fallback otomatis ke **SPP V2 Channel 2** (`CHANNEL_DATA`).
- **Pacing & Penggantian Otomatis:**
  - Jika watchface kustom dengan ID yang sama (`265246003`) sudah terpasang di memori jam, sistem secara otomatis menghapus versi lama sebelum mengunggah wallpaper baru agar ruang penyimpanan jam tidak penuh.
  - Diberikan jeda antar-chunk untuk menjaga kestabilan buffer chip Bluetooth jam tangan, lalu otomatis mengaktifkan watchface setelah transfer selesai.

---

## 4. Validasi Keamanan Paket (`WatchfacePackageValidator`)
Untuk melindungi jam tangan dari kerusakan firmware (*bricking*):
1. **Perlindungan Anti-Firmware:**
   - Menolak keras berkas dengan signature header firmware berbahaya seperti `AMOI`, `XMFW`, `MIFI`.
   - Menolak berkas yang mengandung kata kunci firmware seperti `.fw`, `bootloader`, `recovery`, `ota_update`.
2. **Batasan Ukuran Berkas:**
   - Ukuran minimum: 128 byte.
   - Ukuran maksimum: 10 MB (batas wajar memori watchface AMOLED).
3. **Pemeriksaan Integritas & Ekstraksi Metadata:**
   - Menghitung checksum MD5 yang cocok dengan protokol pengunggahan jam.
   - Mengekstrak nama watchface dari offset 104 pada header biner `.bin` atau dari berkas manifest `wfDef.json` pada arsip `.zip`.
   - Memastikan keamanan ekstraksi ZIP terhadap serangan *Zip Slip*.

---

## 5. Alur Penggunaan pada Antarmuka (UI/UX)
1. Buka aplikasi **RW3-DirectSync** ➔ tab **Pengaturan** ➔ pilih kartu **Watchface & Wallpaper**.
2. **Memilih Foto:** Tekan tombol **Pilih Foto dari Galeri (Photo Picker)**. Fitur ini menggunakan Android Photo Picker modern (`PickVisualMedia`) yang aman tanpa memerlukan izin akses seluruh penyimpanan.
3. **Pengaturan Pratinjau:** Pengguna dapat menekan tombol **Perbesar**, **Perkecil**, atau **Reset** untuk menyesuaikan proporsi gambar pada bingkai layar AMOLED 390×450.
4. **Memasang ke Jam:**
   - Tombol **Pasang Wallpaper ke Jam** otomatis aktif hanya jika jam dalam status terhubung (*Authenticated*).
   - Pengguna dapat melihat progres transfer secara terukur (`LinearProgressIndicator` dan teks `X KB / Y KB • Z%`).
   - Tersedia tombol **Batal** untuk membatalkan pengiriman dan tombol **Ulangi** jika terjadi gangguan koneksi.
5. **Impor Paket Kustom:** Pengguna dapat mengimpor file paket watchface `.bin`, `.face`, atau `.zip`. Aplikasi memvalidasi kompatibilitas paket dan menampilkan nama serta ukuran sebelum dikirimkan ke jam tangan.
6. **Manajemen Koleksi:** Menampilkan daftar watchface yang tersimpan di jam dengan tombol **Pakai** untuk mengubah tampilan aktif, serta tombol **Hapus** untuk menghapus tema yang dapat dihapus.

---

## 6. Verifikasi Pengujian Unit
Pengujian otomatis telah dijalankan dan lulus 100% secara offline (`.\gradlew.bat testDebugUnitTest assembleDebug --offline`):
- `XiaomiWearWatchfaceProtocolTest`: Pengujian enkoding perintah Protobuf watchface list, set, delete, install, dan penguraian ACK.
- `WatchfacePackageValidatorTest`: Pengujian deteksi berkas biner valid, arsip zip, penolakan berkas firmware, dan batasan ukuran.
- `WatchfaceTransferManagerTest`: Pengujian state machine pengunggahan, pemecahan chunk, pengakuan perangkat, batas retry, dan pembatalan operasi.

