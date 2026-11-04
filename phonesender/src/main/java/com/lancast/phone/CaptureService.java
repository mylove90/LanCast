
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;

import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class CaptureService extends Service {
    private static final String TAG = "LanCast";
    private static final String CHANNEL = "lancast_capture";
    private static final int NOTIFICATION_ID = 42;

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;
    private static final int BITRATE = 4000000;

    private volatile boolean running = false;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private Socket socket;

    private DataOutputStream out;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        final int resultCode = intent.getIntExtra("resultCode", 0);
        final Intent data = intent.getParcelableExtra("data");
        final String host = intent.getStringExtra("host");

        new Thread(
                () -> startStreaming(resultCode, data, host),
                "LanCast-capture"
        ).start();

        return START_NOT_STICKY;
    }

    private void sendPacket(int type, byte[] bytes, long pts, int flags)
            throws Exception {
        if (bytes == null || bytes.length == 0) return;

        out.writeByte(type);
        out.writeInt(bytes.length);
        out.writeLong(pts);
        out.writeInt(flags);
        out.write(bytes);
        out.flush();
    }

    private byte[] bufferToBytes(ByteBuffer source) {
        ByteBuffer buffer = source.duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private void sendCodecConfig(MediaFormat format) throws Exception {
        if (format.containsKey("csd-0")) {
            ByteBuffer sps = format.getByteBuffer("csd-0");
            if (sps != null) {
                byte[] bytes = bufferToBytes(sps);
                sendPacket(0, bytes, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                Log.i(TAG, "Sent SPS: " + bytes.length + " bytes");
            }
        }

        if (format.containsKey("csd-1")) {
            ByteBuffer pps = format.getByteBuffer("csd-1");
            if (pps != null) {
                byte[] bytes = bufferToBytes(pps);
                sendPacket(2, bytes, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                Log.i(TAG, "Sent PPS: " + bytes.length + " bytes");
            }
        }
    }

    private void startStreaming(int resultCode, Intent data, String host) {
        try {
            if (data == null || host == null || host.isEmpty()) {
                throw new IllegalArgumentException(
                        "Missing projection data or TV address"
                );
            }

            socket = new Socket(host, 5000);
            socket.setTcpNoDelay(true);
            out = new DataOutputStream(socket.getOutputStream());

            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(
                            MEDIA_PROJECTION_SERVICE
                    );

            projection = mpm.getMediaProjection(resultCode, data);

            if (projection == null) {
                throw new IllegalStateException("MediaProjection is null");
            }

            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "MediaProjection stopped by system");
                    running = false;
                }
            };

            projection.registerCallback(
                    projectionCallback,
                    new Handler(Looper.getMainLooper())
            );

            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    WIDTH,
                    HEIGHT
            );

            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            );
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
            );

            encoder.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            running = true;

            DisplayMetrics metrics = getResources().getDisplayMetrics();

            virtualDisplay = projection.createVirtualDisplay(
                    "LanCast",
                    WIDTH,
                    HEIGHT,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null
            );

            if (virtualDisplay == null) {
                throw new IllegalStateException(
                        "Failed to create VirtualDisplay"
                );
            }

            Log.i(TAG, "Capture started successfully");

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (running && !socket.isClosed()) {
                int index = encoder.dequeueOutputBuffer(info, 10000);

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = encoder.getOutputFormat();

                    Log.i(TAG, "Encoder output format: " + outputFormat);

                    sendCodecConfig(outputFormat);
                    continue;
                }

                if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                }

                if (index >= 0) {
                    try {
                        // Codec configuration is sent from csd-0/csd-1.
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                && info.size > 0) {

                            ByteBuffer buffer = encoder.getOutputBuffer(index);

                            if (buffer != null) {
                                buffer.position(info.offset);
                                buffer.limit(info.offset + info.size);

                                byte[] bytes = new byte[info.size];
                                buffer.get(bytes);

                                sendPacket(
                                        1,
                                        bytes,
                                        info.presentationTimeUs,
                                        info.flags
                                );
                            }
                        }
                    } finally {
                        encoder.releaseOutputBuffer(index, false);
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Capture failed", e);
        } finally {
            cleanup();
            stopSelf();
        }
    }

    private void cleanup() {
        running = false;

        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Exception ignored) {}

        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }
        } catch (Exception ignored) {}

        try {
            if (projection != null) {
                projection.stop();
                projection = null;
            }
        } catch (Exception ignored) {}

        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception ignored) {}

        out = null;

        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        running = false;
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "LanCast screen sharing",
                    NotificationManager.IMPORTANCE_LOW
            );

            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle("LanCast is sharing your screen")
                    .setContentText("Streaming H.264 to your TV")
                    .setSmallIcon(android.R.drawable.presence_video_online)
                    .setOngoing(true)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("LanCast is sharing your screen")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }
}

