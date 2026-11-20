package com.gazescroller.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent as AXEvent

/**
 * خدمة إمكانية الوصول (Accessibility Service).
 * هي الجزء الوحيد المسموح له تقنيًا على أندرويد بتنفيذ لمسات/سحب حقيقية
 * جوه تطبيقات تانية (زي إنستغرام) بدون ما يكون فيه اتصال مباشر بينهم.
 *
 * لازم المستخدم يفعّلها يدويًا من:
 * الإعدادات > إمكانية الوصول (Accessibility) > مرّر بميل رأسك > تفعيل
 */
class GazeAccessibilityService : AccessibilityService() {

    companion object {
        // مرجع ثابت للخدمة عشان أي جزء تاني في التطبيق (GazeCameraService)
        // يقدر يطلب منها تنفيذ سحبة، من غير ما يحتاج bind معقد
        var instance: GazeAccessibilityService? = null

        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }

    // آخر باكدج ظاهر بالمقدمة (بنعرفه من typeWindowStateChanged اللي بيوصلنا
    // لكل التطبيقات دلوقتي، مش بس إنستغرام، عشان نقدر نكتشف "خرج من إنستغرام")
    @Volatile private var currentForegroundPackage: String? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingLeaveRunnable: Runnable? = null

    // نوافذ النظام والكيبورد بتطلّع حدث TYPE_WINDOW_STATE_CHANGED بباكدج غير إنستغرام
    // (مثلاً لما تكتب تعليق)، وما بيجي بعدها أي حدث من إنستغرام يلغي مهلة الإطفاء،
    // فكانت الكاميرا بتتوقف غلط. هون بنتجاهل هالباكدجات بالكامل.
    private var ignoredPackages: Set<String> = setOf("com.android.systemui")
    // مهلة قبل ما نصدّق فعليًا إنو المستخدم طلع من إنستغرام. إنستغرام نفسه
    // بيفتح نوافذ فرعية كتير أثناء الاستخدام العادي (كيبورد، نوافذ منبثقة،
    // شريط الإشعارات لو نزّله المستخدم لحظة...) وكل وحدة منها بترجع باكدج
    // مختلف مؤقتًا. بدون هاي المهلة كنا عم نطفي الخدمة غلط بمجرد ما يظهر
    // أي عنصر من هدول، حتى والمستخدم لسه فعليًا جوا إنستغرام.
    private val leaveDebounceMs = 900L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            val imePackages = imm?.enabledInputMethodList?.map { it.packageName } ?: emptyList()
            ignoredPackages = ignoredPackages + imePackages
        } catch (e: Exception) {
            android.util.Log.e("GazeAccessibilityService", "تعذر قراءة قائمة الكيبوردات: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingLeaveRunnable?.let { handler.removeCallbacks(it) }
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // هاد الميثود بينادى لكل حدث نافذة بأي تطبيق مفتوح عالجهاز (مش بس إنستغرام)،
        // يعني بينفّذ فورًا لحظة ما تفعّل إمكانية الوصول. أي استثناء غير ملفوف هون
        // كان بيسقّط عملية التطبيق كلها -> وهاد بالضبط اللي كان يخلي أندرويد يعرض
        // رسالة "تم إغلاق التطبيق بسبب وجود خطأ" ويعطّل الخدمة تلقائيًا.
        try {
            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg in ignoredPackages) return

            if (pkg == INSTAGRAM_PACKAGE) {
                // رجعنا (أو لسه) على إنستغرام: نلغي أي خطة إطفاء معلّقة فورًا
                pendingLeaveRunnable?.let { handler.removeCallbacks(it) }
                pendingLeaveRunnable = null
                currentForegroundPackage = INSTAGRAM_PACKAGE
                return
            }

            if (currentForegroundPackage != INSTAGRAM_PACKAGE) {
                // أصلاً مش معتبرين حالنا جوا إنستغرام، فما في داعي لأي مهلة
                currentForegroundPackage = pkg
                return
            }

            // كنا جوا إنستغرام ووصل event لباكدج تاني: ممكن طلوع فعلي أو بس
            // نافذة عابرة. منستنى المهلة، ولو رجع عالإنستغرام قبل ما تخلص
            // (الحالة فوق) بتنلغي العملية تلقائيًا
            pendingLeaveRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable {
                try {
                    currentForegroundPackage = pkg
                    stopService(Intent(this, GazeCameraService::class.java))
                } catch (e: Exception) {
                    android.util.Log.e("GazeAccessibilityService", "خطأ بإيقاف الخدمة: ${e.message}", e)
                }
            }
            pendingLeaveRunnable = runnable
            handler.postDelayed(runnable, leaveDebounceMs)
        } catch (e: Exception) {
            android.util.Log.e("GazeAccessibilityService", "خطأ بمعالجة حدث إمكانية الوصول: ${e.message}", e)
        }
    }

    override fun onInterrupt() {}

    /** هل إنستغرام هو التطبيق الظاهر بالمقدمة دلوقتي؟ */
    fun isInstagramForeground(): Boolean = currentForegroundPackage == INSTAGRAM_PACKAGE

    /**
     * ينفّذ سحبة (swipe) عمودية فعلية على الشاشة.
     * direction = true يعني سحب لأعلى (زي ما لو المستخدم بيسحب بإصبعه من تحت لفوق -> الشاشة تنزل للريل التالي)
     * direction = false يعني سحب لأسفل (يرجع للريل السابق)
     */
    fun performSwipe(swipeUp: Boolean) {
        try {
            // حماية إضافية: حتى لو الكاميرا لأي سبب لسه شغالة، ما ننفذ ولا سحبة
            // إلا وإنستغرام هو الظاهر فعليًا بالمقدمة
            if (!isInstagramForeground()) return

            val metrics: DisplayMetrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val startY: Float
            val endY: Float

            if (swipeUp) {
                // من نص الشاشة تقريبًا لأعلى -> يودي للريل الجاي
                startY = metrics.heightPixels * 0.75f
                endY = metrics.heightPixels * 0.25f
            } else {
                // من نص الشاشة تقريبًا لأسفل -> يرجع للريل السابق
                startY = metrics.heightPixels * 0.25f
                endY = metrics.heightPixels * 0.75f
            }

            val path = Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }

            val gestureBuilder = GestureDescription.Builder()
            val stroke = GestureDescription.StrokeDescription(path, 0, 250)
            gestureBuilder.addStroke(stroke)

            dispatchGesture(gestureBuilder.build(), null, null)
        } catch (e: Exception) {
            android.util.Log.e("GazeAccessibilityService", "خطأ بتنفيذ السحب: ${e.message}", e)
        }
    }

    /**
     * بتعمل "double tap" فعلي بنص الشاشة، بالظبط متل ما لو المستخدم ضغط ضغطتين
     * سريعتين بإصبعه — هيك بيتسجل لإنستغرام كـ"لايك" على المنشور/الريل الحالي.
     *
     * ملاحظة تقنية: ضغطتين متتاليتين لازم ينبعتوا كـ gesture منفصلين (مش نفس الـ
     * GestureDescription) عشان أندرويد يفسّرهم كنقرتين حقيقيتين وراء بعض، فبنستنى
     * أول ضغطة تخلص (onCompleted) قبل ما نبعت التانية بفاصل بسيط.
     */
    fun performDoubleTapLike() {
        try {
            if (!isInstagramForeground()) return

            val metrics: DisplayMetrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val centerY = metrics.heightPixels / 2f

            dispatchSingleTap(centerX, centerY) {
                // فاصل قصير بين الضغطتين (زي سرعة الضغط الطبيعية بالإصبع)
                android.os.Handler(mainLooper).postDelayed({
                    dispatchSingleTap(centerX, centerY, null)
                }, 90L)
            }
        } catch (e: Exception) {
            android.util.Log.e("GazeAccessibilityService", "خطأ بتنفيذ اللايك: ${e.message}", e)
        }
    }

    private fun dispatchSingleTap(x: Float, y: Float, onDone: (() -> Unit)?) {
        try {
            val tapPath = Path().apply { moveTo(x, y) }
            val tapGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))
                .build()

            dispatchGesture(tapGesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone?.invoke()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone?.invoke()
                }
            }, null)
        } catch (e: Exception) {
            android.util.Log.e("GazeAccessibilityService", "خطأ بتنفيذ النقرة: ${e.message}", e)
        }
    }
}
