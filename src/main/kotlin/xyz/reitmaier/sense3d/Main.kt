package xyz.reitmaier.sense3d

import com.pi4j.Pi4J
import com.pi4j.io.gpio.digital.*
import com.pi4j.io.i2c.I2C
import com.pi4j.plugin.pigpio.PiGpioPlugin

// I2C Configuration Parameters
const val I2C_BUS = 1
const val DEVICE_ADDRESS = 0x42

// Control Pins
// BCM Numbering see https://pinout.xyz
const val RESET_PIN = 6
const val TS_PIN = 5
@OptIn(ExperimentalUnsignedTypes::class)
fun main() {
    // Initialise a new Pi4j runtime context
    val pi4j = Pi4J.newAutoContext()

    // configure i2c
    val i2cConfig  = I2C.newConfigBuilder(pi4j)
            .bus(I2C_BUS)
            .device(DEVICE_ADDRESS)
            .provider(PiGpioPlugin.I2C_PROVIDER_ID)
            .build()
    val i2c = pi4j.create(i2cConfig)

    // configure reset pin
    val resetConfig = DigitalOutput.newConfigBuilder(pi4j)
            .address(RESET_PIN)
            .shutdown(DigitalState.LOW)
            .initial(DigitalState.HIGH)
            .provider(PiGpioPlugin.DIGITAL_OUTPUT_PROVIDER_ID)
    val reset = pi4j.create(resetConfig)

    // configure transfer pin
    val tsConfig = DigitalMultipurpose.newConfigBuilder(pi4j)
            .address(TS_PIN)
            .mode(DigitalMode.INPUT)
            .pull(PullResistance.PULL_UP)
            .provider(PiGpioPlugin.DIGITAL_MULTIPURPOSE_PROVIDER_ID)
    val ts: DigitalMultipurpose = pi4j.digitalMultipurpose<DigitalMultipurposeProvider>().create(tsConfig)

    // create the Sense3d controller
    val gestureController = Sense3dController(i2c,reset,ts)

    // Add Listeners
    gestureController.addOnGestureListener {
        println("Gesture: $it")
    }
    gestureController.addOnMoveListener {
        // Can be very verbose
        println("Moved to: $it")
    }
    gestureController.addOnTouchTapListener {
        println("Detected $it")
    }
    gestureController.addOnAirWheelListener {
        println("AirWheel: $it")
    }
    // Initialise gesture controller and print firmware info
    val firmwareInfo = gestureController.init()
    println("Received Firmware Info: $firmwareInfo")

    // Listen for gesture events for 20 seconds
    gestureController.start()
    Thread.sleep(20_000L)

    // Tidy up
    gestureController.stop()
    gestureController.close()
    pi4j.shutdown()
}
