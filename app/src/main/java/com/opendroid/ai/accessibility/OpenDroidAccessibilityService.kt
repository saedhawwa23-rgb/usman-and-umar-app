package com.opendroid.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.toColorInt
import com.opendroid.ai.R
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.service.OpenDroidService
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.data.repository.WorkspaceTaskRepository
import com.opendroid.ai.core.termux.TermuxIntegration
import com.usmanandumar.workspace.core.task.LocalAiProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.os.Build
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@AndroidEntryPoint
class OpenDroidAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var agentLoop: AgentLoop

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var workspaceTaskRepository: WorkspaceTaskRepository

    @Inject
    lateinit var termuxIntegration: TermuxIntegration

    @Inject
    lateinit var nodeTraversal: AccessibilityNodeTraversal

    @Inject
    lateinit var habitRoutineEngine: dagger.Lazy<com.opendroid.ai.core.routine.HabitRoutineEngine>

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val localAiHandler = Handler(Looper.getMainLooper())
    private var activeLocalAiRequest: LocalAiRequest? = null
    private var windowManager: WindowManager? = null
    private var floatingView: FloatingWidgetView? = null
    private var touchTargetView: TouchTargetView? = null
    private var isButtonAdded = false
    private var isDeviceLocked = false
    private var showFloatingButtonSetting = false
    private val imeSubmitLabels = listOf("search", "go", "send", "done", "submit", "ok", "enter")

    /**
     * BroadcastReceiver that tracks device lock/unlock state.
     * Hides the floating button when the device is locked to prevent
     * unintended interaction from the lock screen.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isDeviceLocked = true
                    refreshFloatingButtonVisibility()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // ACTION_USER_PRESENT is broadcast when the user unlocks the device
                    isDeviceLocked = false
                    refreshFloatingButtonVisibility()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Screen turned on but may still be locked; check KeyguardManager
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    isDeviceLocked = keyguardManager?.isKeyguardLocked == true
                    refreshFloatingButtonVisibility()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Initialize lock state
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        isDeviceLocked = keyguardManager?.isKeyguardLocked == true

        // Register receiver for screen on/off/unlock events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)

        serviceScope.launch {
            settingsRepository.llmConfig
                .map { it.showFloatingButton }
                .collectLatest { show ->
                    showFloatingButtonSetting = show
                    refreshFloatingButtonVisibility()
                }
        }

        serviceScope.launch {
            agentLoop.agentState.collectLatest { state ->
                floatingView?.updateState(state)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        handleLocalAiAccessibilityEvent(event)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank() && pkg != packageName) {
                habitRoutineEngine.get().recordAppOpen(pkg)
            }
        }
    }

    override fun onInterrupt() {
        // Handle interruption
    }

    override fun onDestroy() {
        val interruptedRequest = activeLocalAiRequest
        activeLocalAiRequest = null
        if (interruptedRequest != null && !interruptedRequest.finished) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    workspaceTaskRepository.recordOutcome(
                        interruptedRequest.taskId,
                        success = false,
                        errorCode = "ACCESSIBILITY_SERVICE_STOPPED",
                        errorMessageRedacted = "Accessibility service stopped before the local AI task completed.",
                        event = "local_ai_interrupted",
                    )
                }
            }
        }
        super.onDestroy()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Receiver may not have been registered
        }
        serviceScope.cancel()
        localAiHandler.removeCallbacksAndMessages(null)
        activeLocalAiRequest = null
        removeFloatingButton()
        instance = null
    }

    /**
     * Refreshes the floating button visibility based on both the user setting
     * and the device lock state. The button is only shown when the setting is
     * enabled AND the device is unlocked.
     */
    private fun refreshFloatingButtonVisibility() {
        if (showFloatingButtonSetting && !isDeviceLocked) {
            addFloatingButton()
        } else {
            removeFloatingButton()
        }
    }

    private fun addFloatingButton() {
        if (isButtonAdded) return

        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        val view = FloatingWidgetView(this)
        view.updateState(agentLoop.agentState.value)
        floatingView = view

        // Draw layer: renders the icon, including its animated glow, which can reach the
        // full edge of this 64dp box. It is deliberately not touchable (FLAG_NOT_TOUCHABLE):
        // without that, the transparent margin around the visible icon (the box is a square,
        // the icon is a circle inscribed in it) would swallow taps meant for whatever app is
        // underneath. See #107.
        val params = WindowManager.LayoutParams(
            dpToPx(64),
            dpToPx(64),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // Touch layer: an invisible window sized and centered to match the icon's static
        // footprint (matches the inset used in FloatingWidgetView.onDraw's radius calc), kept
        // in lockstep with the draw layer's position. This is the only part of the widget that
        // is actually touchable, so a tap or drag has to land on the visible icon to be caught
        // by the widget at all — everywhere else in the old 64dp square now falls through to
        // whatever is underneath, same as if the widget weren't there.
        val touchTarget = TouchTargetView(this)
        touchTargetView = touchTarget

        val touchInset = dpToPx(8)
        val touchParams = WindowManager.LayoutParams(
            dpToPx(64) - 2 * touchInset,
            dpToPx(64) - 2 * touchInset,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + touchInset
            y = params.y + touchInset
        }

        touchTarget.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false
            private val touchSlop = 10f
            private val longPressRunnable = Runnable {
                isClick = false
                triggerMicrophoneAction()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        touchTarget.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            if (isClick) {
                                isClick = false
                                touchTarget.removeCallbacks(longPressRunnable)
                            }
                        }
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()

                        val displayMetrics = resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val screenHeight = displayMetrics.heightPixels
                        params.x = params.x.coerceIn(0, screenWidth - params.width)
                        params.y = params.y.coerceIn(0, screenHeight - params.height)

                        touchParams.x = params.x + touchInset
                        touchParams.y = params.y + touchInset

                        try {
                            windowManager?.updateViewLayout(view, params)
                            windowManager?.updateViewLayout(touchTarget, touchParams)
                        } catch (e: Exception) {
                            // View might have been removed
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        touchTarget.removeCallbacks(longPressRunnable)
                        if (isClick) {
                            touchTarget.performClick()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touchTarget.removeCallbacks(longPressRunnable)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(view, params)
            windowManager?.addView(touchTarget, touchParams)
            isButtonAdded = true
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                windowManager?.removeView(view)
            } catch (removeError: Exception) {
                // Ignore: view may not have been added.
            }
            try {
                windowManager?.removeView(touchTarget)
            } catch (removeError: Exception) {
                // Ignore: view may not have been added.
            }
            floatingView = null
            touchTargetView = null
        }
    }

    private fun removeFloatingButton() {
        if (!isButtonAdded) return
        try {
            floatingView?.let { windowManager?.removeView(it) }
            touchTargetView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            floatingView = null
            touchTargetView = null
            isButtonAdded = false
        }
    }

    private data class LocalAiRequest(
        val taskId: String,
        val provider: LocalAiProvider,
        val prompt: String,
        val startedAt: Long = android.os.SystemClock.elapsedRealtime(),
        var sent: Boolean = false,
        var sending: Boolean = false,
        var beforeSendText: String = "",
        var lastText: String = "",
        var stableReads: Int = 0,
        var finished: Boolean = false,
    )

    private fun beginLocalAiTask(taskId: String, provider: LocalAiProvider, prompt: String): Boolean {
        if (activeLocalAiRequest != null || prompt.isBlank()) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(provider.packageName) ?: return false
        val request = LocalAiRequest(taskId, provider, prompt)
        activeLocalAiRequest = request
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(launchIntent)
            localAiHandler.postDelayed({ enterPromptAndSend(request) }, 1_200)
            true
        } catch (_: Exception) {
            activeLocalAiRequest = null
            false
        }
    }

    private fun handleLocalAiAccessibilityEvent(event: AccessibilityEvent) {
        val request = activeLocalAiRequest ?: return
        if (request.finished || event.packageName?.toString() != request.provider.packageName) return
        if (!request.sent && !request.sending) {
            localAiHandler.removeCallbacks(promptEntryRunnable)
            localAiHandler.postDelayed({ enterPromptAndSend(request) }, 350)
        } else if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            localAiHandler.removeCallbacks(responseCaptureRunnable)
            localAiHandler.postDelayed(responseCaptureRunnable, 1_200)
        }
    }

    private var promptEntryRunnable = Runnable { activeLocalAiRequest?.let(::enterPromptAndSend) }
    private var responseCaptureRunnable = Runnable { captureLocalAiResponse() }

    private fun enterPromptAndSend(request: LocalAiRequest) {
        if (activeLocalAiRequest !== request || request.finished || request.sent || request.sending) return
        if (android.os.SystemClock.elapsedRealtime() - request.startedAt > 75_000) {
            finishLocalAi(request, null, "Timed out waiting for an editable field in ${request.provider.label}.")
            return
        }
        val root = rootInActiveWindow ?: run {
            localAiHandler.postDelayed({ enterPromptAndSend(request) }, 900)
            return
        }
        val entered = try {
            if (!isSelectedAiApp(root, request)) false else setEditableText(root, request.prompt)
        } finally { root.recycle() }
        if (!entered) {
            localAiHandler.postDelayed({ enterPromptAndSend(request) }, 900)
            return
        }
        request.sending = true
        localAiHandler.postDelayed({ clickSend(request) }, 700)
    }

    private fun setEditableText(node: AccessibilityNodeInfo, value: String): Boolean {
        if (node.isEditable && node.isEnabled && node.isVisibleToUser) {
            return node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val success = try { setEditableText(child, value) } finally { child.recycle() }
            if (success) return true
        }
        return false
    }

    private fun clickSend(request: LocalAiRequest) {
        if (activeLocalAiRequest !== request || request.finished || request.sent) return
        val root = rootInActiveWindow ?: run {
            localAiHandler.postDelayed({ clickSend(request) }, 800)
            return
        }
        val clicked = try {
            if (!isSelectedAiApp(root, request)) {
                finishLocalAi(request, null, "The active window changed; the Send button was not clicked.")
                return
            }
            request.beforeSendText = nodeTraversal.screenText(root)
            nodeTraversal.findAndClickSubmitControl(root, listOf("send", "send message", "submit"))
        } finally { root.recycle() }
        if (!clicked) {
            finishLocalAi(request, null, "No accessible Send control was found; the prompt was not submitted.")
            return
        }
        request.sent = true
        localAiHandler.postDelayed(responseCaptureRunnable, 2_500)
    }

    private fun captureLocalAiResponse() {
        val request = activeLocalAiRequest ?: return
        if (request.finished) return
        if (android.os.SystemClock.elapsedRealtime() - request.startedAt > 180_000) {
            finishLocalAi(request, null, "Timed out waiting for a stable AI response.")
            return
        }
        val root = rootInActiveWindow ?: run {
            localAiHandler.postDelayed(responseCaptureRunnable, 1_500)
            return
        }
        val screen = try {
            if (!isSelectedAiApp(root, request)) {
                finishLocalAi(request, null, "The selected AI app is no longer the active window; no response was captured.")
                return
            }
            nodeTraversal.screenText(root)
        } finally { root.recycle() }
        val response = screen.replace(request.prompt, "").trim()
        val baseline = request.beforeSendText.replace(request.prompt, "").trim()
        val changedAfterSend = response.isNotBlank() && response != baseline
        if (changedAfterSend && response.length >= 12 && response == request.lastText) request.stableReads++ else request.stableReads = 0
        request.lastText = response
        if (request.stableReads >= 2) {
            serviceScope.launch { executeReturnedCode(request, response) }
        } else {
            localAiHandler.postDelayed(responseCaptureRunnable, 1_500)
        }
    }

    private suspend fun executeReturnedCode(request: LocalAiRequest, response: String) {
        val blocks = Regex("(?is)```(bash|sh|python|py)\\s*\\n(.*?)```").findAll(response).toList()
        val output = StringBuilder("Captured response from ${request.provider.label}:\n").append(response.take(16_000))
        if (blocks.isNotEmpty() && !settingsRepository.autoRunGeneratedCode.first()) {
            output.append("\n\nAI code blocks were NOT executed. Review the code, then enable the explicit high-risk auto-run setting to allow future AI tasks to send code to Termux.")
            finishLocalAi(request, output.toString().take(20_000), null, awaitingReview = true)
            return
        }
        for ((index, block) in blocks.withIndex()) {
            val executionId = termuxIntegration.runCode(block.groupValues[1], block.groupValues[2]).getOrElse { failure ->
                finishLocalAi(request, null, "AI response captured, but Termux could not start: ${failure.message}")
                return
            }
            val result = withTimeoutOrNull(120_000) {
                termuxIntegration.results.first { rows -> rows.any { it.executionId == executionId } }
                    .first { it.executionId == executionId }
            }
            if (result == null) {
                finishLocalAi(request, null, "Termux did not return an execution result before timeout.")
                return
            }
            output.append("\n\nTermux code block ").append(index + 1)
                .append(" exitCode=").append(result.exitCode ?: "unknown")
            if (result.stdout.isNotBlank()) output.append("\nstdout:\n").append(result.stdout.take(8_000))
            if (result.stderr.isNotBlank()) output.append("\nstderr:\n").append(result.stderr.take(8_000))
            result.error?.let { output.append("\nerror:\n").append(it) }
            if (!result.succeeded) {
                finishLocalAi(request, null, "Termux script failed: ${result.error ?: "exit code ${result.exitCode}"}.")
                return
            }
        }
        finishLocalAi(request, output.toString().take(20_000), null)
    }

    private fun isSelectedAiApp(root: AccessibilityNodeInfo, request: LocalAiRequest): Boolean =
        root.packageName?.toString() == request.provider.packageName

    private fun finishLocalAi(request: LocalAiRequest, output: String?, error: String?, awaitingReview: Boolean = false) {
        if (request.finished) return
        request.finished = true
        activeLocalAiRequest = null
        serviceScope.launch {
            runCatching {
                if (output != null) {
                    if (awaitingReview) {
                        workspaceTaskRepository.recordPendingUserReview(request.taskId, output, event = "local_ai_code_review")
                    } else {
                        workspaceTaskRepository.recordOutcome(request.taskId, true, outputRedacted = output, event = "local_ai_accessibility")
                    }
                } else {
                    workspaceTaskRepository.recordOutcome(
                        request.taskId, false, errorCode = "LOCAL_AI_FAILED",
                        errorMessageRedacted = error ?: "No response was captured.", event = "local_ai_accessibility",
                    )
                }
            }
            // Android cannot force-stop a different app. Return to this app and leave the AI
            // client in the background, rather than claiming it was terminated.
            performGlobalAction(GLOBAL_ACTION_HOME)
            localAiHandler.postDelayed({
                startActivity(Intent(this@OpenDroidAccessibilityService, com.opendroid.ai.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
            }, 250)
        }
    }

    private fun triggerMicrophoneAction() {
        val intent = Intent(this, OpenDroidService::class.java).apply {
            action = OpenDroidService.ACTION_TRIGGER_RECORD
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openMainActivityAction() {
        val intent = Intent(this, com.opendroid.ai.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun dpToPx(dp: Int): Int {
        return Math.round(dp * resources.displayMetrics.density)
    }

    /**
     * The invisible window that actually receives touches for the floating widget, sized to
     * just the icon's footprint (see [addFloatingButton]). Given its own named type — rather
     * than plain [android.view.View] — so it draws the same treatment [FloatingWidgetView]
     * already gets from the Lint `StaticFieldLeak` check.
     */
    private inner class TouchTargetView(context: Context) : View(context) {
        override fun performClick(): Boolean {
            super.performClick()
            openMainActivityAction()
            return true
        }
    }

    inner class FloatingWidgetView(context: Context) : android.widget.ImageView(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pulseRadius = 0f
        private var pulseAlpha = 255
        private var state: AgentState = AgentState.Idle

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                pulseRadius = value * dpToPx(8f)
                pulseAlpha = ((1f - value) * 150).toInt()
                invalidate()
            }
        }

        init {
            setImageResource(R.drawable.bot)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val padding = dpToPx(12)
            setPadding(padding, padding, padding, padding)
        }

        fun updateState(newState: AgentState) {
            if (this.state == newState) return
            this.state = newState

            animator.cancel()
            when (newState) {
                is AgentState.Thinking -> {
                    animator.duration = 600
                    animator.repeatMode = ValueAnimator.RESTART
                }
                is AgentState.Listening -> {
                    animator.duration = 1000
                    animator.repeatMode = ValueAnimator.REVERSE
                }
                is AgentState.Speaking -> {
                    animator.duration = 800
                    animator.repeatMode = ValueAnimator.REVERSE
                }
                else -> {
                    animator.duration = 2000
                    animator.repeatMode = ValueAnimator.REVERSE
                }
            }
            if (isAttachedToWindow) {
                animator.start()
            }
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width / 2f) - dpToPx(8f)

            // Draw cyber grey background circle
            paint.color = "#121216".toColorInt()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius, paint)

            // Draw center logo
            super.onDraw(canvas)

            // Draw glow border
            val color = when (state) {
                is AgentState.Idle -> "#FFFFFF".toColorInt() // Platinum pure white
                is AgentState.Listening -> "#FF3B30".toColorInt() // Pulsing red
                is AgentState.Thinking -> "#00F0FF".toColorInt() // Cyan
                is AgentState.Speaking -> "#007AFF".toColorInt() // Neon blue
                is AgentState.ExecutingPlan -> "#38BDF8".toColorInt() // Sky sapphire
                else -> "#FFFFFF".toColorInt()
            }

            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpToPx(3f)

            // Draw main border ring
            canvas.drawCircle(cx, cy, radius, paint)

            // Draw glowing pulse ring
            glowPaint.color = color
            glowPaint.style = Paint.Style.STROKE

            if (state is AgentState.Listening || state is AgentState.Thinking || state is AgentState.Speaking) {
                glowPaint.strokeWidth = dpToPx(1.5f)
                glowPaint.alpha = pulseAlpha
                canvas.drawCircle(cx, cy, radius + pulseRadius, glowPaint)
            }
        }
    }

    // --- Node Automation Methods ---

    fun findAndClick(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return nodeTraversal.findAndClick(rootNode, text)
    }

    fun findAndClickById(viewId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return true
                }
                parent = parent.parent
            }
            node.recycle()
        }
        return false
    }

    fun findAndType(searchText: String, content: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
        for (node in nodes) {
            if (node.isEditable) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                node.recycle()
                return true
            }
            node.recycle()
        }
        return false
    }

    fun findAndTypeById(viewId: String, content: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (node.isEditable) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                node.recycle()
                return true
            }
            node.recycle()
        }
        return false
    }

    /**
     * Performs the IME 'enter/search/go' action on the currently focused editable
     * field — used to submit a search box after TYPE_TEXT/TYPE_ID types into it.
     * Uses AccessibilityNodeInfo.ACTION_IME_ENTER on API 30+ (the only reliable way
     * to trigger the IME action programmatically). On older API levels — or if that
     * fails — falls back to tapping a nearby clickable control whose label reads like
     * a submit action ("search", "go", "send", "done", "submit", "ok", "enter").
     */
    fun performImeEnter(): Boolean {
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val success = if (focusedNode != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    false
                }
            } finally {
                focusedNode.recycle()
            }
        } else {
            false
        }
        return success || performSubmitFallback()
    }

    private fun performSubmitFallback(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = nodeTraversal.findAndClickSubmitControl(rootNode, imeSubmitLabels)
        rootNode.recycle()
        return result
    }

    fun performScroll(forward: Boolean): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val success = performScrollOnNode(rootNode, action)
        rootNode.recycle()
        return success
    }

    private fun performScrollOnNode(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable) {
            return node.performAction(action)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (performScrollOnNode(child, action)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    // --- Gesture Automation Methods (Coordinate Taps) ---

    fun clickCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().apply {
            addStroke(stroke)
        }.build()

        return dispatchGesture(gesture, null, null)
    }

    // --- Screen Text Extraction ---

    fun getScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val text = nodeTraversal.screenText(rootNode)
        rootNode.recycle()
        return text
    }

    suspend fun takeScreenshotAndEncode(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return suspendCoroutine { continuation ->
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                if (bitmap == null) {
                                    continuation.resume(null)
                                    return
                                }
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                bitmap.recycle()
                                hardwareBuffer.close()

                                if (softwareBitmap == null) {
                                    continuation.resume(null)
                                    return
                                }

                                val outputStream = ByteArrayOutputStream()
                                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                                val byteArray = outputStream.toByteArray()
                                val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                                softwareBitmap.recycle()
                                continuation.resume(base64String)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                continuation.resume(null)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: OpenDroidAccessibilityService? = null

        fun getInstance(): OpenDroidAccessibilityService? = instance

        fun startLocalAiTask(taskId: String, provider: LocalAiProvider, prompt: String): Boolean =
            instance?.beginLocalAiTask(taskId, provider, prompt) ?: false
    }
}
