package com.mobilerpgpack.phone.engine.activity

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout

internal class SecondScreenPresentationController(
    private val context: Context,
    private val onSecondScreenActiveChanged: (Boolean) -> Unit = {},
    private val onSecondScreenSurfaceChanged: (Surface?, Int, Int) -> Unit = { _, _, _ -> },
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var presentation: SecondScreenPresentation? = null
    private var isStarted = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updatePresentation()
        override fun onDisplayRemoved(displayId: Int) = updatePresentation()
        override fun onDisplayChanged(displayId: Int) = updatePresentation()
    }

    fun start() {
        if (!isStarted) {
            displayManager.registerDisplayListener(displayListener, mainHandler)
            isStarted = true
        }
        updatePresentation()
    }

    fun stop() {
        if (isStarted) {
            displayManager.unregisterDisplayListener(displayListener)
            isStarted = false
        }
        presentation?.dismiss()
        presentation = null
        onSecondScreenSurfaceChanged(null, 0, 0)
        onSecondScreenActiveChanged(false)
    }

    private fun updatePresentation() {
        val display = findSecondaryDisplay()
        val currentDisplayId = presentation?.display?.displayId

        if (display?.displayId == currentDisplayId) return

        presentation?.dismiss()
        presentation = null
        onSecondScreenSurfaceChanged(null, 0, 0)

        if (display == null) {
            Log.i(TAG, "No secondary display found")
            onSecondScreenActiveChanged(false)
            return
        }

        presentation = SecondScreenPresentation(
            context = context,
            display = display,
            onSecondScreenSurfaceChanged = onSecondScreenSurfaceChanged,
        ).also { nextPresentation ->
            try {
                nextPresentation.show()
                onSecondScreenActiveChanged(true)
                Log.i(TAG, "Second screen presentation shown on display ${display.displayId}")
            } catch (error: WindowManager.InvalidDisplayException) {
                Log.w(TAG, "Unable to show second screen presentation", error)
                presentation = null
                onSecondScreenActiveChanged(false)
            }
        }
    }

    private fun findSecondaryDisplay(): Display? {
        val presentationDisplay = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        return presentationDisplay ?: displayManager.displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private class SecondScreenPresentation(
        context: Context,
        display: Display,
        private val onSecondScreenSurfaceChanged: (Surface?, Int, Int) -> Unit,
    ) : Presentation(context, display) {
        private lateinit var hudSurfaceView: SurfaceView
        private val surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                publishSurface(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                publishSurface(holder, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSecondScreenSurfaceChanged(null, 0, 0)
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            super.onCreate(savedInstanceState)

            window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )

            val root = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                keepScreenOn = true
            }

            hudSurfaceView = SurfaceView(context).apply {
                keepScreenOn = true
                holder.setFormat(PixelFormat.OPAQUE)
                holder.addCallback(surfaceCallback)
            }
            root.addView(
                hudSurfaceView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.BOTTOM,
                ),
            )

            setContentView(root)
        }

        override fun dismiss() {
            if (::hudSurfaceView.isInitialized) {
                hudSurfaceView.holder.removeCallback(surfaceCallback)
            }
            onSecondScreenSurfaceChanged(null, 0, 0)
            super.dismiss()
        }

        private fun publishSurface(holder: SurfaceHolder, width: Int? = null, height: Int? = null) {
            val surface = holder.surface
            val surfaceFrame = holder.surfaceFrame
            val surfaceWidth = width ?: surfaceFrame.width()
            val surfaceHeight = height ?: surfaceFrame.height()
            if (surface.isValid && surfaceWidth > 0 && surfaceHeight > 0) {
                onSecondScreenSurfaceChanged(surface, surfaceWidth, surfaceHeight)
            }
        }
    }

    private companion object {
        private const val TAG = "SecondScreen"
    }
}