package com.lancast.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;

import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class CaptureService extends Service {
    private static final String CHANNEL = "lancast_capture";
    private static final int NOTIFICATION_ID = 42;
    private static final int WIDTH = 1280, HEIGHT = 720, FPS = 30, BITRATE = 4000000;
    private volatile boolean running = false;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private Socket socket;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        startForeground(NOTIFICATION_ID, buildNotification());
        final int resultCode = intent.getIntExtra("resultCode", 0);
        final Intent data = intent.getParcelableExtra("data");
        final String host = intent.getStringExtra("host");
        new Thread(() -> startStreaming(resultCode, data, host), "LanCast-capture").start();
        return START_NOT_STICKY;
    }

    private void startStreaming(int resultCode, Intent data, String host) {
        try {
            socket = new Socket(host, 5000);
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            virtualDisplay = projection.createVirtualDisplay("LanCast",
                    WIDTH, HEIGHT, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null);

            running = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                int index = encoder.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Codec configuration is delivered in the output buffers on supported encoders.
                    continue;
                }
                if (index >= 0) {
                    ByteBuffer buffer = encoder.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        byte[] bytes = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(bytes);
                        out.writeByte(1);
                        out.writeInt(bytes.length);
                        out.writeLong(info.presentationTimeUs);
                        out.writeInt(info.flags);
                        out.write(bytes);
                        out.flush();
                    }
                    encoder.releaseOutputBuffer(index, false);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("LanCast", "Capture failed", e);
        } finally {
            cleanup();
            stopSelf();
        }
    }

    private void cleanup() {
        running = false;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        stopForeground(true);
    }

    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "LanCast screen sharing",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle("LanCast is sharing your screen")
                    .setContentText("Streaming H.264 to your TV")
                    .setSmallIcon(android.R.drawable.presence_video_online)
                    .setOngoing(true).build();
        }
        return new Notification.Builder(this)
                .setContentTitle("LanCast is sharing your screen")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true).build();
    }
}
