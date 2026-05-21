package com.mobilerpgpack.phone.engine.activity

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import androidx.activity.enableEdgeToEdge
import com.mobilerpgpack.phone.engine.engineinfo.IEngineInfo
import com.mobilerpgpack.phone.engine.engineinfo.isResourceCorrect
import com.mobilerpgpack.phone.engine.engineinfo.mainSharedObject
import com.mobilerpgpack.phone.engine.engineinfo.uzdoom.UZDoomEngineInfo
import com.mobilerpgpack.phone.ui.activity.MainActivity
import com.mobilerpgpack.phone.utils.PreferencesStorage
import com.mobilerpgpack.phone.utils.forceLandscapeOrientation
import com.mobilerpgpack.phone.utils.waitUntil
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.qualifier.named
import org.libsdl3.app.SDLActivity

internal class SDL3GameActivity : SDLActivity(), KoinComponent {
    private lateinit var engineInfo : IEngineInfo
    private val secondScreenController by lazy {
        SecondScreenPresentationController(
            context = this,
            onSecondScreenActiveChanged = { enabled ->
                (engineInfo as? UZDoomEngineInfo)?.setSecondScreenHudEnabled(enabled)
            },
            onSecondScreenSurfaceChanged = { surface, width, height ->
                (engineInfo as? UZDoomEngineInfo)?.setSecondScreenHudSurface(surface, width, height)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        MainActivity.gameActivityStarted = true
        enableEdgeToEdge()
        val preferencesStorage : PreferencesStorage = get()
        engineInfo = get (named(preferencesStorage.activeEngineString.value!!))
        engineInfo.apply {
            gameResourcesFound = isResourceCorrect(this@SDL3GameActivity, onCloseDialogBox = { finish() })
            if (!gameResourcesFound) {
                super.onCreate(savedInstanceState)
                return
            }
            initialize(this@SDL3GameActivity)
            super.onCreate(savedInstanceState)
            loadLayout()
            onNativeLibrariesLoaded()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT
                ) {
                    onBackPressed()
                }
            }
        }
        forceLandscapeOrientation()
    }

    override fun getMainSharedObject() = engineInfo.mainSharedObject

    override fun getLibraries() = engineInfo.nativeLibraries

    override fun getArguments(): Array<String>  {
        val args = engineInfo.commandLineArgs
        return if (args.isEmpty()) super.getArguments() else args
    }

    override fun onPause() {
        super.onPause()
        if (gameResourcesFound) {
            secondScreenController.stop()
            engineInfo.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (gameResourcesFound) {
            engineInfo.onResume()
            secondScreenController.start()
        }
        forceLandscapeOrientation()
    }

    override fun onDestroy() {
        secondScreenController.stop()
        super.onDestroy()
        engineInfo.onDestroy()
    }

    @SuppressLint("MissingSuperCall", "GestureBackNavigation")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            engineInfo.onBackPressed()
        }
    }
}