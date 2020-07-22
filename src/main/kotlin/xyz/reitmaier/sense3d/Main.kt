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
const val XFER_PIN = 5
@OptIn(ExperimentalUnsignedTypes::class)
fun main() {
    val pi4j = Pi4J.newAutoContext()

    val i2cConfig  = I2C.newConfigBuilder(pi4j)
            .bus(I2C_BUS)
            .device(DEVICE_ADDRESS)
            .provider(PiGpioPlugin.I2C_PROVIDER_ID)
            .build()
    val i2c = pi4j.create(i2cConfig)

    val resetConfig = DigitalOutput.newConfigBuilder(pi4j)
            .address(RESET_PIN)
            .shutdown(DigitalState.LOW)
            .initial(DigitalState.HIGH)
            .provider(PiGpioPlugin.DIGITAL_OUTPUT_PROVIDER_ID)

    val tsConfig = DigitalMultipurpose.newConfigBuilder(pi4j)
            .address(XFER_PIN)
            .mode(DigitalMode.INPUT)
            .pull(PullResistance.PULL_UP)
            .provider(PiGpioPlugin.DIGITAL_MULTIPURPOSE_PROVIDER_ID)

    val reset = pi4j.create(resetConfig)
    val ts: DigitalMultipurpose = pi4j.digitalMultipurpose<DigitalMultipurposeProvider>().create(tsConfig)
    val flick = Sense3dController(i2c,reset, ts)
    flick.addOnGestureListener {
        println("Gesture: $it")
    }
    flick.addOnMoveListener {
        // Can be very verbose
        println("Moved to: $it")
    }
    flick.addOnTouchTapListener {
        println("Detected $it")
    }
    flick.addOnAirWheelListener {
        println("AirWheel: $it")
    }
    val firmwareInfo = flick.init()
    println("Received Firmware Info: $firmwareInfo")

    // Listen for 20 seconds
    flick.start()
    Thread.sleep(20_000L)

    // Tidy up
    flick.stop()
    flick.close()
    pi4j.shutdown()
}
