# Dokumentasi Fitur Watchface & Wallpaper Redmi Watch 3

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

