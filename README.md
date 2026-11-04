# LanCast (experimental LAN screen mirroring)

Two Android apps:
- `tvreceiver`: Android 4.4.2 / API 19 receiver, TCP port 5000.
- `phonesender`: Android 10+ sender, MediaProjection + H.264 encoder, TCP to the TV.

## Build on GitHub
1. Create a new GitHub repository.
2. Upload the contents of this folder (the files inside `LanCast`, not the parent folder).
3. Push to branch `main`, or open **Actions → Build LanCast APKs → Run workflow**.
4. Download the `lancast-debug-apks` artifact from the completed workflow.

No signing secrets are needed for debug APKs.

## First test
1. Install `tvreceiver-debug.apk` on the CAIXUN TV and open it.
2. Confirm the TV LAN IP is `192.168.1.6`; the app listens on TCP port `5000`.
3. Install `phonesender-debug.apk` on the Galaxy A15.
4. Enter `192.168.1.6`, tap **Start screen sharing**, and approve Android's screen-capture prompt.
5. To stop, tap **Stop sharing**.

## Important alpha limitations
This is a first-pass transport/codec prototype, not a finished DeX replacement.
- The sender currently targets fixed 1280x720 at 30 FPS and 4 Mbps.
- The current TCP protocol sends MediaCodec output buffers directly. Device encoder output may provide codec configuration separately; if the TV shows a black screen, the next engineering step is to add explicit SPS/PPS handling and confirm the decoder's supported AVC profile/level.
- Receiver currently initializes its decoder for 1280x720 and assumes the TV's H.264 decoder accepts the sender's bitstream format.
- Touch/mouse return channel, audio, adaptive bitrate, reconnect UI, rotation handling and secure-content capture are not implemented.
- MediaProjection cannot capture protected/DRM video, and Android may show a persistent screen-sharing indicator.
- Samsung DeX may change display metrics/virtual-display behavior; this prototype mirrors the captured device display, not a complete DeX desktop integration.
- Keep both devices on the same reachable LAN. The receiver binds to all interfaces on port 5000.
- If GitHub Actions fails due to SDK package installation, add an explicit Android SDK setup step or use a GitHub runner image with Android SDK preinstalled.

## Troubleshooting
- If sender says it cannot connect, verify the TV receiver is open and the phone can reach `192.168.1.6:5000`.
- If TV stays black, collect `adb logcat` from the TV and Android logs from the phone; codec configuration is the likely first issue to investigate.
