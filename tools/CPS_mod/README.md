# AT2 CPS Dealer UI Patch

This directory contains a small Python patch script for exposing hidden dealer-related UI controls in the AT2 CPS, which will allow you to modify `subSystemId` freely.

The recommended script is:

```text
patch_expose_dealer_ui_full.py
```

This script patches the minified CPS `index-*.js` bundle to expose the dealer selector and related production/read-write controls.

## What this patch does

The script modifies the CPS frontend JavaScript bundle and applies the following changes:

1. Changes the CPS UI mode from `user` to `complete`.
2. Forces several dealer / production / read-write UI sections to be visible.
3. Changes the default dealer from `unknown` to:

```text
宝锋 / CN059500 / subSystemId 1
```

4. When the device `vendorCode` is empty, it falls back to:

```text
CN059500
```

This prevents the UI from returning to `unknown` when no vendor code is reported.

The script does not modify radio firmware. It only patches the CPS frontend JavaScript file.

## How to obtain the CPS

This repository does not redistribute the official CPS package. Obtain it from the official Ola Radio app.

1. Open the official Ola Radio app.
2. Connect to the AT2 radio.
3. On the AT2 device card, tap the three-dot menu in the top-right corner.
4. Select `Device Detail`.
5. In the `Device Detail` page, tap `Firmware Update Tool`.
6. Copy the Google Drive link shown by the app.
7. Download the CPS package from that Google Drive link and install it.
8. Locate the CPS installation directory.
9. Open the following directory inside the installation path:

```text
<install-dir>\Bluetooth_User_Series\resources\app\app\dist\assets
```

10. Copy `patch_expose_dealer_ui_full.py` into this directory and run it there.

The script must be placed in the same directory as the CPS `index-*.js` bundle.


## Usage

1. Locate the CPS JavaScript bundle.

It is usually named like:

```text
index-f91c8683.js
```

or generally:

```text
index-*.js
```

2. Copy `patch_expose_dealer_ui_full.py` into the same directory as the `index-*.js` file.

Example directory layout:

```text
Bluetooth_User_Series/
└── resources/
    └── app/
        └── app/
            └── dist/
                └── assets/
                    ├── index-f91c8683.js
                    └── patch_expose_dealer_ui_full.py
```

3. Run the patch script with Python 3:

```bash
python patch_expose_dealer_ui_full.py
```

On Windows PowerShell:

```powershell
python .\patch_expose_dealer_ui_full.py
```

4. Restart the CPS application.

After patching, the dealer selector and related hidden controls should be visible in the CPS UI.

## Backup

Before modifying the JavaScript file, the script automatically creates a backup:

```text
index-xxxx.js.before_expose_dealer_ui_full.bak
```

If the backup already exists, it will not be overwritten.

To restore the original CPS bundle, delete the patched `index-xxxx.js` and rename the backup file back to the original name.

Example:

```text
index-f91c8683.js.before_expose_dealer_ui_full.bak
-> index-f91c8683.js
```

## Script behavior

The script first searches for `index-*.js` in the same directory.

If exactly one matching file is found, it patches that file.

If multiple matching files exist, it falls back to:

```text
index-f91c8683.js
```

If no valid JavaScript bundle is found, the script exits with an error.

## Warnings

This patch targets a specific CPS frontend bundle structure. If the CPS version changes, the minified JavaScript snippets may also change.

If the script prints warnings such as:

```text
WARNING not found
```

then the patch may be incomplete. Do not use the patched CPS until the result has been checked manually.

## Dealer / subSystemId mapping

The CPS bundle contains a hard-coded dealer list. Each dealer entry includes a `value` field and a `subSystemId`.

In this document, `subSystemId` refers to the value used by the CPS dealer list. It is also the value referred to as `subSystemCode` in some notes.

| subSystemId | Dealer name | Dealer code |
|---:|---|---|
| 0 | 外贸体验版 | `POFUNG00` |
| 1 | 宝锋 | `CN059500` |
| 2 | 王青红 | `CN059501` |
| 3 | 王鑫源 | `CN059502` |
| 4 | 李木旺 | `CN059503` |
| 5 | 王景松 | `CN059504` |
| 6 | 邓小松 | `CN002801` |
| 7 | 姜克明 | `CN002501` |
| 8 | 张丽丽 | `CN053601` |
| 9 | 陈军 | `CN093101` |
| 10 | 吴端乐 | `CN053201` |


## Safety and legal notice

Use this patch only with radios and frequencies that you are legally allowed to configure and operate.

This project is not affiliated with Baofeng, ALERVITES, Ola Radio, UCChip, or any listed dealer.

No warranty is provided. Use at your own risk.