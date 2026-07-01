package momoi.mod.qqpro.hook.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import momoi.mod.qqpro.util.Utils

/**
 * 蓝牙耳机路由 — in-call audio routing. QQ's native routing (`C2COperatorImpl.c(int)`) only toggles
 * speaker vs. earpiece and never engages a Bluetooth headset, so a call never reaches a paired BT
 * headset. This helper drives the [AudioManager] into communication mode and routes to a chosen output:
 *
 * - **API 31+ (S)**: the modern [AudioManager.setCommunicationDevice] / [AudioManager.getAvailableCommunicationDevices].
 * - **older**: [AudioManager.startBluetoothSco] + [AudioManager.setBluetoothScoOn] for BT, and
 *   `setSpeakerphoneOn` for speaker/earpiece.
 *
 * [enter] auto-selects a connected BT headset on call connect; [route] backs the in-call output
 * selector; [release] restores normal audio on call end. All best-effort + idempotent — the watch's
 * audio HAL varies, so failures are logged and swallowed rather than crashing the call.
 */
object CallAudioRouter {

    enum class Route { BLUETOOTH, SPEAKER, EARPIECE }

    private var focusRequest: AudioFocusRequest? = null
    private var entered = false

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Enter communication mode and auto-route to a BT headset if one is connected. Idempotent. */
    fun enter(ctx: Context) {
        val audio = am(ctx)
        runCatching {
            if (!entered) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                requestFocus(audio)
                entered = true
            }
            if (hasBluetooth(ctx)) route(ctx, Route.BLUETOOTH)
        }.onFailure { Utils.log("CallAudioRouter: enter failed: $it") }
    }

    /** Route call audio to [target]. Returns true if the route was applied. */
    fun route(ctx: Context, target: Route): Boolean {
        val audio = am(ctx)
        return runCatching {
            if (audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) routeModern(audio, target)
            else routeLegacy(audio, target)
            Utils.log("CallAudioRouter: routed to $target")
            true
        }.onFailure { Utils.log("CallAudioRouter: route $target failed: $it") }.getOrDefault(false)
    }

    /** Whether a Bluetooth SCO headset is currently available to route to. */
    fun hasBluetooth(ctx: Context): Boolean = runCatching {
        val audio = am(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            @Suppress("DEPRECATION")
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        }
    }.getOrDefault(false)

    /** Whether this watch even has an earpiece (many round watches are speaker-only). */
    fun hasEarpiece(ctx: Context): Boolean = runCatching {
        am(ctx).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
    }.getOrDefault(false)

    /** The routes worth offering in the in-call selector, given what hardware is present. */
    fun availableRoutes(ctx: Context): List<Route> = buildList {
        if (hasBluetooth(ctx)) add(Route.BLUETOOTH)
        add(Route.SPEAKER)
        if (hasEarpiece(ctx)) add(Route.EARPIECE)
    }

    /** Restore normal audio on call end. */
    fun release(ctx: Context) {
        val audio = am(ctx)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (audio.isBluetoothScoOn) { audio.isBluetoothScoOn = false; audio.stopBluetoothSco() }
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            focusRequest?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audio.abandonAudioFocusRequest(it) }
            focusRequest = null
            audio.mode = AudioManager.MODE_NORMAL
        }.onFailure { Utils.log("CallAudioRouter: release failed: $it") }
        entered = false
    }

    private fun routeModern(audio: AudioManager, target: Route) {
        val wantType = when (target) {
            Route.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            Route.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            Route.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        val device = audio.availableCommunicationDevices.firstOrNull { it.type == wantType }
        if (device != null) audio.setCommunicationDevice(device)
        else audio.clearCommunicationDevice()
    }

    @Suppress("DEPRECATION")
    private fun routeLegacy(audio: AudioManager, target: Route) {
        when (target) {
            Route.BLUETOOTH -> {
                audio.isSpeakerphoneOn = false
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
            }
            Route.SPEAKER -> {
                if (audio.isBluetoothScoOn) { audio.isBluetoothScoOn = false; audio.stopBluetoothSco() }
                audio.isSpeakerphoneOn = true
            }
            Route.EARPIECE -> {
                if (audio.isBluetoothScoOn) { audio.isBluetoothScoOn = false; audio.stopBluetoothSco() }
                audio.isSpeakerphoneOn = false
            }
        }
    }

    private fun requestFocus(audio: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            audio.requestAudioFocus(req)
            focusRequest = req
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }
}
