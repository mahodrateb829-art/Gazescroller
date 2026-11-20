package com.gazescroller.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gazescroller.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // بنستخدمها عشان نميّز إذا كنا إحنا اللي فتحنا شاشة إعدادات إمكانية الوصول
    // (زر "فتح إعدادات إمكانية الوصول")، عشان لما المستخدم يرجع من هناك
    // نكمل الرحلة أوتوماتيكيًا (نطلب إذن الكاميرا ثم نفتح إنستغرام) من غير
    // ما يحتاج يضغط أي زر تاني بنفسه
    private var waitingForAccessibilitySettings = false

    // لما تصير true، معناها الخدمة اشتغلت وعم ننتظر بس المعايرة تخلص
    // عشان نفتح إنستغرام أوتوماتيكيًا (بدل ما نفتحه فورًا ونعاير جواه)
    private var waitingForCalibrationToOpenInstagram = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startServiceAndOpenInstagram()
        } else {
            binding.statusText.text = "لازم توافق على إذن الكاميرا عشان التتبع يشتغل."
