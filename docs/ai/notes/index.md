# Notes

Findings that cost time to learn, and standing facts about the repository. One concept per file; each
entry repeats the note's `description`.

# Findings

* [Stop boards take live times from two sources](./live-times-two-sources.md) - where GTFS-RT has no prediction (often), boards, the map stop sheet and the widget overlay EFA delays matched by line and scheduled minute; trips do the same per stop. The README's realtime section mentions only GTFS-RT.
* [There is no CI](./no-ci-every-check-is-local.md) - a GitHub Actions workflow existed for a few minutes on 2026-10-05 and was deleted; it built a debug APK with Java 17, ran no tests, and would have published debug-signed releases on every v* tag.

# Reference

* [Test suite layout, and the network tests that are off by default](./test-suite-and-opt-in-live-tests.md) - four kinds of tests across three modules; the tests that download the real STA feed or hit the FTP server only run with -PliveTests=true or -PgtfsReal, and TranslationsTest fails on any missing or stale en/de/it string.
