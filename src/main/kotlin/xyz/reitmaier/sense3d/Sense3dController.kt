package xyz.reitmaier.sense3d
import com.pi4j.io.gpio.digital.*
import com.pi4j.io.i2c.I2C
import org.slf4j.LoggerFactory
import java.io.IOException

@OptIn(ExperimentalUnsignedTypes::class)
class Sense3dController(private val i2c: I2C, private val reset: DigitalOutput, private val ts: DigitalMultipurpose) {
    val logger = LoggerFactory.getLogger(Sense3dController::class.java)

    private val FLAG_FIRMWARE_INFO = 0x83u
    private val FLAG_SENSOR_DATA = 0x91u

    private val S3D_DATA_GESTURE = 1u shl 1
    private val S3D_DATA_TOUCH = 1u shl 2
    private val S3D_DATA_AIRWHEEL = 1u shl 3
    private val S3D_DATA_XYZ = 1u shl 4

    private val S3D_SYS_POSITION = 1u
    private val S3D_SYS_AIRWHEEL = 1u shl 1

    private val S3D_PAYLOAD_GESTURE = 10 // 4 bytes
    private val S3D_PAYLOAD_TOUCH = 14 // 4 bytes
    private val S3D_PAYLOAD_AIRWHEEL = 18 // 2 bytes
    private val S3D_PAYLOAD_X = 20 // 2 bytes
    private val S3D_PAYLOAD_Y = 22 // 2 bytes
    private val S3D_PAYLOAD_Z = 24 // 2 bytes

    private var pollingThread: Thread? = null
    private var running = false


    // callbacks
    private var onGestureListener: OnGestureListener? = null
    private var onMoveListener: OnMoveListener? = null
    private var onAirWheelListener: OnAirWheelListener? = null
    private var onTouchTapListener: OnTouchTapListener? = null

    enum class GestureType {
        GARBAGE,
        FLICK_WEST_TO_EAST,
        FLICK_EAST_TO_WEST,
        FLICK_SOUTH_TO_NORTH,
        FLICK_NORTH_TO_SOUTH,
        CIRCLE_CLOCKWISE,
        CIRCLE_COUNTERCLOCKWISE,
    }

    enum class TouchType {
        DOUBLE_TAP_CENTER,
        DOUBLE_TAP_EAST,
        DOUBLE_TAP_NORTH,
        DOUBLE_TAP_WEST,
        DOUBLE_TAP_SOUTH,
        TAP_CENTER,
        TAP_EAST,
        TAP_NORTH,
        TAP_WEST,
        TAP_SOUTH,
        TOUCH_CENTER,
        TOUCH_EAST,
        TOUCH_NORTH,
        TOUCH_WEST,
        TOUCH_SOUTH,
    }

    // Keep track of Airwheel Rotation
    var lastRotation = 0

    /**
     *
     * Initializes the MGC3130 Chipset
     * @return The [FirmwareInfo] of the chipset
     * @throws Exception if chipset fails to initialise
     */
    fun init() : FirmwareInfo {
        logger.info("Initializing MGC3130 Chipset...")
        reset.low()
        Thread.sleep(40)
        reset.high()
        Thread.sleep(40)

        val data = readMsg(132)

        if(data[3] != FLAG_FIRMWARE_INFO.toUByte()) {
            logger.error("Unexpected Message Contents")
            logger.error("Did not receive firmware info")
            logger.error("... Initialisation of MGC3130 Chipset failed.")
            throw Exception("Failed to initialize MGC3130 Chipset")
        }
        val fw = parseFirmwareInfo(data)

        Thread.sleep(200)
        // toByte since jvm uses signed bytes, rather than unsigned bytes
        val dataOutputLock = byteArrayOf(0x10, 0x00, 0x00,
                0xa2.toByte(), 0xa1.toByte(), 0x00, 0x00, 0x00, 0x1f, 0x00, 0x00, 0x00, 0xff.toByte(),
                0xff.toByte(), 0xff.toByte(), 0xff.toByte()
        )
        logger.debug("Locking data output for\n 0: DSP Status\n 1: Gesture Data\n 2: TouchInfo\n 3: AirWheelInfo\n 4: xyzPosition")
        i2c.write(dataOutputLock)
        Thread.sleep(100)


        val autoCalibration = byteArrayOf(0x10, 0x00, 0x00,
                0xa2.toByte(), 0x80.toByte(), 0x00, 0x00, 0x00, 0x3f, 0x00, 0x00, 0x00, 0x3f, 0x00, 0x00, 0x00)
        logger.debug("Enable auto-calibration for:\n Bit 1: gesture-triggered\n Bit 2: negative\n Bit 3: idle\n Bit 4: invalid values, if values completely out of range\n Bit 5: triggered by AFA (Automati Frequency Adjustment)")
        i2c.write(autoCalibration)

        logger.info("... Initialisation of MGC3130 Chipset complete.")

        return fw
    }

    /**
     * Start polling sensing board.
     * @see [stop]
     */
    fun start() {
        // Check if polling thread is alive
        if(pollingThread?.isAlive == true) {
            throw Exception("${this.javaClass.simpleName} is already started.")
        }

        // Create & start polling thread
        running = true
        pollingThread = Thread { poll() }
        pollingThread?.isDaemon = true
        pollingThread?.start()
    }

    /**
     * Stop polling sensing board.
     * @see [start] to restart sensing.
     */
    fun stop() {
        running = false
        try {
            //Wait for the polling thread to stop
            pollingThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        logger.info("Stopped polling MGC3130 chipset.")
    }

    /**
     * Stops polling, closes the controller & I2C device and removes all listeners
     */
    fun close() {
        if(running) stop()
        // remove Listeners
        i2c.close()
        removeOnGestureListener()
        removeOnMoveListener()
        removeOnTouchTapListener()
        removeOnAirWheelListener()
    }

    /**
     * Continuously polls for sensor data.
     * @see [start] & @see [stop]
     */
    private fun poll() {
        logger.info("Beginning to continuously poll MGC3130 chipset ...")
        try {
            while (running) {
                val length = 26
                val data = readMsg(length)
                if (data.isEmpty() || data[0] == 0u.toUByte()) {
//                    logger.debug("No message from MGC3130 sensor")
                } else if (data.size != length) {
                    logger.debug("Only read ${data.size} of $length bytes from sensor.")
                } else if (data[3] == FLAG_SENSOR_DATA.toUByte()) {
                    parseAndSurfaceSensorData(data)
                }
                Thread.sleep(5)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Reads data from the I2C Device
     * [length]: how many bytes to read (usually 26)
     */
    private fun readMsg(length: Int = 26) : UByteArray {
//       logger.debug("Waiting to read message of length $length ... "}
        val end = System.currentTimeMillis() + 5
        while (ts.isHigh && System.currentTimeMillis() < end) {
            Thread.sleep(1)
        }
        if (ts.isHigh)  {
//           logger.debug("... No new message available")
            return ByteArray(4).toUByteArray() // [0,0,0,0]
        }
        // Assert transfer line low to ensure
        // MGC3130 does not update data buffers
        if(ts.isLow) {
            //Switch to output
            ts.output().low()
            return try {
                val data = i2c.readNBytes(length).toUByteArray()
//               logger.debug(" ... read ${data.size} of $length bytes")
                data
            } catch (e: IOException) {
                //TODO count errors
                ByteArray(9).toUByteArray() // [0,0,0,0,0,0,0,0,0]
            } finally {
                // Switch back to Input
                ts.input()
            }
        }
        //TODO Handle this case through exception
        return ByteArray(9).toUByteArray()
    }

    /**
     * Parses raw sensor data and surfaces events to listeners
     * [data]: the raw sensor data
     */
    private fun parseAndSurfaceSensorData(data: UByteArray) {
        val configMask = data[4].toUInt()
        val timestamp = data[6] // 200hz, 8-bit counter, max ~1.25sec
        val sysInfo = data[7].toUInt()
        if(configMask and S3D_DATA_XYZ > 0u && sysInfo and S3D_SYS_POSITION > 0u) {
            //Valid Move
            val x = data[S3D_PAYLOAD_X+1].toUInt() shl 8 or data[S3D_PAYLOAD_X].toUInt()
            val y = data[S3D_PAYLOAD_Y+1].toUInt() shl 8 or data[S3D_PAYLOAD_Y].toUInt()
            val z = data[S3D_PAYLOAD_Z+1].toUInt() shl 8 or data[S3D_PAYLOAD_Z].toUInt()
            onMoveListener?.run { this(Triple(x.toInt(),y.toInt(),z.toInt())) }
        }
        if(configMask and S3D_DATA_TOUCH > 0u) {
            //Valid Touch
            val action = data[S3D_PAYLOAD_TOUCH + 1].toUInt() shl 8 or data[S3D_PAYLOAD_TOUCH].toUInt()
            var comp = 1u shl 14
            for(touchTapAction in TouchType.values()) {
               if(action and comp > 0u) {
                   onTouchTapListener?.run { this(touchTapAction) }
               }
              comp = comp shr 1
            }
        }
        if(configMask and S3D_DATA_GESTURE > 0u && data[S3D_PAYLOAD_GESTURE] > 0u) {
            //Valid Gesture
            val detectGesture = data[S3D_PAYLOAD_GESTURE]
            val gesture = GestureType.values().getOrElse(detectGesture.toInt()-1) { GestureType.GARBAGE }
            onGestureListener?.run { this(gesture) }
        }
        if(configMask and S3D_DATA_AIRWHEEL > 0u && sysInfo and S3D_SYS_AIRWHEEL > 0u) {
            //Airwheel detected
            val delta = (data[S3D_PAYLOAD_AIRWHEEL].toInt() - lastRotation) / 32.0
            if(delta != 0.0 && delta > -0.5 && delta < 0.5) {
                onAirWheelListener?.run { this(delta * 360.0) }
            }
            lastRotation = data[S3D_PAYLOAD_AIRWHEEL].toInt()
        }
    }



    /**
     * Parses and surfaces raw firmware info data
     * [data]: the raw sensor data
     */
    private fun parseFirmwareInfo(data: UByteArray) : FirmwareInfo {
        val fwValid = data[4]
        if(fwValid == 0.toUByte()) {
            throw Exception("No valid GestIC Library could be located")
        }
        if (fwValid == 0x0A.toUByte()) {
            throw Exception("An invalid GestiIC Library was stored, or the last update failed")
        }
        val hwRev = arrayOf(data[5], data[6])
        val paramStartAddr = data[7] * 128u
        val libLoadedVer = arrayOf(data[8], data[9])
        val libLoaderPlatform = data[10]
        val fwStartAddr = data[11] * 128u
        val fwVersion = data.drop(12) // Drop first 12 elements
                .map { it.toByte().toChar() } // map remaining bytes to Char
                .joinToString("") // join to string
                .split((0x00).toChar())[0] // discard garbage after '\0' string terminator
        val fwInfoReceived = true
        return FirmwareInfo(fwValid,hwRev, paramStartAddr, libLoadedVer, libLoaderPlatform, fwStartAddr, fwVersion, fwInfoReceived)
    }

    /**
     * Attaches a listener for move events
     * [listener]: The [OnMoveListener] listener
     */
    fun addOnMoveListener(listener: OnMoveListener) {
        onMoveListener = listener
    }

    /**
     * Remove move event listener
     * [listener]: The [OnMoveListener] listener to be removed
     */
    fun removeOnMoveListener(listener: OnMoveListener? = null) {
        onMoveListener = null
    }

    /**
     * Attaches a listener for AirWheel events
     * [listener]: The [OnAirWheelListener] listener
     */
    fun addOnAirWheelListener(listener: OnAirWheelListener) {
        onAirWheelListener = listener
    }

    /**
     * Remove AirWheel event listener
     * [listener]: The [OnAirWheelListener] listener to be removed
     */
    fun removeOnAirWheelListener(listener: OnAirWheelListener? = null) {
        onAirWheelListener = null
    }
    /**
     * Attaches OnTouchTap event listener
     * [listener]: The [OnTouchTapListener] listener
     */
    fun addOnTouchTapListener(listener: OnTouchTapListener) {
        onTouchTapListener = listener
    }

    /**
     * Remove OnTouchTap event listener
     * [listener]: The [OnTouchTapListener] listener to be removed
     */
    fun removeOnTouchTapListener(listener: OnTouchTapListener? = null) {
        onTouchTapListener = null
    }

    /**
     * Attaches a listener for Gesture events
     * [listener]: The [OnGestureListener] listener
     */
    fun addOnGestureListener(listener: OnGestureListener) {
        onGestureListener = listener
    }

    /**
     * Remove gesture event listener
     * [listener]: The [OnGestureListener] listener to be removed
     */
    fun removeOnGestureListener(listener: OnGestureListener? = null) {
        onGestureListener = null
    }
}

// Helper Types & Classes
@OptIn(ExperimentalUnsignedTypes::class)
typealias OnGestureListener = (gesture: Sense3dController.GestureType) -> Unit
@OptIn(ExperimentalUnsignedTypes::class)
typealias OnTouchTapListener = (touch: Sense3dController.TouchType) -> Unit
typealias OnMoveListener = (event: Triple<Int, Int, Int>) -> Unit
typealias OnAirWheelListener = (rotation: Double) -> Unit
@OptIn(ExperimentalUnsignedTypes::class)
data class FirmwareInfo(val fwValid: UByte,
                        val hwRev: Array<UByte>,
                        val paramStartAddr: UInt,
                        val libLoadedVer: Array<UByte>,
                        val libLoaderPlatform: UByte,
                        val fwStartAddr: UInt,
                        val fwVersion: String,
                        val fwInfoReceived: Boolean)
