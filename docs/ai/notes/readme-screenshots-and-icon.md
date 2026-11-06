---
type: Reference
title: Retaking the README screenshots and icon
description: The README's images come from the Screenshots_36 emulator at 540x1200 with a clean demo-mode status bar; the Alerts cards need saved items, adb cannot zoom the map reliably so fly to a saved stop instead, and docs/icon-512.png is rendered from the default icon's SVG.
tags: [readme, screenshots, emulator, icons]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-11-03T21:30:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-11-03T21:30:00Z"
status: stable
---

The images live in `docs/screenshots/` `README.md` shows a selection and `docs/FEATURES.md` all of them. Each is a 1080x2400 capture from
the `Screenshots_36` emulator (API 36), scaled to 540x1200; `notification.png` is the notification cropped
from the open shade. They show live data, so evening captures have the most buses without being empty;
late at night the planner returns few connections.

# Getting the screens to show something

- Install over the existing app with `adb install -r` on the debug APK. The emulator keeps its saved
  places, lines and stops, which the Plan row and the Alerts cards need; Alerts shows no cards without
  saved items.
- Take the following, strip and notification shots in one go after tapping Start: the countdown runs out
  within minutes. Stop following before retaking other screens, or the strip stays in them.
- The widget is already on the emulator's home screen; tap its refresh before the capture.

# Status bar

System UI demo mode (`settings put global sysui_demo_allowed 1`, then `com.android.systemui.demo`
broadcasts with `enter`, `clock`, `battery`, `network` and `notifications`) hides notification icons. Set
`clock` to the real time before each capture, so it agrees with the times on screen. Opening the shade
brings the notification icons back, so send `notifications` again afterwards.

# Zooming the map

adb has no multi-touch: `input` is single-finger and `/dev/input` is closed to the shell by SELinux, and
two `input tap`s are often too slow to count as a double tap, or open a vehicle sheet instead. So do not
try to set a zoom level. A long press on the "my position" button lists the saved stops, and picking one
flies there at street zoom and opens its sheet, which is the map shot; closing the sheet and panning with
`input swipe` finds a car park or a bus for the strip and parking shots.

# The icon

`docs/icon-512.png` is `icons/Alpine Route.svg` at 512x512 with rounded corners (radius 112) on a
transparent background, rendered with headless Chrome (`--default-background-color=00000000`). Pillow
cannot read SVG. When the default icon changes ([app icons](./app-icons-and-package-name.md)), redo it and
its alt text on the first line of the README.
