# Optional APK delivery

`python3 tools/deliver_apk.py /path/to/Bingo.apk` copies a compiled APK to destinations supplied in a private `~/.config/bingo/apk-delivery.json`. No destination, address or password is included in this source package. The tool verifies SHA-256 before replacement and after delivery, refreshes modification times, and records a private receipt. It does not install the app. Use `--help` for options.

The published Gradle build does not invoke delivery automatically. Configure an optional local hook on your own build machine if needed. Never commit the delivery configuration or receipts.
