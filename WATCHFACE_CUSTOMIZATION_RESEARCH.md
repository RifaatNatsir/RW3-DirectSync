# Riset custom watchface Redmi Watch 3

## Implementasi lanjutan, 9 November 2026

Kelima desain kini mendukung warna angka asli/palet/HEX dan bayangan Gaussian dengan feather 1–6 px. Perubahan diterapkan pada sprite asli dalam paket, tidak hanya overlay pratinjau. Ukuran glyph, spacing, posisi widget, dan payload asli dipertahankan; hanya pointer/ukuran resource yang diganti dan blok baru ditambahkan. AOD Green Glass/Abstraction tidak diubah. Feather tetap dalam batas frame asli.

Fairy flower memerlukan normalisasi mask grayscale pada frame BGRA yang menyimpan background hitam opaque. Ini berbeda dari RGBA umum yang memiliki piksel hitam sah; normalisasi dibatasi ke profil Fairy flower sebelum tint. Handwriting menggunakan asset biner lama melalui parser yang sama, termasuk tiga layout serta spacing digit negatif.

Status siap unggah dipisahkan dari event pekerjaan sinkronisasi: HR streaming tidak lagi membuat koneksi terautentikasi dianggap terputus. Putus socket membatalkan transfer; UI menyediakan koneksi ulang. **105 tes lulus tanpa gagal/skip**, APK debug terpasang. Pengujian HP mencakup status/tombol unggah saat HR streaming dan ekspor kelima desain dengan warna/bayangan. Verifikasi biner dan pratinjau lokal ada di `app/build/qa/watchface-research/styled/`. Paket modifikasi belum dikirim ke smartwatch. Detail pemakaian dan batas dukungan terkini ada di bagian teratas `WATCHFACE_SUPPORT.md`; catatan berikut mempertahankan hasil riset tahap sebelumnya.

Tanggal pemeriksaan: 8 November 2026. Paket Green Glass asli berhasil diperoleh lewat penangkapan cache unduhan Mi Fitness menggunakan ADB. Ini belum membuktikan pemasangan Green Glass yang sudah dimodifikasi.

## Paket asli berhasil diambil

- Berkas lokal: `app/build/qa/watchface-research/GreenGlass-265210144.bin`.
- Salinan di HP: `/sdcard/Download/GreenGlass-265210144.bin`.
- Header magic: `5a a5 34 12`; ID `265210144`; nama `Green glass`; versi `0x800`; dua slot (berbeda dari tiga slot Handwriting).
- Ukuran lengkap: **1.926.987 byte**, sama dengan metadata Mi Fitness.
- MD5: `3f4acbb21c9cf74c95d0ae268fc654b2`, cocok dengan `fileHash` dari metadata Mi Fitness dan salinan di HP.
- SHA-256: `08274c34a003c705d1f03006801bb386b70edc0fa0046cc88517d268f70ea2bb`.
- Catatan verifikasi: `app/build/qa/watchface-research/GreenGlass-265210144-verification.json`.
- Metode: buka Green Glass resmi di Mi Fitness, unduh ulang, lalu baca berkas cache melalui ADB selama berkas masih tersedia. Penangkap hanya menyimpan hasil yang ukuran dan MD5-nya cocok; cache awal yang tertangkap sebelum selesai sudah diganti dengan paket lengkap yang terverifikasi.
- Green Glass resmi selesai dipasang ulang; Mi Fitness menampilkan **Sedang digunakan**. Belum ada modifikasi aset atau kompilasi ulang.
- Seluruh artefak biner dan skrip penangkap tetap di folder QA yang diabaikan Git. Jangan menambahkan paket pihak ketiga ini ke asset aplikasi tanpa memeriksa hak distribusinya.

## Kesimpulan

Custom watchface penuh memiliki jalur pengembangan melalui pembongkaran paket dan kompilasi ulang. Setelah riset ini, aplikasi ditambah pilihan Green Glass dengan penggantian latar slot utama, sprite jam/tanggal asli, dan ekspor lokal. Editor bebas widget/font belum tersedia. Lihat `WATCHFACE_SUPPORT.md` untuk implementasi dan pengujian terbaru.

Pada implementasi awal, foto pengguna selalu dikemas menggunakan template Handwriting (`265246003`). Ini keputusan implementasi aplikasi, bukan bukti bahwa firmware hanya mengizinkan custom gambar pada ID tersebut. Implementasi berikutnya memakai paket Green Glass asli untuk mempertahankan desain Green Glass (`265210144`). Mengubah ID Handwriting menjadi ID Green Glass saja tidak mengubah susunan jam, font, atau indikator menjadi Green Glass.

## Bukti dari proyek sebelum implementasi Green Glass

- `WallpaperBinPackager.DEFAULT_WALLPAPER_WATCHFACE_ID` adalah `265246003`.
- `WatchfaceActivity.handleInstallWallpaperClicked()` selalu memanggil `buildFromAssetTemplate()` tanpa pilihan template.
- Asset `rw3_wallpaper_template.bin` berukuran 493.733 byte, ID `265246003`, nama `Custom Photo`, versi header `0x800`, dan tiga slot.
- Patcher mengasumsikan header `0x07F0`, tabel resource `0x01B0`, 100 entri, tiga thumbnail, serta tiga entri background pada `0x0270`, `0x0490`, dan `0x06B0`. Ketiganya berukuran 390 x 450. Offset tersebut khusus template yang dibundel, tidak boleh diterapkan langsung pada Green Glass.
- `handleInstallImportedPackageClicked()` sudah menerima paket biner untuk dikirim. Ini jalur impor paket hasil kompilasi, bukan editor.

## Pemeriksaan HP awal, sebelum pengunduhan ulang

Pemeriksaan hanya membaca cache watchface; tidak mengunggah, mengaktifkan, atau menghapus watchface.

- Folder cache Mi Fitness untuk ID Green Glass ditemukan, tetapi kosong pada saat pemeriksaan.
- Satu berkas cache biner yang masih tersedia adalah Handwriting: ID `265246003`, nama `Handwriting`, ukuran 1.854.414 byte, versi `0x800`, tiga slot.
- Salinan pemeriksaan disimpan dalam folder QA yang diabaikan Git: `app/build/qa/watchface-research/mifitness-cache.bin`. Berkas ini bukan paket Green Glass dan jangan didistribusikan atau dimasukkan ke Git.
- Pencarian web berdasarkan ID pada pemeriksaan awal belum menemukan berkas Green Glass yang dapat diverifikasi. Pengunduhan ulang melalui Mi Fitness kemudian berhasil ditangkap melalui ADB, sebagaimana dicatat di atas.

## Cara mempertahankan Green Glass dengan gambar sendiri

1. Dapatkan paket Green Glass asli untuk **Redmi Watch 3 / lchz.watch.m65sa** dari unduhan resmi yang tersedia. Cache Mi Fitness bisa diperiksa setelah pengunduhan, tetapi beberapa versi menghapus paket setelah pemasangan. Folder ber-ID saja tidak cukup.
2. Jika paket berupa ZIP, identifikasi biner watchface di dalamnya; pengembang Mi Create menjelaskan bahwa `resource.bin` bisa merupakan watchface yang sudah dikompilasi.
3. Gunakan unpacker yang mendukung Redmi Watch 3 untuk membuka paket menjadi proyek dan aset. `UnpackMiColorFace` mencantumkan model ini; Mi Create mendukung proyek `.fprj` hasil unpack.
4. Identifikasi gambar background dan komponen overlay. Jika jam/ikon menyatu dengan gambar background, menggantinya secara utuh juga akan menghapus elemen tersebut; perlu memisahkan atau menyusun ulang layer.
5. Ganti gambar sambil mempertahankan widget dinamis jam, tanggal, langkah, HR, baterai, dan area sentuh yang memang ada pada proyek. Periksa semua varian dan AOD jika paket memilikinya; jangan menganggap tiga slot selalu berarti AOD.
6. Kompilasi khusus model Redmi Watch 3, buat thumbnail yang sesuai, lalu periksa ulang hasil dengan unpacker. Untuk salinan kustom, verifikasi strategi ID yang diterima perangkat dan hindari benturan dengan watchface asli.
7. Impor biner hasil kompilasi melalui aplikasi setelah validasi paket dan status transfer diperkuat. Keberhasilan spesifik Green Glass masih membutuhkan pengujian perangkat.

Alternatif untuk integrasi langsung ke Android adalah parser resource generik dan profil template Green Glass yang diverifikasi dari paket asli. Penggantian background harus memperbarui seluruh referensi offset dan panjang yang terdampak, mempertahankan blok widget, serta menggunakan encoding gambar yang benar. Patcher Handwriting yang ada tidak cukup untuk ini.

## Hal yang perlu diperbaiki sebelum mencoba paket modifikasi

- `WatchfacePackageValidator.validateBinaryPackage()` saat ini dapat mengembalikan `Valid` tanpa memverifikasi magic, struktur resource, dan kecocokan model. Pesan UI “valid untuk Redmi Watch 3” belum didukung pemeriksaan kompatibilitas yang cukup.
- Jalur impor ZIP memvalidasi container dan kemudian memilih biner terbesar. Metadata/ID perlu diambil ulang dari biner yang benar-benar dikirim, bukan diasumsikan berasal dari container yang sama.
- Pada pemeriksaan awal, `WatchfaceTransferManager.executeChunkStream()` menandai sukses setelah seluruh chunk ditulis dan jeda 400 ms. Ini sudah diperbaiki: status sukses menunggu daftar watchface yang menandai ID tujuan aktif, dengan timeout jika tidak terkonfirmasi. Konfirmasi ID aktif belum memverifikasi isi visual foto.
- `BleSyncService.startWatchfaceUpload()` otomatis menghapus watchface yang dapat dihapus dengan ID sama sebelum upload. Jangan sekadar mengubah ID foto menjadi `265210144`, karena itu berpotensi menghapus Green Glass asli tanpa menghasilkan desain yang dimaksud.
- Dokumentasi Mi Create secara khusus mencantumkan Redmi Watch 3 sebagai perangkat dengan pemulihan buruk/tidak tersedia untuk watchface bermasalah. Karena itu hasil kompilasi yang belum diperiksa tidak dipasang selama riset ini.

## Referensi primer

- [UnpackMiColorFace: daftar model termasuk Redmi Watch 3](https://github.com/m0tral/UnpackMiColorFace)
- [Mi Create: mengubah watchface yang sudah ada, resource.bin dan lokasi cache](https://github.com/ooflet/Mi-Create/discussions/32)
- [Mi Create: proyek, widget, dan pemilihan perangkat](https://ooflet.github.io/docs/quickstart/intro/)
- [Mi Create: properti gambar dan transparansi](https://ooflet.github.io/docs/widgets/image/)
- [Mi Create: kompilasi, pemeriksaan, dan batas pemulihan perangkat](https://ooflet.github.io/docs/quickstart/testing/)
- [EasyFace: dukungan Redmi Watch 3 dan unpacker](https://github.com/m0tral/EasyFace)

Pada sesi riset awal belum ada perubahan aplikasi. Sesi implementasi berikutnya menambahkan `GreenGlassBinary`, `GreenGlassTemplate`, `XiaomiImageBlock`, pilihan desain dan ekspor, serta konfirmasi ID aktif pada transfer. Build debug dan seluruh 87 pengujian berhasil; pemasangan watchface modifikasi ke smartwatch belum diuji.

Pratinjau dan ekspor dari HP sudah diperiksa. Berkas uji hasil ekspor berukuran 1.967.496 byte dengan ID `265210144`, disimpan di Unduhan HP dan folder QA lokal. Pemeriksaan terhadap paket asli mengonfirmasi bahwa hanya empat word referensi gambar berubah pada bagian asli; seluruh widget, sprite dan slot kedua tetap identik. Foto uji tidak dipasang ke smartwatch.


## Kelanjutan: empat template foto

Fairy flower `265210032`, Abstraction `265210171`, dan Triangulation `265210196` berhasil diambil lewat ADB, dicocokkan dengan ukuran/MD5 metadata Mi Fitness, dan ditambahkan ke aplikasi bersama Green Glass. Detail hash dan slot normal ada di `WATCHFACE_SUPPORT.md` serta `PhotoWatchfaceProfile.kt`. Berkas aslinya disimpan di Unduhan HP dan dalam penyimpanan privat aplikasi.

Implementasi baru memakai parser descriptor/resource, glyph, weekday, dan bentuk berotasi. Abstraction mengganti latar tiga varian normalnya (0, 2, 3) sambil mempertahankan AOD (1). Pratinjau Green Glass diperbaiki agar urutan bulan/tanggal mengikuti source ID asli. Unit test tambahan mencakup ikon petir RGB565 dengan perbedaan kuantisasi kanal warna. Seluruh 94 tes lulus; keempat pilihan dan ekspor diuji di HP. Foto hasil modifikasi belum diunggah ke smartwatch.
