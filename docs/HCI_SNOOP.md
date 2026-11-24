# Recording the real protocol with the Bluetooth HCI snoop log

Sonic Control cannot control a headset whose protocol is unknown. The most reliable way to learn it is to record
what the manufacturer's own app sends. This is done **by the phone owner, on their own phone, with their own headphones**.
Nothing is modified: Android only writes a log file.

## Steps (Android 10+)
1. Settings → About phone → tap *Build number* 7 times to enable Developer options.
2. Settings → System → Developer options → **Enable Bluetooth HCI snoop log** (choose *Enabled* or *Enabled filtered*).
3. Turn Bluetooth off and on once so logging starts.
4. Connect the headphones. Open the manufacturer's app (for JBL: *JBL Headphones*).
5. Do **one action at a time**, with a pause of about 10 seconds between them, and write the order down:
   noise control on → ambient → off, equalizer preset change, read battery screen, etc.
6. Capture a bug report: Developer options → *Take bug report* → *Full report*, or `adb bugreport`.
   The log is in `FS/data/misc/bluetooth/logs/btsnoop_hci.log` inside the zip (path differs by vendor).
7. Turn the snoop log off afterwards.

## Analysis (not done by the app)
Open the file in Wireshark. Filter `btatt` for BLE GATT traffic or `btrfcomm` for RFCOMM. The vendor frames
(for example JBL `AA cmd len payload`) appear as ATT writes/notifications or RFCOMM data. Compare each action with the frames sent.

## Privacy
The log contains device addresses and everything the phone exchanged with Bluetooth devices, which can include
call audio control and names of other devices. Review before sharing, share only the segment you need, and prefer filtered logging.
Sonic Control never reads, uploads or collects this log.

## What this is not
It does not bypass any protection, does not read firmware and does not send anything to the headphones.
Frames learned this way are marked Experimental until they are confirmed with a validated reply from the headset on real hardware.
