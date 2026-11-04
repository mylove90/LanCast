package com.lancast.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    private static final int PORT = 5000;
    private SurfaceView surfaceView;
    private TextView status;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Surface surface;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        layout.addView(surfaceView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setText("LanCast receiver starting on port 5000...");
        layout.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 52));
        setContentView(layout);
        surfaceView.getHolder().addCallback(this);

        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "LanCast:Receiver");
        wakeLock.acquire();
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() { @Override public void run() { status.setText(text); }});
    }

    private void startServer() {
        if (running.getAndSet(true)) return;
        serverThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    setStatus("Ready — connect phone to " + getLocalIp() + ":" + PORT);
                    while (running.get()) {
                        Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        setStatus("Phone connected — waiting for H.264...");
                        new Thread(new StreamClient(client), "LanCast-stream").start();
                    }
                } catch (IOException e) {
                    if (running.get()) setStatus("Server error: " + e.getMessage());
                }
            }
        }, "LanCast-server");
        serverThread.start();
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nets =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                java.net.NetworkInterface n = nets.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = n.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address)
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "TV-LAN-IP";
    }

    private class StreamClient implements Runnable {
        private final Socket socket;
        StreamClient(Socket s) { socket = s; }

        @Override public void run() {
            MediaCodec decoder = null;
            DataInputStream in = null;
            try {
                in = new DataInputStream(socket.getInputStream());
                while (running.get() && !socket.isClosed()) {
                    int type;
                    try { type = in.readUnsignedByte(); } catch (IOException eof) { break; }
                    int length = in.readInt();
                    if (length < 0 || length > 4 * 1024 * 1024) throw new IOException("Bad packet length");
                    long ptsUs = in.readLong();
                    int flags = in.readInt();
                    byte[] payload = new byte[length];
                    in.readFully(payload);

                    if (decoder == null) {
                        if (surface == null || !surface.isValid()) {
                            setStatus("Waiting for TV display surface...");
                            continue;
                        }
                        MediaFormat format = MediaFormat.createVideoFormat(
                                MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
                        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                        decoder.configure(format, surface, null, 0);
                        decoder.start();
                        setStatus("H.264 decoder started — receiving video");
                    }

                    int index = decoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
                        ByteBuffer buffer = inputBuffers[index];
                        buffer.clear();
                        if (payload.length > buffer.capacity()) throw new IOException("Frame too large");
                        buffer.put(payload);
                        decoder.queueInputBuffer(index, 0, payload.length, ptsUs, flags);
                    }

                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int out;
                    while ((out = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                        decoder.releaseOutputBuffer(out, true);
                    }
                }
            } catch (Exception e) {
                setStatus("Stream ended: " + e.getMessage());
            } finally {
                if (decoder != null) {
                    try { decoder.stop(); } catch (Exception ignored) {}
                    try { decoder.release(); } catch (Exception ignored) {}
                }
                try { socket.close(); } catch (Exception ignored) {}
                setStatus("Disconnected — waiting for phone");
            }
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { surface = holder.getSurface(); startServer(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surface = null; }

    @Override protected void onDestroy() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
