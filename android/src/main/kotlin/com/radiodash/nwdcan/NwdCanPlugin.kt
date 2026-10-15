package com.radiodash.nwdcan

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * nwd_can  –  reads live vehicle data from the NWD / Digital Star head-unit's
 * own CanService (package com.nwd.can.setting), using the published
 * third-party SDK channel.  No wires, no OBD dongle – pure IPC to the unit's
 * own CAN service.
 *
 * Everything here is reconstructed from the vendor's own client SDK so that the
 * binder transaction codes and the wire protocol match the service exactly.
 */
class NwdCanPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    companion object {
        private const val TAG = "NwdCan"
        private const val PLUGIN_VER = "diag1"

        // ---- the service we bind to (from CanConstant / SourceConstant) ----
        private const val SERVICE_PKG = "com.nwd.can.setting"
        private const val SERVICE_CLS = "com.nwd.can.service.CanService"
        private const val ACTION_SERVICE = "com.nwd.can.service.impl.carcase.ACTION_CAN_SERVICE"

        // ---- AIDL descriptors (must match byte-for-byte) ----
        private const val FEATURE_DESC = "com.nwd.can.sdk.outer.adil.ICanRemote4OuterFeature"
        private const val CALLBACK_DESC = "com.nwd.can.sdk.outer.adil.ICanRemoteModelCallback"

        // ---- transaction codes on ICanRemote4OuterFeature.Stub ----
        private const val TX_INIT_SDK_CFG = 2
        private const val TX_ADD_AIR = 9
        private const val TX_ADD_DOOR = 11
        private const val TX_ADD_RADAR = 15
        private const val TX_ADD_CARINFO = 17
        private const val TX_ADD_CAN_CARINFO = 27

        // ---- transaction codes the service calls back on (our Binder) ----
        private const val TX_CB_CAN_DATA = 1   // onDistributeCanData(byte[])
        private const val TX_CB_CAR_INFO = 2   // onDistributeCarInfo(CarInfo) -- typed, ignored here
        private const val TX_CB_AMP = 3
        private const val TX_CB_TPMS = 4
        private const val INTERFACE_TRANSACTION = 0x5f4e5446 // not used; placeholder

        // ---- published third-party channel (from initSdkCfg validation) ----
        private const val SDK_CHANNEL = "nwdthirdapp"
        private const val SDK_SECRET = "d39df3d908cf7136227987e37d5b2c7d"
        private const val SDK_PARAM_JOIN: Byte = 0
        private const val SDK_DESC = "radio dashboard"

        // remote-protocol frame marker
        private const val MARKER = 110 // 0x6E
    }

    private var ctx: Context? = null
    private var methods: MethodChannel? = null
    private var events: EventChannel? = null
    private var sink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())

    // All binder IPC runs on this background thread so the UI thread never
    // blocks on a cross-process call to the CAN service.
    private var ioThread: HandlerThread? = null
    private var io: Handler? = null

    private var feature: IBinder? = null
    private var bound = false

    // ---------- Flutter plumbing ----------
    override fun onAttachedToEngine(b: FlutterPlugin.FlutterPluginBinding) {
        ctx = b.applicationContext
        methods = MethodChannel(b.binaryMessenger, "nwd_can/methods").also { it.setMethodCallHandler(this) }
        events = EventChannel(b.binaryMessenger, "nwd_can/events").also { it.setStreamHandler(this) }
    }

    override fun onDetachedFromEngine(b: FlutterPlugin.FlutterPluginBinding) {
        stop()
        methods?.setMethodCallHandler(null)
        events?.setStreamHandler(null)
        methods = null; events = null; ctx = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> { start(); result.success(bound) }
            "stop" -> { stop(); result.success(true) }
            "isConnected" -> result.success(feature != null)
            else -> result.notImplemented()
        }
    }

    override fun onListen(args: Any?, s: EventChannel.EventSink?) { sink = s; start() }
    override fun onCancel(args: Any?) { sink = null }

    private fun emit(map: Map<String, Any?>) { main.post { sink?.success(map) } }

    // ---------- bind / unbind ----------
    private fun start() {
        val c = ctx ?: return
        if (bound) return
        if (ioThread == null) {
            ioThread = HandlerThread("nwd-can-io").apply { start() }
            io = Handler(ioThread!!.looper)
        }
        val intent = Intent(ACTION_SERVICE).apply {
            setPackage(SERVICE_PKG)
            component = ComponentName(SERVICE_PKG, SERVICE_CLS)
        }
        try {
            bound = c.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "bindService -> $bound")
            if (!bound) emit(mapOf("type" to "status", "connected" to false, "error" to "bindService returned false"))
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            emit(mapOf("type" to "status", "connected" to false, "error" to (e.message ?: "bind exception")))
        }
    }

    private fun stop() {
        val c = ctx
        if (bound && c != null) { try { c.unbindService(conn) } catch (_: Exception) {} }
        bound = false; feature = null
        io = null
        ioThread?.quitSafely()
        ioThread = null
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            feature = binder
            Log.d(TAG, "onServiceConnected $binder")
            // Run registration + init OFF the UI thread (this callback is on main).
            (io ?: main).post {
                try {
                    registerCallback(TX_ADD_AIR)
                    registerCallback(TX_ADD_DOOR)
                    registerCallback(TX_ADD_RADAR)
                    registerCallback(TX_ADD_CARINFO)
                    registerCallback(TX_ADD_CAN_CARINFO)
                    initSdkCfg()
                    emit(mapOf("type" to "status", "connected" to true, "v" to PLUGIN_VER))
                } catch (e: Exception) {
                    Log.e(TAG, "register/init failed", e)
                    emit(mapOf("type" to "status", "connected" to false, "error" to (e.message ?: "register failed")))
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            feature = null
            emit(mapOf("type" to "status", "connected" to false))
        }
    }

    // ---------- outbound binder calls ----------
    private fun registerCallback(code: Int) {
        val f = feature ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FEATURE_DESC)
            data.writeStrongBinder(callback)
            val ok = f.transact(code, data, reply, 0)
            Log.e(TAG, "registerCallback code=$code transactOk=$ok")
            reply.readException()
        } finally { reply.recycle(); data.recycle() }
    }

    private fun initSdkCfg() {
        val f = feature ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FEATURE_DESC)
            data.writeString(SDK_CHANNEL)
            data.writeString(SDK_SECRET)
            data.writeByte(SDK_PARAM_JOIN)
            data.writeString(SDK_DESC)
            val ok = f.transact(TX_INIT_SDK_CFG, data, reply, 0)
            Log.e(TAG, "initSdkCfg transactOk=$ok")
            reply.readException()
        } finally { reply.recycle(); data.recycle() }
    }

    // ---------- the callback binder the service pushes data into ----------
    private var rxCount = 0

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // DIAGNOSTIC: prove whether the service is calling us at all.
            rxCount++
            Log.e(TAG, "onTransact code=$code rx=$rxCount")
            emit(mapOf("type" to "rx", "code" to code, "n" to rxCount))
            if (code in 1..0x00ffffff) {
                try { data.enforceInterface(CALLBACK_DESC) } catch (e: Exception) {
                    Log.e(TAG, "enforceInterface failed", e)
                }
            }
            return when (code) {
                TX_CB_CAN_DATA -> {
                    val bytes = data.createByteArray()
                    reply?.writeNoException()
                    if (bytes != null) handleFrame(bytes)
                    true
                }
                TX_CB_CAR_INFO -> {
                    try {
                        val present = data.readInt()
                        if (present != 0) emit(parseTypedCarInfo(data))
                    } catch (e: Exception) {
                        Log.e(TAG, "carinfo parcel decode", e)
                    }
                    reply?.writeNoException()
                    true
                }
                TX_CB_AMP, TX_CB_TPMS -> {
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    // ============================================================
    //  Decode – ported 1:1 from RemoteProtocalParse (vendor SDK)
    // ============================================================
    private fun u(b: ByteArray, i: Int): Int { if (i < 0 || i >= b.size) return 0; val v = b[i].toInt(); return if (v < 0) v + 256 else v }

    // byteArrToInt(bArr, off, len, little) – matches JavaDecode exactly
    private fun dec(b: ByteArray, off: Int, len: Int, little: Boolean): Int {
        if (off >= b.size - 1) return 0
        var r = 0
        for (k in 0 until len) {
            var v = if (off + k < b.size) b[off + k].toInt() else 0
            if (v < 0) v += 256
            r = r or (v shl (if (little) k * 8 else (len - k - 1) * 8))
        }
        return r
    }

    private fun handleFrame(b: ByteArray) {
        if (b.isEmpty()) return
        if (u(b, 0) != MARKER) {
            // not the normalised remote protocol – forward raw for mapping
            emit(mapOf("type" to "raw", "hex" to b.joinToString("") { "%02x".format(it.toInt() and 0xff) }))
            return
        }
        try {
            when (u(b, 1)) {
                1 -> emit(parseAc(b))
                2 -> emit(parseCarInfo(b))
                3 -> emit(parseDoor(b))
                4 -> emit(parseRadar(b))
                else -> emit(mapOf("type" to "raw", "sub" to u(b, 1),
                    "hex" to b.joinToString("") { "%02x".format(it.toInt() and 0xff) }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode error", e)
        }
    }

    private fun parseCarInfo(b: ByteArray): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        m["type"] = "carInfo"
        fun i16(o: Int) = dec(b, o, 2, false).let { if (it == 65535) -1 else it }
        fun i24(o: Int) = dec(b, o, 3, false).let { if (it == 16777215) -1 else it }
        fun f8(o: Int) = (u(b, o)).let { if (it == 255) -1f else it.toFloat() }

        m["instantSpeed"] = i16(3)          // km/h
        m["engineSpeed"] = i16(5)           // RPM
        m["drivingMile"] = i24(7)
        m["canDriverMileage"] = i16(10)
        m["frontRightSafetyBelt"] = b[12].toInt()
        m["safetyBelt"] = b[13].toInt()
        m["batteryCapacity"] = u(b, 14)
        m["handbrakeFailure"] = b[15].toInt()
        m["handbrake"] = b[16].toInt()
        m["fuelLevel"] = f8(17)             // %
        m["batteryVoltage"] = f8(18)        // V
        m["doorLockFL"] = b[19].toInt()
        m["doorLockFR"] = b[20].toInt()
        m["doorLockRL"] = b[21].toInt()
        m["doorLockRR"] = b[22].toInt()
        m["doorLockBoot"] = b[23].toInt()
        m["highBeam"] = b[24].toInt()
        m["dippedBeam"] = b[25].toInt()
        m["frontFog"] = b[26].toInt()
        m["rearFog"] = b[27].toInt()
        m["widthLamp"] = b[28].toInt()
        m["hazard"] = b[29].toInt()
        m["rightTurn"] = b[30].toInt()
        m["leftTurn"] = b[31].toInt()
        m["avgSpeed1"] = i16(32)
        m["avgSpeed2"] = i16(34)
        m["avgSpeed3"] = i16(36)
        m["tripMile2"] = i24(38)
        m["tripMile3"] = i24(41)
        m["instantOil"] = f8(44)
        m["avgOil1"] = f8(45)
        m["avgOil2"] = f8(46)
        m["avgOil3"] = f8(47)
        m["driveTime1"] = i24(54)
        m["driveTime2"] = i24(57)
        m["driveTime3"] = i24(60)
        if (b.size > 103) {
            m["gear"] = b[74].toInt()
            m["driveMode"] = b[80].toInt()
            m["batteryCoolantTemp"] = b[93].toInt()
            m["driveSystemCoolantTemp"] = b[100].toInt()
        }
        if (b.size > 116) {
            m["coolantTempStatus"] = b[106].toInt()
            m["maintenanceLight"] = b[112].toInt()
            m["checkEngine"] = b[115].toInt()
        }
        return m
    }

    private fun bit(v: Int, n: Int) = ((v shr n) and 1) == 1

    private fun tempStr(raw: Int, fahrenheit: Boolean): String {
        val s = raw.toByte().toInt()
        return when {
            s == 0 -> "Low"
            s == -1 -> "High"
            s == -2 -> "-.--"
            else -> "%.1f%s".format((raw and 0xff) / 2.0f, if (fahrenheit) "℉" else "℃")
        }
    }

    private fun parseAc(b: ByteArray): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        m["type"] = "ac"
        val b3 = u(b, 3)
        m["acSwitch"] = bit(b3, 7)
        m["acMode"] = bit(b3, 6)
        m["acAuto"] = bit(b3, 5)
        m["recirculate"] = bit(b3, 4)
        m["dual"] = bit(b3, 0)
        val b2 = u(b, 4)
        m["frontDefog"] = bit(b2, 7)
        m["rearDefog"] = bit(b2, 6)
        m["maxAc"] = bit(b2, 5)
        m["econ"] = bit(b2, 4)
        val b5 = u(b, 5)
        m["fanLevel"] = b5 and 15
        val fUnit = ((u(b, 6) shr 2) and 1)
        val isF = fUnit != 0
        m["seatCoolL"] = (u(b, 8) shr 4) and 15
        m["seatCoolR"] = u(b, 8) and 15
        m["seatHeatL"] = (u(b, 9) shr 4) and 15
        m["seatHeatR"] = u(b, 9) and 15
        m["tempLeft"] = tempStr(u(b, 10), isF)
        m["tempRight"] = tempStr(u(b, 11), isF)
        return m
    }

    private fun parseDoor(b: ByteArray): Map<String, Any?> {
        val v = u(b, 3)
        return mapOf(
            "type" to "door",
            "frontLeft" to bit(v, 7),
            "frontRight" to bit(v, 6),
            "rearLeft" to bit(v, 5),
            "rearRight" to bit(v, 4),
            "trunk" to bit(v, 3),
            "bonnet" to bit(v, 2)
        )
    }

    private fun parseRadar(b: ByteArray): Map<String, Any?> {
        return mapOf(
            "type" to "radar",
            "rearLeftLvl" to b[3].toInt(),
            "rearLeftCenterLvl" to b[4].toInt(),
            "rearRightCenterLvl" to b[5].toInt(),
            "rearRightLvl" to b[6].toInt(),
            "frontLeftLvl" to b[7].toInt(),
            "frontLeftCenterLvl" to b[8].toInt(),
            "frontRightCenterLvl" to b[9].toInt(),
            "frontRightLvl" to b[10].toInt()
        )
    }

    /**
     * Decode the typed CarInfo Parcelable. Reads every field in the EXACT order
     * of CarInfo.writeToParcel from the unit's own SDK, keeping the useful ones.
     * Reading stops after the outside/water temperature strings — the fields we
     * need are all before that point.
     */
    private fun parseTypedCarInfo(p: Parcel): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        m["type"] = "carInfo"
        p.readInt(); p.readInt(); p.readInt(); p.readInt()        // mDrivingMile, 1,2,3
        p.readInt(); p.readInt(); p.readInt()                     // mCanDriverMileage,2,3
        p.readFloat(); p.readFloat(); p.readFloat()               // mElecPow1,2,3
        p.readInt(); p.readInt(); p.readInt()                     // mElecCanDriver1,2,3
        p.readFloat(); p.readFloat()                              // mTRIPAMile, mTRIPBMile
        m["instantSpeed"] = p.readInt()                           // mInstantanSpeed (km/h)
        p.readInt(); p.readInt(); p.readInt()                     // mEquallySpeed,2,3
        p.readByte()                                              // mSpeedUnit
        m["driveTime1"] = p.readInt()                             // mDriverTime1
        p.readInt(); p.readInt()                                  // mDriverTime2,3
        m["engineSpeed"] = p.readInt()                            // mEngineSpeed (RPM)
        m["coolantTemp"] = p.readFloat()                          // mCoolantTemp (deg C)
        p.readFloat()                                             // mInstantanOil
        p.readFloat(); p.readFloat(); p.readFloat()               // mAverageOil,2,3
        p.readByte()                                              // mOilConsumptionUnit
        m["fuelLevel"] = p.readFloat()                            // mOilSurplus (%)
        p.readByte()                                              // mOilLowWarning
        m["batteryVoltage"] = p.readFloat()                       // mBatteryVoltage (V)
        p.readFloat()                                             // mElectric
        p.readByte()                                              // mBatteryVoltageStatus
        p.readInt()                                               // mBatteryCapacity
        p.readByte()                                              // mSafetyBelt
        p.readByte()                                              // mFrontRightSafetyBelt
        m["handbrake"] = p.readByte().toInt()                     // mHandbrake
        p.readByte()                                              // mCleaningLiquid
        p.readInt(); p.readInt(); p.readInt(); p.readInt(); p.readInt() // door locks FL,FR,RL,RR,boot
        p.readByte(); p.readByte(); p.readByte(); p.readByte()    // highbeam, dipped, beforeFog, afterFog
        m["rightTurn"] = p.readByte().toInt()                     // mRightTurnSignal
        m["leftTurn"] = p.readByte().toInt()                      // mLeftTurnSignal
        p.readByte(); p.readByte(); p.readByte(); p.readByte()    // hazard, big, small, widthLight
        p.readByte(); p.readByte(); p.readByte()                  // backLight, brakeLight, cautionLight
        p.readByte()                                              // mComfurtableUnit
        p.readInt()                                               // mComfurtableValue
        p.readByte(); p.readByte(); p.readByte()                  // comfMax, currentMachineOil, mileageUnit
        p.readByte(); p.readInt(); p.readByte(); p.readInt(); p.readByte() // machineOilCheck sign/day/daySign/mile/mileSign
        p.readByte(); p.readInt(); p.readByte(); p.readInt(); p.readByte() // tireCheck sign/day/daySign/mile/mileSign
        p.readByte(); p.readByte(); p.readInt(); p.readByte(); p.readInt(); p.readByte() // carCheck sign/month/day/daySign/mile/mileSign
        p.readByte()                                              // mTempUnit
        m["outsideTemp"] = p.readString()                        // mstrOuttemp (e.g. "25C")
        m["waterTempStr"] = p.readString()                       // mstrWaterTemp
        return m
    }
}
