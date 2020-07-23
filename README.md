# Sense3d
[![](https://jitpack.io/v/treitmaier/Sense3d.svg)](https://jitpack.io/#treitmaier/Sense3d)
[![Hex.pm](https://img.shields.io/hexpm/l/plug.svg)](https://github.com/treitmaier/Sense3d)

Sense3d is a 100% Kotlin library to surface 3D positional data and common gestures from electrical near-field add on boards for the Raspberry Pi. Sense3d currently only supports boards that utlise the MGC3130 chipset, such as [Flick](https://github.com/PiSupply/Flick) or [Skywriter](https://shop.pimoroni.com/products/skywriter).  Through these boards Sense3d is able to detect gestures from up to 15cm away in 3D space.

The aim of the Sense3d library is to abstract away the low-level aspects of 3D tracking and gesture sensing boards so developers can focus on handling and responding to higher level events, such as when a user moves their hand or waves their hand from left to right (see code sample below).

## Background
Communication with the MGC3130 chipset is accomplished via a two-wire I2C interface. To access this low-level I/O capability on the Raspberry Pi embedded platform, Sense3d utilises the [Pi4J](https://pi4j.com/) interface library.

Coming from the world of mobile interface design and development on Android, I wanted to utilise programming paradigms (functional interfaces and lambda expressions) as well as language features (e.g. streams & anonymous classes) that have been introduced since Java 8. Unfortunately this ruled out using the stable version of Pi4J as it is not compatible with more current JDK versions. Sense3d therefore depends on [Version 2 of Pi4J](https://v2.pi4j.com/), which targets JDK 11 and is currently under active development. That means there is no API compatibility with previous versions of Pi4J and current APIs are also subject to change.  Hopefully those APIs will stabilise soon.

As the [MGC3130 interface specification](docs/MGC3130-Library-Interface-Description.pdf) spec utilises unsigned byte representations and arithmetics, Sense3d utilises Kotlin's corresponding [unsigned integer types](https://kotlinlang.org/docs/reference/whatsnew13.html) (`UByte` and `UInt`).  This is currently an experimental feature in Kotlin 1.3, which should hopefully become stable in the next Kotlin release.

## Setup

You'll need ensure that Kernel support for the I2C interface is enabled on the Raspberry Pi. To do this run:

```
sudo raspi-config
```

Go to **Interfacing Options** ... then **I2C** ... and select **Yes** to enable I2C.  You'll need to also reboot in order for the changes to take effect:

```
sudo reboot
```


### Pin mapping
The pinout for the Raspberry Pi is as follows. 

| MGC3130 | Raspberry Pi (BCM Pin#) |
| ------- | ----------------------  |
| VCC     | 3v3 Power               |
| SDA     | 2                       |
| SCL     | 3                       |
| RESET   | 17 / any other GPIO     |
| TS      | 27 / any other GPIO     |
| GND     | GND                     |

Check [pinout.xyz](https://pinout.xyz) to find the BCM pin number's corresponding physical location on the header.  And keep in mind that the RESET/RST and TS/TRFR pins can be configured to any other GPIO pin.

Finally make sure the MGC3130 chipset is being detected on the I2C Bus:

```
i2cdetect -y 1
```

and take note of the address -- usually 42.

## Getting Started

### Install dependencies

Although Sense3d is compatible with both 64bit (aarch64) and 32bit (armv7l) versions of Raspberry Pi OS/Raspbian, currently it depends on custom compiled versions of the pi4j-v2 library to address issues with [Multipurpose GPIOs](https://github.com/Pi4J/pi4j-v2/issues/26), which hasn't been integrated into the pi4j-v2 codebase yet.  Therefore I'm currently only bundling 32bit (armv7l) versions of the pi4j libraries, as that is the platform I'm currently targeting and for which I have complied the native components of the pi4j library.  You'll need to download the pi4j-v2 library jars from the [libs directory](libs/).  For gradle-based build systems, you'll need to make sure the jars in the [libs directory](libs) are included in an equivalent directory in your application and listed as dependencies:

``` groovy
implementation fileTree(dir: ‘libs’, include: ‘*.jar’) // groovy

// or

implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar")))) // Kotlin KTS
```

Furthermore both Sense3d & Pi4J-v2 both utilise the [slf4j logging facade](http://www.slf4j.org/) which again needs to be listed as a dependecy in your `build.gradle` or `build.gradle.kts` file:

``` groovy
implementation 'org.slf4j:slf4j-api:2.0.0-alpha0' // Groovy

// or

implementation("org.slf4j:slf4j-api:2.0.0-alpha0") // Kotlin KTS
```

Finally you'll need to include the Sense3d dependency itself. First by adding the JitPack repository to your build file:

``` groovy
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

or if you're Kotlin's Gradle DSL:

``` kotlin
repositories {
    ...
    maven(url = "https://jitpack.io")
}
```


And finally, list Sense3d as a dependency:

``` groovy
implementation 'com.github.treitmaier:Sense3d:-SNAPSHOT' // Groovy

// or

implementation("com.github.treitmaier:Sense3d:-SNAPSHOT") // Kotlin KTS
```

### Code Sample

``` kotlin
// I2C Configuration Parameters
val I2C_BUS = 1
val DEVICE_ADDRESS = 0x42 //see above

// Control Pins
// BCM Numbering see https://pinout.xyz
val RESET_PIN = 17
val TS_PIN = 27
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
```

