# ReMe

An Android app that turns the phone of a person trapped in a building into a rescue beacon,
and the phone of a firefighter into a finder that shows how close that person is and whether
they are on a floor above or below.

It works phone to phone. No mobile network, no building Wi-Fi and no server are needed, because
in a fire all three are usually gone.

## The problem we are solving

Every year, thousands of people die in fires not because they were unreachable, but because
they simply couldn't be found in time. In a fire or collapse, victims lose consciousness from
smoke inhalation while trying to escape, which leaves them silent and invisible in
zero-visibility conditions. Statistics show that if a victim is located within the first
8 minutes, they have a 66% chance of survival.

Unfortunately, GPS is not informative enough for finding people inside buildings, and accurate
IMSI-based locating systems are highly regulated and usually can't be used by rescuers.

In an emergency every second counts, and victims cannot afford to wait extra minutes for
rescuers to find them. So we need a lightweight, easy-to-set-up solution that makes it possible
to quickly locate a victim within a building and save them before it is too late.

## Our solution

The victim's phone sends several parameters to rescuers over BLE, Wi-Fi Aware and Bluetooth:
air pressure, GPS, phone signal strength, battery, brief medical information and phone
temperature.

The app helps rescuers find people who need help inside buildings by sharing all the relevant
information. This lets firefighters find people in dangerous situations, when time is crucial.

## How it looks in use

**The person who needs help** taps the SOS widget on the home screen. The phone opens a red
emergency screen and starts broadcasting in the background, so it keeps working with the screen
locked. The torch blinks and a siren plays at full alarm volume, even in silent mode, so
rescuers without the app can also see and hear the phone over the last few metres. The only way
to stop it is the "I'm safe" button, followed by a confirmation.

**The rescuer** just opens the app. It only shows phones that have asked for help. For each one
the rescuer sees:

- a circle that shrinks and grows with distance, and a status such as "Nearby" or "Getting closer"
- a Level tile: "Same level", "↑ 3 m above you" or "↓ 2 m below you"
- a Medical ID card: name, age, blood type, conditions
- the victim's battery, temperature and last GPS fix

## How it works

| What | How |
|---|---|
| Finding the phone at all | BLE advertising. The same 25-byte packet goes out on several advertising sets at once, plus one long-range (LE Coded PHY) set, because single packets are easily lost on a crowded 2.4 GHz band. |
| How close | Signal strength of a real Bluetooth connection between the two phones, read ten times a second. A connection carries much further than advertisements do. The readings are smoothed and turned into a distance and a closer/farther trend. |
| Distance in metres | Wi-Fi Aware with Wi-Fi RTT ranging, where both phones support it. It is slow and often fails, so it does not replace Bluetooth. Each successful range re-calibrates the Bluetooth estimate. |
| Which floor | Barometers. Both phones share their pressure, and the difference gives the height (about 12 Pa per metre). |
| Which building | The last GPS fix, sent together with its age. |
| Conditions around the victim | Air temperature if the phone has that sensor, otherwise battery temperature. |

Everything the rescuer needs fits in the Bluetooth packet, so nothing depends on a connection
to the internet.

## Building and running

You need Android Studio (or just the JDK and Android SDK) and two Android phones with
Android 9 or newer and Bluetooth LE.

A ready-made build is in the repo as `ReMe.apk`.

On first start the app asks for Bluetooth, location, nearby Wi-Fi devices and notification
permissions. It needs all of them. Then add the SOS widget to the home screen of the phone that
will play the victim.

## Things to know

- Wi-Fi ranging needs Wi-Fi switched on (no access point required) and does not work while the
  phone's hotspot is on.
- Bluetooth signal strength is noisy. Walls and bodies change it.
