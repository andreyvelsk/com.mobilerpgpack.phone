# Native Android Second-Screen Rendering

This document describes the second-screen rendering architecture used to move real UZDoom-native UI rendering from the primary Android display to a physical secondary display. The staged statusbar-only checkpoint is verified flicker-free and is the baseline for future changes. The current working change renders only the real UZDoom automap on the lower screen, using the same stable timing and EGL presentation pattern as that baseline.

The important result is this: the lower screen is no longer an Android reimplementation of the HUD/map and it is no longer a `Bitmap` fed by `glReadPixels`. The secondary display receives a native Android `Surface`, UZDoom renders a real engine draw list directly into an EGL window surface backed by that `Surface`, and the primary display remains fullscreen without the status bar.

Validated behavior:

- The lower display can render a real UZDoom-native second-screen draw list without Android HUD reconstruction.
- The upper display no longer renders the status bar.
- The flicker caused by the old `glReadPixels`/`Bitmap` path is avoided.
- No FPS throttling is required.
- The implementation was built and installed with `./gradlew assembleFdroidDebug -Pandroid.injected.build.abi=arm64-v8a` and `./gradlew installFdroidDebug -Pandroid.injected.build.abi=arm64-v8a`.

## Problem Statement

The target hardware exposes a real secondary Android display. The requested behavior is not just a second-screen UI. It is a true relocation of the engine-rendered UZDoom status bar:

- Keep the upper/main screen as the gameplay view.
- Remove the status bar from the upper/main screen completely.
- Render the same status bar that UZDoom would normally draw on the main screen onto the lower/secondary screen.
- Preserve pixel-authentic UZDoom rendering: SBARINFO/ZScript/status bar logic must still come from the engine.
- Avoid reducing FPS as a workaround for flicker.

## Approaches That Were Rejected

### Android data-driven HUD

The first implementation rendered an Android view from exported game state such as health, armor, ammo, and mugshot state. This was stable, but it was not acceptable because it did not render the actual UZDoom status bar. It could not preserve mod-specific SBARINFO, ZScript behavior, texture scaling, palette/translation behavior, or exact layout.

This approach is useful only for a custom companion UI, not for moving an engine-rendered HUD.

### Native offscreen render plus `glReadPixels` plus `Bitmap`

The second implementation rendered the real UZDoom status bar into an offscreen GLES target, read pixels back to CPU memory, copied them into an Android `Bitmap`, and drew the bitmap on the `Presentation`.

That proved the exact status bar could be rendered separately, but it was not stable enough for gameplay. It caused flicker and likely stalls because of several issues:

- `glReadPixels` forces GPU/CPU synchronization.
- `glFinish` blocks the render thread and can disturb frame pacing.
- Per-frame offscreen texture/FBO allocation churn is expensive.
- Android `Canvas` invalidation and the engine render loop are not synchronized.
- The primary GL state can be disturbed if the offscreen path is not restored perfectly.

Keeping the last valid bitmap and forcing opaque alpha helped only with transient empty frames. It did not solve the deeper synchronization problem.

### Second SDL window

SDL3's Android backend only supports one SDL window. In this project that makes a normal second SDL window a poor path. Do not attempt to solve this by creating another SDL window unless the SDL Android backend is redesigned.

## Key Design Decision

Use Android only to own and expose the physical secondary display surface. Do the actual HUD rendering inside the native engine render path.

The final architecture keeps the frame on the GPU:

1. Android detects the secondary display and shows a `Presentation` on it.
2. The `Presentation` contains a fullscreen opaque `SurfaceView`.
3. Android passes the `SurfaceView`'s `Surface` to native code through JNI.
4. Native code converts the `Surface` into an `ANativeWindow`.
5. UZDoom's render thread creates an EGL window surface for that `ANativeWindow` using the current GL context's EGL config.
6. UZDoom prepares the real lower-screen draw list in the original 320x200-style virtual space.
7. The GLES framebuffer temporarily switches the current EGL draw/read surface to the second-screen surface.
8. The engine draws the prepared 2D draw list into the second surface and swaps it with `eglSwapBuffers`.
9. The previous primary EGL surface and GL state are restored before the normal primary-screen frame update continues.

This is conceptually similar to the useful part of the melonDS Android pattern: do not copy rendered frames through CPU memory. Keep presentation GPU-native. melonDS passes texture IDs to a GL renderer; this project instead uses the existing UZDoom GL context and renders directly into a secondary Android window surface.

## File Map

Main Android wrapper code:

- `app/src/main/java/com/mobilerpgpack/phone/engine/activity/SecondScreenPresentationController.kt`
- `app/src/main/java/com/mobilerpgpack/phone/engine/activity/SDL3GameActivity.kt`
- `app/src/main/java/com/mobilerpgpack/phone/engine/engineinfo/uzdoom/UZDoomEngineInfo.kt`

UZDoom native code:

- `app/src/main/jni/UZDoom/src/d_main.cpp`
- `app/src/main/jni/UZDoom/src/common/rendering/v_video.h`
- `app/src/main/jni/UZDoom/src/common/rendering/gles/gles_framebuffer.h`
- `app/src/main/jni/UZDoom/src/common/rendering/gles/gles_framebuffer.cpp`
- `app/src/main/jni/UZDoom/src/CMakeLists.txt`

Repository note for future agents:

- `/memories/repo/second-screen-hud.md`

## Android Layer

### Display detection

`SecondScreenPresentationController` owns Android secondary-display detection.

It uses `DisplayManager` and first searches `DisplayManager.DISPLAY_CATEGORY_PRESENTATION`. If that returns no suitable display, it falls back to any non-default display.

This matters because different dual-screen or external-display Android devices classify their secondary display differently.

```kotlin
private fun findSecondaryDisplay(): Display? {
    val presentationDisplay = displayManager
        .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    return presentationDisplay ?: displayManager.displays
        .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
}
```

### Presentation ownership

The secondary display is still Android-owned. A normal `Presentation` is shown on the secondary display. The important difference from the old implementation is that Android does not draw HUD pixels itself.

The `Presentation` window is fullscreen and black-backed:

```kotlin
window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
window?.setFlags(
    WindowManager.LayoutParams.FLAG_FULLSCREEN,
    WindowManager.LayoutParams.FLAG_FULLSCREEN,
)
```

The root view also uses a black background. This avoids flashes from transparent or default window content if the native surface has not presented yet.

### SurfaceView instead of Bitmap View

The presentation contains a fullscreen `SurfaceView`:

```kotlin
hudSurfaceView = SurfaceView(context).apply {
    keepScreenOn = true
    holder.setFormat(PixelFormat.OPAQUE)
    holder.addCallback(surfaceCallback)
}
```

Important details:

- `SurfaceView` creates a real platform surface suitable for native EGL rendering.
- `PixelFormat.OPAQUE` avoids alpha/composition surprises.
- The `SurfaceView` fills the full secondary display.
- There is no Android `Canvas`, no `Bitmap`, and no update timer.
- The surface lifecycle is event-driven through `SurfaceHolder.Callback`.

### Surface lifecycle bridge

The controller publishes the surface only when it is valid and has a positive size:

```kotlin
private fun publishSurface(holder: SurfaceHolder, width: Int? = null, height: Int? = null) {
    val surface = holder.surface
    val surfaceFrame = holder.surfaceFrame
    val surfaceWidth = width ?: surfaceFrame.width()
    val surfaceHeight = height ?: surfaceFrame.height()
    if (surface.isValid && surfaceWidth > 0 && surfaceHeight > 0) {
        onSecondScreenSurfaceChanged(surface, surfaceWidth, surfaceHeight)
    }
}
```

On destruction, dismiss, stop, and display change, the controller sends `null, 0, 0` to native code. This is essential. Native code must stop rendering into a destroyed surface before Android tears it down.

Places that clear the surface:

- `surfaceDestroyed`
- `SecondScreenPresentation.dismiss()`
- `SecondScreenPresentationController.stop()`
- `SecondScreenPresentationController.updatePresentation()` before replacing the presentation

### Activity wiring

`SDL3GameActivity` wires two independent callbacks:

```kotlin
SecondScreenPresentationController(
    context = this,
    onSecondScreenActiveChanged = { enabled ->
        (engineInfo as? UZDoomEngineInfo)?.setSecondScreenHudEnabled(enabled)
    },
    onSecondScreenSurfaceChanged = { surface, width, height ->
        (engineInfo as? UZDoomEngineInfo)?.setSecondScreenHudSurface(surface, width, height)
    },
)
```

These callbacks intentionally do different jobs:

- `setSecondScreenHudEnabled(true)` tells UZDoom to switch the main view to fullscreen mode and prepare lower-screen HUD rendering.
- `setSecondScreenHudSurface(surface, width, height)` gives UZDoom the actual native target it can render into.

The active flag can become true before the `SurfaceView` surface exists. Native code handles this by returning early until an active `ANativeWindow` is available.

## Kotlin to Native Bridge

`UZDoomEngineInfo.kt` contains a small JNI bridge object:

```kotlin
internal object UZDoomSecondScreenSurfaceBridge {
    external fun setSecondScreenHudSurface(surface: Surface, width: Int, height: Int)
    external fun clearSecondScreenHudSurface()
}
```

`UZDoomEngineInfo.setSecondScreenHudSurface` validates the Java `Surface` before crossing into native code:

```kotlin
fun setSecondScreenHudSurface(surface: Surface?, width: Int, height: Int) {
    if (surface == null || !surface.isValid || width <= 0 || height <= 0) {
        UZDoomSecondScreenSurfaceBridge.clearSecondScreenHudSurface()
    } else {
        UZDoomSecondScreenSurfaceBridge.setSecondScreenHudSurface(surface, width, height)
    }
}
```

Notes:

- The existing JNA registration remains for exported C functions such as `SetSecondScreenHudEnabled`.
- The `Surface` object is passed via JNI because JNA is not suitable for passing Java `Surface` handles to `ANativeWindow_fromSurface`.
- The native method names are generated from the Kotlin object name and package path:
  - `Java_com_mobilerpgpack_phone_engine_engineinfo_uzdoom_UZDoomSecondScreenSurfaceBridge_setSecondScreenHudSurface`
  - `Java_com_mobilerpgpack_phone_engine_engineinfo_uzdoom_UZDoomSecondScreenSurfaceBridge_clearSecondScreenHudSurface`

## Native Surface Ownership

The JNI implementation lives in `d_main.cpp` under `#if ANDROID`:

```cpp
ANativeWindow* window = surface != nullptr ? ANativeWindow_fromSurface(env, surface) : nullptr;
```

Important ownership rule:

- `ANativeWindow_fromSurface` returns a native window reference.
- That reference must be released with `ANativeWindow_release`.
- The render thread owns the active `ANativeWindow` reference after it is accepted.
- Pending windows are released if overwritten before becoming active.
- The previous active window is released only after the framebuffer has been told to stop using it.

The implementation uses pending and active slots:

```cpp
static ANativeWindow* uzSecondScreenHudPendingWindow = nullptr;
static bool uzSecondScreenHudSurfacePending = false;
static ANativeWindow* uzSecondScreenHudActiveWindow = nullptr;
```

This is necessary because Android surface callbacks run on the Android/UI side, while rendering must happen on the UZDoom render thread. Do not create or destroy EGL surfaces directly from the Java callback thread.

### Pending-to-active handoff

`D_SetSecondScreenHudPendingWindow` only stores the next window under a mutex:

```cpp
static void D_SetSecondScreenHudPendingWindow(ANativeWindow* window, int width, int height)
{
    std::lock_guard<std::mutex> lock(uzSecondScreenHudSurfaceMutex);
    if (uzSecondScreenHudPendingWindow != nullptr)
    {
        ANativeWindow_release(uzSecondScreenHudPendingWindow);
    }
    uzSecondScreenHudPendingWindow = window;
    uzSecondScreenHudPendingWidth = width > 0 ? width : 0;
    uzSecondScreenHudPendingHeight = height > 0 ? height : 0;
    uzSecondScreenHudSurfacePending = true;
}
```

`D_UpdateSecondScreenHudSurface` runs on the render path and commits pending state to active state. When the active native window changes, it calls `screen->SetSecondScreenNativeWindow` before releasing the previous `ANativeWindow`. The render path also passes the currently active native window to the framebuffer before drawing; the framebuffer returns early if the pointer and dimensions are unchanged.

If there was a previous active window, it is released only after the framebuffer has switched to the new target or to `nullptr`.

This avoids use-after-release bugs.

## Keeping the Primary Screen Fullscreen

`D_UpdateSecondScreenHudMode` changes the normal UZDoom view mode while the second-screen HUD is active:

```cpp
if (requested)
{
    uzSecondScreenSavedScreenBlocks = screenblocks;
    screenblocks = 12;
    R_SetViewSize(12);
    uzSecondScreenHudApplied = true;
}
else
{
    screenblocks = uzSecondScreenSavedScreenBlocks;
    R_SetViewSize(screenblocks);
    uzSecondScreenHudApplied = false;
}
```

`screenblocks = 12` makes the upper screen fullscreen. That removes the status bar from the upper/main display. The previous value is saved and restored when the second-screen HUD is disabled.

This is the cleanest way to remove the UZDoom status bar from the primary display without trying to surgically skip specific draw calls in the main 2D path.

## Preparing the Real UZDoom Automap Draw List

The current working lower-screen pass shows only the real UZDoom automap. It intentionally keeps the same timing and GLES presentation path as the verified statusbar-only baseline: a compact temporary `F2DDrawer`, aspect-preserving `Render2DToSecondScreen`, and a call before primary `End2DAndUpdate()`.

For the map, the temporary source keeps width at 320 and derives height from the secondary surface aspect ratio. On the verified device the secondary display is 1240x1080, so the map source becomes about 320x279. This avoids the top letterbox gap that a fixed 320x200 source produced on the taller lower display, without returning to the unstable full-secondary-surface drawer.

Core sequence in `D_RenderSecondScreenMapFrame`:

```cpp
F2DDrawer mapDrawer;
F2DDrawer* savedDrawer = twod;
const bool savedAutomapActive = automapactive;
const bool savedViewActive = viewactive;
int mapSourceWidth = uzSecondScreenHudRenderWidth;
int mapSourceHeight = uzSecondScreenHudRenderHeight;
#if ANDROID
if (uzSecondScreenHudSurfaceWidth > 0 && uzSecondScreenHudSurfaceHeight > 0)
{
    mapSourceHeight = int(double(mapSourceWidth) * double(uzSecondScreenHudSurfaceHeight) / double(uzSecondScreenHudSurfaceWidth) + 0.5);
}
#endif

mapDrawer.Begin(mapSourceWidth, mapSourceHeight);
mapDrawer.ClearClipRect();
twod = &mapDrawer;
if (uzSecondScreenMapStartedLevel != primaryLevel ||
    uzSecondScreenMapStartedWidth != mapSourceWidth ||
    uzSecondScreenMapStartedHeight != mapSourceHeight)
{
    primaryLevel->automap->startDisplay();
    uzSecondScreenMapStartedLevel = primaryLevel;
    uzSecondScreenMapStartedWidth = mapSourceWidth;
    uzSecondScreenMapStartedHeight = mapSourceHeight;
}
automapactive = true;
viewactive = false;
primaryLevel->automap->Drawer(mapSourceHeight);
mapDrawer.End();
automapactive = savedAutomapActive;
viewactive = savedViewActive;

twod = savedDrawer;
```

Important details:

- `twod` is temporarily redirected to the local drawer so automap code emits commands into the lower-screen draw list instead of the main-screen draw list.
- The lower pass temporarily sets `automapactive = true` and `viewactive = false`, then restores both values. This produces the full automap page without putting the upper screen into automap mode.
- `primaryLevel->automap->startDisplay()` is called only when the active level or lower source dimensions change, while `twod` points to the lower drawer. This initializes automap scale/location for the lower source dimensions without resetting zoom/pan every frame.
- `primaryLevel->automap->Drawer(mapSourceHeight)` draws a full-height automap source. No status bar calls are made in the lower pass.
- After drawing, `twod`, `automapactive`, and `viewactive` are restored so the main render path is left in its expected state.

The current virtual source dimensions are:

```cpp
static constexpr int uzSecondScreenHudRenderWidth = 320;
static constexpr int uzSecondScreenHudRenderHeight = 200;
```

The draw list is then passed to the framebuffer:

```cpp
screen->Render2DToSecondScreen(&mapDrawer, mapSourceWidth, mapSourceHeight);
```

The old `Render2DToBuffer` fallback still exists below this call. It is no longer used by the Android `Presentation` path, but it can still help debugging or non-GLES fallback experiments.

## Frame Timing Placement

The second-screen render call is placed near the end of the main frame, before overlays and `End2DAndUpdate()`:

```cpp
D_RenderSecondScreenMapFrame();
DrawOverlays();
End2DAndUpdate();
```

This placement has several advantages:

- The game state and automap state for the frame are already current.
- The lower-screen draw happens on the same render thread as the main renderer.
- The primary EGL surface can be restored before the normal primary-screen swap/update.
- It avoids a separate timer or Android-side render loop.

## Framebuffer API Extension

The base framebuffer interface was extended in `v_video.h`:

```cpp
virtual void SetSecondScreenNativeWindow(void* nativeWindow, int width, int height) {}
virtual bool Render2DToSecondScreen(F2DDrawer* drawer, int width, int height) { return false; }
```

This keeps the UZDoom game-loop code renderer-agnostic. GLES implements the methods. Other renderers can return `false` until they have their own implementation.

The GLES framebuffer declares:

```cpp
void SetSecondScreenNativeWindow(void* nativeWindow, int width, int height) override;
bool Render2DToSecondScreen(F2DDrawer* drawer, int width, int height) override;
```

Android-only state in `gles_framebuffer.h`:

```cpp
ANativeWindow* SecondScreenNativeWindow = nullptr;
EGLDisplay SecondScreenEglDisplay = nullptr;
EGLSurface SecondScreenEglSurface = nullptr;
int SecondScreenSurfaceWidth = 0;
int SecondScreenSurfaceHeight = 0;
```

Use `nullptr` for EGL handle checks in this codebase. The NDK EGL macros such as `EGL_NO_DISPLAY`, `EGL_NO_CONTEXT`, and `EGL_NO_SURFACE` expanded through `EGL_CAST(...)` in a way that caused compile errors in this build configuration.

## Creating the Secondary EGL Surface

The GLES implementation creates an EGL window surface for the Android `ANativeWindow`.

The most important nuance is EGL config selection. Do not choose an arbitrary config. The existing UZDoom GL context must be compatible with the secondary window surface. The implementation queries the current context's `EGL_CONFIG_ID`, enumerates configs, and picks the matching config:

```cpp
static EGLConfig FindCurrentEglConfig(EGLDisplay display, EGLContext context)
{
    EGLint configId = 0;
    if (display == nullptr || context == nullptr || !eglQueryContext(display, context, EGL_CONFIG_ID, &configId))
    {
        return nullptr;
    }

    EGLint configCount = 0;
    if (!eglGetConfigs(display, nullptr, 0, &configCount) || configCount <= 0)
    {
        return nullptr;
    }

    std::vector<EGLConfig> configs(configCount);
    if (!eglGetConfigs(display, configs.data(), configCount, &configCount))
    {
        return nullptr;
    }

    for (int i = 0; i < configCount; ++i)
    {
        EGLint currentConfigId = 0;
        if (eglGetConfigAttrib(display, configs[i], EGL_CONFIG_ID, &currentConfigId) && currentConfigId == configId)
        {
            return configs[i];
        }
    }

    return nullptr;
}
```

Before creating the surface, the implementation requests the Android buffer size:

```cpp
ANativeWindow_setBuffersGeometry(SecondScreenNativeWindow, SecondScreenSurfaceWidth, SecondScreenSurfaceHeight, 0);
```

Then it creates the surface:

```cpp
SecondScreenEglSurface = eglCreateWindowSurface(display, config, SecondScreenNativeWindow, nullptr);
```

After creation, it queries the actual EGL surface size and stores it:

```cpp
eglQuerySurface(display, SecondScreenEglSurface, EGL_WIDTH, &eglWidth);
eglQuerySurface(display, SecondScreenEglSurface, EGL_HEIGHT, &eglHeight);
```

This is important because Android may adjust the actual buffer dimensions.

## Rendering Into the Secondary EGL Surface

`OpenGLFrameBuffer::Render2DToSecondScreen` does the direct GPU presentation.

High-level flow:

1. Validate `GLRenderer`, drawer, dimensions, and native window.
2. Get current `EGLDisplay` and `EGLContext`.
3. Ensure the secondary `EGLSurface` exists.
4. Save previous EGL draw/read surfaces.
5. Save relevant GL state.
6. `eglMakeCurrent` to the secondary surface using the same context.
7. Clear the secondary surface to black.
8. Draw the prepared UZDoom 2D command list.
9. `eglSwapBuffers` on the secondary surface.
10. Restore the previous EGL surfaces.
11. Restore saved GL state and clear cached render-state material.

Core switch and draw:

```cpp
if (!eglMakeCurrent(display, SecondScreenEglSurface, SecondScreenEglSurface, context))
{
    return false;
}

glBindFramebuffer(GL_FRAMEBUFFER, 0);
glViewport(0, 0, SecondScreenSurfaceWidth, SecondScreenSurfaceHeight);
glDisable(GL_SCISSOR_TEST);
glClearColor(0.f, 0.f, 0.f, 1.f);
glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

::Draw2D(drawer, gl_RenderState, destinationX, destinationY, destinationWidth, destinationHeight);
glFlush();
const bool swapped = eglSwapBuffers(display, SecondScreenEglSurface) == EGL_TRUE;
```

Do not use `glFinish()` here. `glFlush()` is enough before `eglSwapBuffers` and avoids the hard GPU/CPU synchronization that contributed to flicker and stalls in the old implementation.

### Aspect and placement

The implementation preserves the lower source aspect ratio inside the secondary display:

```cpp
const float frameAspect = float(width) / float(height);
int destinationWidth = SecondScreenSurfaceWidth;
int destinationHeight = int(float(destinationWidth) / frameAspect + 0.5f);
if (destinationHeight > SecondScreenSurfaceHeight)
{
    destinationHeight = SecondScreenSurfaceHeight;
    destinationWidth = int(float(destinationHeight) * frameAspect + 0.5f);
}
const int destinationX = (SecondScreenSurfaceWidth - destinationWidth) / 2;
const int destinationY = 0;
```

This draws the lower source centered horizontally and aligned to the bottom in OpenGL window coordinates. In this GLES code path, viewport coordinates use GL's lower-left origin.

### State restoration

The renderer saves and restores:

- `EGL_DRAW` surface
- `EGL_READ` surface
- `GL_FRAMEBUFFER_BINDING`
- `GL_VIEWPORT`
- `GL_SCISSOR_BOX`
- scissor enabled state
- clear color

It also calls:

```cpp
gl_RenderState.ClearLastMaterial();
```

This matters because UZDoom's render state caches bound materials. After manually switching surfaces and drawing, cached state can become stale unless invalidated.

If `eglSwapBuffers` fails, the secondary surface is destroyed so it can be recreated on a later frame:

```cpp
if (!swapped)
{
    DestroySecondScreenSurface();
}
```

## Build System Detail

The UZDoom Android target must link both EGL and Android native-window APIs:

```cmake
target_link_libraries( zdoom ${PROJECT_LIBRARIES} lzma ${ZMUSIC_LIBRARIES} EGL android log )
```

Without `android`, symbols such as `ANativeWindow_fromSurface`, `ANativeWindow_release`, and `ANativeWindow_setBuffersGeometry` may fail to link.

## Why This Solves the Flicker

The old path crossed several asynchronous boundaries every frame:

```text
UZDoom GL render -> glFinish -> glReadPixels -> CPU array -> JNA -> Bitmap.setPixels -> Android View invalidate -> Canvas draw
```

The new path is much shorter and stays on the render thread/GPU:

```text
UZDoom draw list -> same GL context -> secondary EGL window surface -> eglSwapBuffers
```

The secondary `eglSwapBuffers` should run before the primary frame completes `End2DAndUpdate()`, matching the staged statusbar-only checkpoint that was verified on the device. The unstable changes were full-surface lower composition, present-after-primary-swap timing, and offscreen texture-present; do not reintroduce those as flicker workarounds.

Benefits:

- No per-frame CPU pixel copy.
- No per-frame Android bitmap upload.
- No Android `Canvas` drawing schedule involved.
- No FPS reduction or artificial delay.
- The lower-screen presentation is synchronized with the engine render loop.
- The same engine code that normally draws the automap is still used.

## Remaining Fallback Code

Some old frame-copy APIs still exist:

- `GetSecondScreenHudFrameWidth`
- `GetSecondScreenHudFrameHeight`
- `CopySecondScreenHudFrame`
- `Render2DToBuffer`

They are no longer the active Android second-screen presentation path. Do not rebuild the lower screen around these APIs unless explicitly debugging or supporting a non-GLES fallback.

If future cleanup removes them, verify that no external debug tooling still depends on the exported symbols.

## Porting This Pattern to Other Engines

This architecture can be reused in other engines if they meet these conditions:

- The engine renders with EGL/OpenGL ES on Android.
- The engine has an existing render thread and GL context.
- The engine can prepare the lower-screen content as a renderable native draw list, texture, scene, or pass.
- The engine can temporarily render to another EGL window surface and restore the original surface before continuing.

### Generic porting steps

1. Add Android secondary display detection with `DisplayManager`.
2. Show a `Presentation` on the secondary display.
3. Put an opaque fullscreen `SurfaceView` in the presentation.
4. Pass `Surface`, width, and height to native code through JNI.
5. Convert `Surface` to `ANativeWindow` with `ANativeWindow_fromSurface`.
6. Store the new window as pending state and let the render thread accept it.
7. On the render thread, create an EGL window surface from the native window.
8. Use the current EGL context's compatible EGL config.
9. During the frame, render the desired second-screen content into the secondary surface.
10. `eglSwapBuffers` the secondary surface.
11. Restore the primary EGL draw/read surfaces and GL state.
12. Clear the native window on surface destruction, presentation dismissal, activity pause, and display replacement.

### What each engine must customize

The Android and EGL surface handoff is reusable. The engine-specific part is the content generation:

- UZDoom builds an `F2DDrawer` list and calls the real automap drawer or status bar drawer, depending on the active lower-screen content.
- A different Doom-family engine might render its status bar or automap pass into a secondary surface.
- An emulator might render a second virtual screen directly into the surface.
- A 3D engine might render a separate camera, UI pass, or cockpit panel.

The key is to move the real native render output, not reconstruct it in Android UI.

### If an engine uses textures instead of direct draw lists

For engines that already render the second-screen content into a GL texture, two variants are possible:

- Draw a textured quad into the secondary EGL surface using the same context.
- Use a Java/Kotlin `GLSurfaceView` renderer with shared contexts, similar to melonDS Android.

The direct EGL surface approach used here avoids cross-context texture sharing. That is why it was a good fit for UZDoom: SDL owns one GL context, and the code can temporarily make that context current to a second EGL surface.

## Important Pitfalls

### Do not render from the Android UI thread

The `SurfaceHolder.Callback` thread only hands off the `Surface`. EGL surface creation, `eglMakeCurrent`, drawing, and swapping happen on the engine render thread.

### Do not release an active `ANativeWindow` too early

The active native window remains valid until the framebuffer has switched away from it. Release the old active window after calling `screen->SetSecondScreenNativeWindow(newWindowOrNull, ...)`.

### Do not use arbitrary EGL config selection

The secondary EGL surface must be compatible with the current context. Query `EGL_CONFIG_ID` from the current context and create the second window surface with that config.

### Do not leave the secondary EGL surface current

Always restore `EGL_DRAW` and `EGL_READ` to the previous primary surfaces before the main renderer continues.

### Do not rely on Android view invalidation for frame pacing

No `Handler.postDelayed`, no `invalidate`, no `Bitmap.setPixels`. The engine render loop decides when the secondary surface updates.

### Avoid `glReadPixels` for gameplay presentation

`glReadPixels` is acceptable for screenshots, diagnostics, or a temporary proof of concept. It is not suitable for a continuously updating gameplay-grade second screen.

### Avoid `glFinish`

`glFinish` serializes CPU and GPU work. It is a common source of stutter and should not be used in the live second-screen render path.

### Restore enough GL state

At minimum, restore framebuffer binding, viewport, scissor box, scissor enabled state, clear color, and renderer cached material state. If future rendering bugs appear, inspect additional GL state mutated by the second-screen pass.

### Surface size can change

Android may report changes through `surfaceChanged`, and EGL may report a size different from the requested buffer geometry. Always store and use the queried EGL surface size when available.

### Vulkan is not implemented by this path

This implementation is GLES-specific. A Vulkan version would need a separate Android surface/swapchain path and proper synchronization. Do not assume the GLES EGL approach works for Vulkan.

## Testing Checklist

Build:

```bash
./gradlew assembleFdroidDebug -Pandroid.injected.build.abi=arm64-v8a
```

Install:

```bash
./gradlew installFdroidDebug -Pandroid.injected.build.abi=arm64-v8a
```

Symbol check:

```bash
nm -D app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libuzdoom.so \
  | grep -E 'UZDoomSecondScreenSurfaceBridge|SetSecondScreenHudEnabled|CopySecondScreenHudFrame'
```

Expected symbols include:

- `Java_com_mobilerpgpack_phone_engine_engineinfo_uzdoom_UZDoomSecondScreenSurfaceBridge_setSecondScreenHudSurface`
- `Java_com_mobilerpgpack_phone_engine_engineinfo_uzdoom_UZDoomSecondScreenSurfaceBridge_clearSecondScreenHudSurface`
- `SetSecondScreenHudEnabled`

Manual device checks:

- Launch UZDoom on the dual-screen Android device.
- Verify the secondary display is detected and a `Presentation` appears.
- Verify the upper display is fullscreen and does not show the status bar.
- Verify the lower display shows only the real UZDoom automap and no status bar.
- Verify the upper display remains playable and fullscreen.
- Verify there is no visible lower-screen flicker.
- Rotate/resume/pause if the device supports it and verify surface recreation does not crash.
- Disconnect or disable the secondary display and verify native rendering stops cleanly.

Useful log tags and symptoms:

- Android controller logs under `SecondScreen`.
- If the lower screen stays black, check that `surfaceCreated`/`surfaceChanged` fires and JNI symbols are exported.
- If there is a native crash on display removal, inspect `ANativeWindow` release ordering.
- If the upper screen corrupts after lower-screen drawing, inspect EGL/GL state restoration.
- If `eglCreateWindowSurface` fails, verify the EGL config lookup and the surface validity/size.

## Future Improvements

Potential cleanup and extensions:

- Remove the old bitmap/readback APIs if no debug tooling needs them.
- Add explicit logging around EGL surface creation failures in `Render2DToSecondScreen`.
- Add renderer capability reporting so Kotlin can know whether native second-screen rendering is available.
- Add a Vulkan implementation using a secondary Android surface/swapchain if Vulkan becomes required.
- Generalize the controller so other engines can register a native surface consumer without depending on UZDoom-specific names.
- Add an engine-agnostic interface such as `ISecondScreenNativeSurfaceConsumer` on the Kotlin side.

## Quick Summary for Future Agents

If you need to modify or port this feature, keep this invariant:

```text
Android owns display discovery and Surface lifecycle.
The native engine owns rendering.
The frame must stay on the GPU.
Do not bring back glReadPixels/Bitmap for live second-screen presentation.
```

The minimum viable architecture is:

```text
DisplayManager -> Presentation -> SurfaceView -> JNI Surface -> ANativeWindow
  -> EGL window surface -> engine render thread -> native draw pass -> eglSwapBuffers
```

For UZDoom specifically:

```text
screenblocks=12 removes status bar from upper screen.
F2DDrawer + primaryLevel->automap->Drawer(mapSourceHeight) creates the exact lower automap page.
Render2DToSecondScreen presents the compact aspect-matched lower source directly to the lower display with aspect preservation.
```