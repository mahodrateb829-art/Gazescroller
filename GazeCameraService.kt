package com.gazescroller.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * خدمة أمامية (Foreground Service) بتشغّل الكاميرا الأمامية طول الوقت في الخلفية،
 * وبتستخدم ML Kit عشان تحسب زاوية ميل الرأس (pitch) لكل فريم.
 *
 * لما الرأس يميل لأعلى بشكل واضح -> سحبة لأعلى (الريل التالي)
 * لما الرأس يميل لأسفل بشكل واضح -> سحبة لأسفل (الريل السابق)
 *
 * التنفيذ الفعلي للسحبة بيحصل عن طريق GazeAccessibilityService.instance
 * لأن الكاميرا والتحليل مالهومش صلاحية تنفيذ لمسات، الخدمة التانية بس اللي عندها الصلاحية دي.
 */
class GazeCameraService : LifecycleService() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private var lastSwipeTime = 0L
    private var lastNotifUpdate = 0L
    private val swipeCooldownMs = 1200L
    private val notifUpdateThrottleMs = 1000L
    private val channelId = "gaze_scroller_channel"

    // عتبة الانحراف عن وضعية الراحة (بالدرجات) اللي بعدها نعتبر إن المستخدم "مايل" فعلًا
    // ملاحظة: دي عتبة *نسبية* عن baselinePitch، مش زاوية مطلقة من الصفر
    private val pitchThreshold = 12f

    // بعد كل سحبة بنعطّل السحب لحد ما الراس يرجع قريب من وضعية الراحة
    // (أقل من rearmThreshold). هيك ما بتتكرر السحبة وإنت لسه مايل، ورجعة الراس
    // للوسط ما بتعمل سحبة معاكسة بالغلط.
    @Volatile private var armed = true
    private val rearmThreshold = 6f

    // --- كشف الغمزة (Blink) عشان نعمل لايك تلقائي ---
    // احتمالية "العين مفتوحة" أقل من هاي القيمة تعتبر "مسكرة"، وأعلى من العتبة التانية تعتبر "مفتوحة"
    // (فيه فجوة بينهم مقصودة عشان نتجنب الاهتزاز/التذبذب لو الاحتمال قريب من النص)
    private val eyesClosedProb = 0.4f
    private val eyesOpenProb = 0.6f
    private var eyesCurrentlyClosed = false
    private var eyesClosedSince = 0L
    // الغمزة الطبيعية بتاخد ~100-400ms، فما بنعتبرها لايك. لازم العينين يضلوا
    // مسكرين مدة متعمّدة (نص ثانية على الأقل، وبحد أقصى ٣ ثواني عشان النعاس ما يعمل لايك)
    private val minDeliberateBlinkMs = 500L
    private val maxDeliberateBlinkMs = 3000L
    private var lastBlinkTime = 0L
    // مهلة بين كل لايك والتاني عشان الغمزات الطبيعية غير المقصودة ما تعمل لايكات متكررة بسرعة
    private val blinkCooldownMs = 1500L

    // زاوية الراحة (baseline): وضعية راسك الطبيعية وقت ما بلّشت — ممكن تكون
    // ٠ لو واقف بشكل مستقيم، أو ٢٠+ لو مستلقي على الأريكة والموبايل قدامك مايل.
    // كل قرارات السحب بتتخذ بناءً على *الفرق* عن هاي القيمة، مش الزاوية المطلقة.
    @Volatile private var baselinePitch: Float? = null
    // بنجمع أول ثواني من الفريمات عشان نحسب متوسط دقيق لوضعية الراحة تلقائيًا
    private val calibrationSamples = mutableListOf<Float>()
    // نافذة منزلقة من آخر N عينة (كانت ٢٠ عينة ثابتة بدون أي فحص ثبات)
    private val calibrationSampleCount = 12
    // أقصى تشتت (انحراف معياري بالدرجات) مسموح بين العينات عشان نقبل المعايرة.
    // لو راسك عم يتحرك، المعايرة بتستنى لحد ما يثبت بدل ما تسجّل baseline غلط.
    private val calibrationMaxStdDev = 3.5f

    // بنحلل ٣ فريمات بالثانية بس (بدل ٥) — تحليل ML Kit هو أكبر مستهلك للطاقة
    // بعد الكاميرا نفسها، وميل الرأس حركة بطيئة، فمش محتاجين نحلل كل فريم.
    // العتبة دي أهم بكتير من ٥ vs ٣ للاستجابة لأنو fps التحليل مش شرط
    // يتطابق مع سرعة رد فعل المستخدم — كول-داون السحبة أصلاً ١.٢ ثانية
    private val frameIntervalMs = 330L
    @Volatile private var lastFrameProcessedTime = 0L
    private var faceDetector: com.google.mlkit.vision.face.FaceDetector? = null

    companion object {
        // بيبعت آخر زاوية pitch متوقّعة لأي حد مستمع (MainActivity) عشان تظهر
        // على الشاشة مباشرة وتساعد في التشخيص (هل الكاميرا شايفة وش ولا لأ؟ هل الزاوية بتوصل للعتبة؟)
        var onPitchUpdate: ((rawPitch: Float, baseline: Float?, relativePitch: Float?, calibrationProgress: String?) -> Unit)? = null
        var onNoFaceDetected: (() -> Unit)? = null
        // بيبعت أي خطأ صار أثناء تشغيل الكاميرا/التحليل مباشرة عشان يظهر على الشاشة
        // بدل ما يضيع بالـ Logcat وحده (كنا قبل هيك ما عنا أي طريقة نعرف فيها
        // إنو صار استثناء صامت أوقف كل شي من غير ما يبين أي أثر على الواجهة)
        var onError: ((String) -> Unit)? = null
        // بينادى لما ينكشف "غمزة مقصودة" (العينين اتسكرو وبعدين انفتحو) عشان
        // نقدر نعرض تأكيد بصري على الشاشة (❤️ اتعمل لايك)
        var onBlinkDetected: (() -> Unit)? = null

        // مرجع للخدمة الشغّالة عشان MainActivity يقدر يطلب "إعادة معايرة" أي وقت
        // (مثلاً المستخدم كان واقف وبعدين قعد عالأريكة)
        var instance: GazeCameraService? = null
    }

    /** استدعيها لما تحب تسجّل وضعية راحة جديدة (بأي وقت وأي وضعية جسم) */
    fun recalibrate() {
        calibrationSamples.clear()
        baselinePitch = null
        armed = true
        eyesCurrentlyClosed = false
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    override fun onCreate() {
        super.onCreate()
        instance = this
        cameraExecutor = Executors.newSingleThreadExecutor()
        notificationManager = getSystemService(NotificationManager::class.java)

        // بنسجّل كل مرة الخدمة تنعاد تشغيلها فيها (onCreate بينادى بس لما الخدمة
        // تتقتل وتنعاد من الصفر، مش كل مرة تتفتح فيها الشاشة). لو الرقم عم يطلع
        // كبير بسرعة، معناها النظام عم يقتل الخدمة باستمرار (قبل ما المعايرة
        // تكمل)، مش مشكلة بمنطق المعايرة نفسه.
        val prefs = getSharedPreferences("gaze_debug", MODE_PRIVATE)
        val restartCount = prefs.getInt("restart_count", 0) + 1
        prefs.edit().putInt("restart_count", restartCount).apply()
        Log.d("GazeCameraService", "بدء الخدمة — مرة رقم $restartCount")

        startForegroundWithNotification("جاري المعايرة... (تشغيلة رقم $restartCount)")
        startCamera()
    }

    private fun buildNotification(contentText: String): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "تتبع ميل الرأس", NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, GazeCameraService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("تتبّع ميل الرأس شغّال")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(0, "إيقاف", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification(text: String) {
        startForeground(1, buildNotification(text))
    }

    private fun updateNotification(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate < notifUpdateThrottleMs) return
        lastNotifUpdate = now
        notificationManager.notify(1, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == "STOP") {
            stopSelf()
        }
        // START_NOT_STICKY: خدمة الكاميرا الأمامية ما بتقدر تنبدأ من الخلفية على أندرويد 12+،
        // فلو النظام قتل العملية وحاول يعيد تشغيلها (STICKY) بتصير كراشات متكررة.
        return START_NOT_STICKY
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            // لفّينا كل الجسم بـ try/catch عشان أي استثناء (حتى من .get() نفسها،
            // اللي كانت قبل هيك برّا أي حماية) ينكتب بوضوح على الشاشة بدل ما
            // يضيع بصمت على هاد الـ thread (تنفيذ الـ Runnable هون ممكن يفشل
            // من غير ما يطلع أي أثر ظاهر للمستخدم أبدًا)
            try {
                val cameraProvider = cameraProviderFuture.get()

                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    val msg = "❌ الجهاز ما عنده كاميرا أمامية متاحة"
                    Log.e("GazeCameraService", msg)
                    updateNotification(msg)
                    onError?.invoke(msg)
                    return@addListener
                }

                // رفعنا الدقة من 320x240 لـ 640x480: الدقة المنخفضة جدًا كانت
                // السبب الحقيقي وراء "المعايرة ما بتخلص" — مع minFaceSize 0.3
                // (تحت)، الوجه لازم يشغل ٣٠٪ من عرض الصورة عشان ينكتشف، وبصورة
                // ٣٢٠×٢٤٠ هاد بيعني لازم الموبايل يكون قريب جدًا من الوش (سيلفي
                // قريب)، مش وضعية استخدام طبيعية للتمرير. 640x480 لسه خفيفة
                // على المعالج/البطارية، بس بتدي ML Kit تفاصيل كافية يلقط فيها
                // وجه أصغر (أبعد) من غير ما يحتاج تقريب مبالغ فيه.
                val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(640, 480),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)

                // مش كل الأجهزة بتدعم نفس نطاقات الـ fps على الكاميرا الأمامية —
                // فبدل ما نفرض رقم ثابت (زي 10,10) ممكن يفشل بصمت على أجهزة
                // كتير (الكاميرا بترجع فريمات فاشلة بدل ما توقف بخطأ واضح)،
                // بنسأل هاردوير الكاميرا نفسه شو النطاقات المدعومة فعليًا
                // ونختار أبطأ نطاق موجود فيها. لو أي مشكلة بالقراءة، ما
                // بنطبّق أي تحديد أصلاً (بنرجع للسلوك الافتراضي الآمن).
                try {
                    val cameraManager = getSystemService(android.hardware.camera2.CameraManager::class.java)
                    var supportedRange: Range<Int>? = null
                    for (id in cameraManager.cameraIdList) {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                        if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                            val ranges = characteristics.get(
                                android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                            )
                            supportedRange = ranges?.minByOrNull { it.upper }
                            break
                        }
                    }
                    if (supportedRange != null) {
                        Log.d("GazeCameraService", "استخدام نطاق fps مدعوم فعليًا: $supportedRange")
                        Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            supportedRange
                        )
                    }
                } catch (e: Exception) {
                    Log.e("GazeCameraService", "تعذر قراءة نطاقات fps المدعومة، هنكمل من غير تحديد: ${e.message}")
                }

                val analysisUseCase = analysisBuilder.build()

                val detectorOptions = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // لازم ALL عشان نقدر نجيب احتمالية "العين مفتوحة" لكل عين (كانت NONE قبل هيك)
                    // كانت 0.3f (لازم الوجه يشغل ٣٠٪ من عرض الصورة) — عتبة عالية
                    // جدًا لوضعية استخدام طبيعية (موبايل مسند/مو ملصوق بالوش).
                    // خفّضناها لـ 0.15 عشان تنكتشف وجوه أبعد كمان
                    .setMinFaceSize(0.15f)
                    .build()
                val detector = FaceDetection.getClient(detectorOptions)
                faceDetector = detector

                // ملاحظة: setAnalyzer هون بيستخدم cameraExecutor (خلفية) قصدًا —
                // تحليل الفريمات شغلة تقيلة على المعالج ومحله مش على الـ main thread.
                // هاد مختلف عن bindToLifecycle تحت اللي *لازم* يصير عالـ main thread.
                analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        processFrame(imageProxy, detector)
                    } catch (e: Exception) {
                        Log.e("GazeCameraService", "فشل تحليل الفريم: ${e.message}", e)
                        onError?.invoke("خطأ بتحليل الفريم: ${e.message}")
                        imageProxy.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, analysisUseCase
                )
                Log.d("GazeCameraService", "تم ربط الكاميرا بنجاح")
            } catch (e: Exception) {
                val msg = "❌ فشل تشغيل الكاميرا: ${e.message ?: e.javaClass.simpleName}"
                Log.e("GazeCameraService", msg, e)
                updateNotification(msg)
                onError?.invoke(msg)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
        // ⬆️ السبب الحقيقي للخطأ "Not in application's main thread": كان هون قبل هيك
        // مكتوب "cameraExecutor" (خيط خلفية)، بينما bindToLifecycle لازم تنفّذ حصرًا
        // عالـ main thread. استبدلناها بـ getMainExecutor عشان الـ listener كامل (وبالتالي
        // الربط) يصير على الـ main thread، بدون ما نأثر على أداء التحليل (لسه شغّال
        // على cameraExecutor جوه setAnalyzer).
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processFrame(imageProxy: ImageProxy, detector: com.google.mlkit.vision.face.FaceDetector) {
        // Throttling: لو ما زال بدري عن آخر فريم حلّلناه، سكّر الفريم فورًا من غير
        // ما نبعته لـ ML Kit — هاد أهم توفير للبطارية لأنو الـ inference هو الجزء المكلف
        val now = System.currentTimeMillis()
        if (now - lastFrameProcessedTime < frameIntervalMs) {
            imageProxy.close()
            return
        }
        lastFrameProcessedTime = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                // ملاحظة مهمة: هاد الـ callback بيتنفذ لاحقًا (async) بعد ما processFrame()
                // خلصت وطلعت من نطاق الـ try/catch اللي بالـ analyzer فوق، فأي استثناء
                // هون كان ما بينلقط أبدًا وبيسقّط التطبيق كله بصمت. لهيك لازم try/catch
                // مستقل هون.
                try {
                    val face = faces.firstOrNull()
                    if (face != null) {
                        // headEulerAngleX بيمثل زاوية ميل الرأس لأعلى/لأسفل (pitch) بالنسبة لمحور الكاميرا
                        val pitch = face.headEulerAngleX
                        Log.d("GazeCameraService", "pitch=$pitch baseline=$baselinePitch")

                        val currentBaseline = baselinePitch
                        if (currentBaseline == null) {
                            // لسه بنعاير: بنجمع عينات لحد ما نطلع بمتوسط وضعية الراحة
                            calibrationSamples.add(pitch)
                            // نافذة منزلقة: بنحتفظ بآخر N عينة بس
                            while (calibrationSamples.size > calibrationSampleCount) {
                                calibrationSamples.removeAt(0)
                            }

                            val full = calibrationSamples.size >= calibrationSampleCount
                            val mean = calibrationSamples.average().toFloat()
                            val stdDev = sqrt(
                                calibrationSamples.map { (it - mean) * (it - mean) }.average()
                            ).toFloat()

                            if (full && stdDev <= calibrationMaxStdDev) {
                                // الوسيط (median) أقل تأثرًا بعينة شاذة من المتوسط
                                val sorted = calibrationSamples.sorted()
                                val n = sorted.size
                                val newBaseline = if (n % 2 == 1) sorted[n / 2]
                                else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
                                baselinePitch = newBaseline
                                armed = true
                                Log.d("GazeCameraService", "تمت المعايرة: baseline=$newBaseline stdDev=$stdDev")
                                updateNotification("تمت المعايرة ✅ — ميّل راسك للتمرير")
                                onPitchUpdate?.invoke(pitch, newBaseline, 0f, null)
                            } else {
                                val progress = if (full) {
                                    "${calibrationSamples.size}/$calibrationSampleCount — ثبّت راسك شوي"
                                } else {
                                    "${calibrationSamples.size}/$calibrationSampleCount"
                                }
                                updateNotification("عم نعاير... ($progress)")
                                onPitchUpdate?.invoke(pitch, null, null, progress)
                            }
                        } else {
                            val relativePitch = pitch - currentBaseline
                            updateNotification("الانحراف: ${relativePitch.roundToInt()}°")
                            onPitchUpdate?.invoke(pitch, currentBaseline, relativePitch, null)
                            handlePitch(relativePitch)
                        }

                        // كشف الغمزة بيشتغل حتى أثناء المعايرة (مش شرط ننتظر baseline)
                        val leftOpen = face.leftEyeOpenProbability
                        val rightOpen = face.rightEyeOpenProbability
                        if (leftOpen != null && rightOpen != null) {
                            handleBlink(leftOpen, rightOpen)
                        }
                    } else {
                        Log.d("GazeCameraService", "لا يوجد وجه في الفريم")
                        eyesCurrentlyClosed = false
                        updateNotification("مفيش وجه ظاهر للكاميرا دلوقتي")
                        onNoFaceDetected?.invoke()
                    }
                } catch (e: Exception) {
                    Log.e("GazeCameraService", "خطأ بمعالجة نتيجة كشف الوجه: ${e.message}", e)
                    onError?.invoke("خطأ بمعالجة نتيجة الوجه: ${e.message}")
                }
            }
            .addOnFailureListener {
                try {
                    Log.e("GazeCameraService", "فشل تحليل الوجه: ${it.message}")
                    updateNotification("خطأ في التحليل: ${it.message}")
                } catch (e: Exception) {
                    Log.e("GazeCameraService", "خطأ إضافي بمعالج الفشل: ${e.message}", e)
                }
            }
            .addOnCompleteListener {
                try {
                    imageProxy.close()
                } catch (e: Exception) {
                    Log.e("GazeCameraService", "خطأ بإغلاق الفريم: ${e.message}", e)
                }
            }
    }

    /** relativePitch = الفرق عن وضعية الراحة (baseline)، مش الزاوية المطلقة */
    private fun handlePitch(relativePitch: Float) {
        try {
            val now = System.currentTimeMillis()
            // بعد سحبة: ما منسمح بسحبة جديدة قبل ما الراس يرجع لوضعية الراحة
            if (!armed) {
                if (abs(relativePitch) < rearmThreshold) armed = true
                return
            }
            if (now - lastSwipeTime < swipeCooldownMs) return

            // ملاحظة: إشارة الزاوية ممكن تختلف حسب اتجاه تركيب الكاميرا، جرّب وعدّل لو معكوسة
            if (relativePitch > pitchThreshold) {
                GazeAccessibilityService.instance?.performSwipe(swipeUp = true)
                lastSwipeTime = now
                armed = false
            } else if (relativePitch < -pitchThreshold) {
                GazeAccessibilityService.instance?.performSwipe(swipeUp = false)
                lastSwipeTime = now
                armed = false
            }
        } catch (e: Exception) {
            Log.e("GazeCameraService", "خطأ بمعالجة الزاوية: ${e.message}", e)
        }
    }

    /**
     * بتتبع حالة العينين فريم بفريم عشان تكتشف "غمزة كاملة": سكرتين ثم فتحتين.
     * القرار بيصير لحظة إعادة الفتح (مش لحظة السكر) عشان نضمن إنها غمزة مكتملة
     * فعلًا مش بس وميض عابر لفريم واحد.
     */
    private fun handleBlink(leftOpen: Float, rightOpen: Float) {
        try {
            val bothClosed = leftOpen < eyesClosedProb && rightOpen < eyesClosedProb
            val bothOpen = leftOpen > eyesOpenProb && rightOpen > eyesOpenProb

            val now = System.currentTimeMillis()
            if (bothClosed) {
                if (!eyesCurrentlyClosed) {
                    eyesCurrentlyClosed = true
                    eyesClosedSince = now
                }
            } else if (bothOpen && eyesCurrentlyClosed) {
                eyesCurrentlyClosed = false
                val closedFor = now - eyesClosedSince
                if (closedFor in minDeliberateBlinkMs..maxDeliberateBlinkMs &&
                    now - lastBlinkTime >= blinkCooldownMs
                ) {
                    lastBlinkTime = now
                    Log.d("GazeCameraService", "غمزة انكشفت -> تنفيذ لايك")
                    GazeAccessibilityService.instance?.performDoubleTapLike()
                    onBlinkDetected?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e("GazeCameraService", "خطأ بمعالجة الغمزة: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        faceDetector?.close()
        cameraExecutor.shutdown()
    }
}
