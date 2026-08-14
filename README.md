# EGR Watch

Android app for a 2009 Kia Sportage 2.0 diesel. Connects to a Bluetooth ELM327
OBD adapter, and while you drive it auto-clears an EGR-family fault code (turning
off the check engine light) but holds and shows any other code instead of hiding it.

## Get an APK without installing Android Studio (recommended)

1. Create a new GitHub repository.
2. Upload everything in this folder to it (web uploader or `git push`).
3. Open the **Actions** tab. The "Build APK" workflow runs automatically.
4. When it finishes (about 3-5 min), open the run, download the **EgrWatch-debug**
   artifact, unzip it, and you have `app-debug.apk`.

## Or build locally

Open this folder in Android Studio, then Build > Build APK(s). Android Studio
downloads the SDK and dependencies on first run.

## Install on the phone

Copy `app-debug.apk` to the phone and open it. You'll need to allow "install from
unknown sources" for your file manager or browser. This is a debug build, so it is
unsigned for the Play Store but installs fine directly.

## First run

1. Pair the ELM327 in the phone's Bluetooth settings once (PIN usually 1234 or 0000).
2. Plug the adapter into the OBD port, ignition on.
3. Launch the app and mount the phone. It finds the paired adapter and runs itself.

## Known v1 items to confirm on the car

- The EGR code set is the common P040x range plus P0409 and P1406. Report the actual
  code that appears so it can be confirmed or added.
- Some ECUs refuse a clear while the engine runs. If the footer shows "clear rejected",
  that's this.
- The DTC reader is tuned for one or two stored codes. Three or more at once uses a
  multi-frame format that needs checking against real output.
