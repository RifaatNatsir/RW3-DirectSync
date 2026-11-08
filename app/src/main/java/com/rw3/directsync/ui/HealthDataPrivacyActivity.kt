package com.rw3.directsync.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rw3.directsync.R

class HealthDataPrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
        }
        content.addView(TextView(this).apply {
            text = "Akses riwayat tidur"
            textSize = 24f
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
        })
        content.addView(TextView(this).apply {
            text = "RW3-DirectSync membaca data tidur dari Mi Fitness melalui Health Connect untuk menampilkan sesi dan riwayat tidur di HP. Data disimpan di database lokal aplikasi.\n\nAplikasi tidak menulis atau menghapus data Health Connect. Jika Anda menekan Ekspor Webhook, fase tidur tersimpan ikut dikirim ke alamat webhook yang Anda atur.\n\nAnda dapat mencabut izin membaca tidur melalui pengaturan Health Connect kapan saja."
            textSize = 16f
            setPadding(0, padding, 0, 0)
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
        })
        setContentView(content)
    }
}
