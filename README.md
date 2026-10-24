# A6300 Bulb Intervalometer

This is an app I created using Claude to fulfil a specific task. It is offered as is - I am not a developer :)
I wanted an intervalometer app that would allow to set multiple long exposure on my Sony A6300 camera. I couldn't find an app which performed this so I asked Claude if we could build one together. I gathered the retired Sony sdk info and worked from there. I have tested it successfully an old Android 11 phone and Sony A6300 camera. If it helps anyone else then please use freely.   

An Android app that turns a phone into an intervalometer for the **Sony A6300**, with exposures **longer than the camera's built-in 30 second limit** (tested up to 120 s). It was built for astrophotography on a tracking mount, where the camera takes a short run of long frames.

The app talks to the camera over its own Wi-Fi using Sony's Camera Remote API. No cables, no extra hardware.

> **Unofficial project.** Not affiliated with or endorsed by Sony. Camera Remote API by Sony.

---

## What it does

- Takes a sequence of exposures with a set **exposure time**, **number of frames**, **gap between frames**, **start delay** and **ISO**.
- Exposures of **30 s or less** use the camera's own timed shutter. The camera ends the exposure itself, so it can't get stuck open.
- Exposures **over 30 s** use bulb mode: the app opens the shutter, holds it for the time you set, then closes it.
- **Confirms ISO and shutter speed** by reading them back from the camera before every frame. If it can't confirm them, it stops and shoots nothing, rather than shooting with the wrong settings.
- Runs as a **foreground service** with a wake lock and a Wi-Fi lock, so it is meant to keep going with the screen locked.
- **Retries closing the shutter** for up to 15 minutes if the Wi-Fi connection drops at the end of a bulb frame. Any frame whose stop was delayed is marked `SUSPECT` in the log.
- Has an **ABORT** button that closes an open bulb exposure.
- Keeps a log on screen, in Logcat (tag `A6300`) and in a file in the app's folder.

## Tested with

| Item | Detail |
|---|---|
| Camera | Sony A6300, mode dial on **M**, saving RAW+JPEG |
| Phone | An Android 11 phone |
| Sequences run | 3 × 60 s, 1 × 120 s, 2 × 70 s, 10 × 120 s (about 21 minutes), plus short camera-timed runs at 20 s |
| Exposure accuracy | EXIF exposure times matched the requested times in the checks made (for example 60 s, 60 s, 61 s for three 60 s frames) |
| Connection loss | Wi-Fi was switched off mid-frame during a bulb exposure and the shutter stayed open. After rejoining the camera's Wi-Fi, the app closed it within a few seconds and carried on |

Other cameras, phones and Android versions have **not** been tested.

## Important warnings

- **A lost Wi-Fi connection leaves the shutter open.** If the phone loses the camera mid-exposure, the camera does **not** close the shutter by itself. The app keeps retrying the stop and will close it as soon as the connection returns, but until then the frame is over-exposed. Keep the phone close to the camera, plugged in, and keep other saved Wi-Fi networks off the phone so it doesn't switch away.
- **Phones don't always rejoin the camera's Wi-Fi by themselves.** In testing, the phone connected to a different network after Wi-Fi was toggled and the camera's network had to be selected by hand.
- **Check the camera's own settings.** Auto power-off and Wi-Fi timeouts need to be long enough for your session. The behaviour of these on the A6300 has not been verified here, so check them in the camera menu before relying on a long run.
- **Not controlled by the app:** aperture, focus, white balance, and file format. Set these on the lens and camera first.
- Use at your own risk, and test with the lens cap on before a real night.

## Requirements

- Sony A6300 with **Smart Remote Embedded** (the camera's built-in Wi-Fi remote function)
- Android phone, developed and tested on **Android 11**
- Memory card with space for your frames

## Camera set-up

1. Turn the mode dial to **M** (Manual). The app cannot change the exposure mode.
2. Start the remote function: on the camera, **MENU, then Application, then Application List, then Smart Remote Embedded**. The camera shows its Wi-Fi name and password.
3. On the phone, join that Wi-Fi network. If Android asks about "no internet" for this network, choose to stay connected.

## Install

**From an APK:** download the `.apk` from the Releases page and open it on your phone. You will need to allow installing apps from unknown sources.

**From source:** open the project in Android Studio, connect the phone with USB debugging enabled, and press Run. No extra libraries are needed.

## Using it

1. Complete the camera set-up above.
2. Open the app and set:
   - **Exposure (s)**: up to 300 is accepted; tested up to 120.
   - **Frames**
   - **Gap between frames (s)**
   - **Start delay (s)**: time to let vibrations settle after you press START.
   - **ISO**
3. Press **START**. The status line shows the frame number and a countdown. You can lock the screen.
4. Press **ABORT** to stop. If a bulb exposure is open, it is closed.

At the end, the log prints a summary with the actual open time and the saved file name for each frame. Your full-size files stay on the camera's memory card.

## How it works (for developers)

The app uses Sony's **Camera Remote API beta** (JSON over HTTP) and follows the connection steps in Sony's Development Guide:

1. Bind the app's traffic to the Wi-Fi network (the camera has no internet).
2. Find the camera with an SSDP `M-SEARCH` for `urn:schemas-sony-com:service:ScalarWebAPI:1`.
3. Read the device description XML to get the camera service URL (on the A6300 this was `http://192.168.122.1:8080/sony/camera`).
4. Call `startRecMode` if the camera lists it, then wait for the camera to finish setting up.
5. Send JSON-RPC `POST` requests.

Findings specific to the A6300 that are not in Sony's v2.40 API reference:

- When the shutter speed is set to `BULB`, the camera replaces `actTakePicture` with **`startBulbShooting`**, and offers **`stopBulbShooting`** while an exposure is running. Both take no parameters. They were found by asking the camera for its method list with `getMethodTypes`.
- The camera keeps its session through a Wi-Fi drop of at least a minute, so a stop command sent after reconnecting works without reconnecting from scratch.
- Right after `startRecMode`, the camera can report only a partial API list, and settings calls fail with "Not Available Now". The app waits until `setIsoSpeedRate` and `setShutterSpeed` are available before changing settings.
- After `setShutterSpeed`, the available calls take a moment to switch between `actTakePicture` and `startBulbShooting`.

Android notes: plain HTTP must be allowed for the camera (`usesCleartextTraffic`), multicast needs `CHANGE_WIFI_MULTICAST_STATE` and a multicast lock, and the sequence runs in a foreground service.

## Not yet verified

- Whether the app keeps running reliably with the screen locked for a whole session (designed for it, but not confirmed here).
- The camera's auto power-off and Wi-Fi timeout behaviour over a long session.
- Other camera models, other phones, and Android versions other than 11.

## Credits and licence

- **Camera Remote API by Sony.** Sony's documentation is not included in this repository because it is licensed for personal use only.
- Licence: *add a `LICENSE` file before publishing.*
