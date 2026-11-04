
package com.lancast.tv;

import android.app.Activity;
import android.os.Bundle;
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

public class ReceiverActivity extends Activity
        implements SurfaceHolder.Callback {

    private static final int PORT = 5000;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int MAX_PACKET = 4 * 1024 * 1024;

    private SurfaceView surfaceView;
    private TextView status;
    private ServerSocket serverSocket;
    private Thread serverThread;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Surface surface;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);

        layout.addView(
                surfaceView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setText("LanCast receiver starting on port 5000...");

        layout.addView(
                status,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        52
                )
        );

        setContentView(layout);
        surfaceView.getHolder().addCallback(this);
    }

    private void setStatus(final String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private void startServer() {
        if (running.getAndSet(true)) return;

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);

                setStatus(
                        "Ready — connect phone to " + getLocalIp() + ":" + PORT
                );

                while (running.get()) {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);

                    setStatus("Phone connected — waiting for H.264 config...");

                    new Thread(
                            new StreamClient(client),
                            "LanCast-stream"
                    ).start();
                }

            } catch (IOException e) {
                if (running.get()) {
                    setStatus("Server error: " + e.getMessage());
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

                java.util.Enumeration<java.net.InetAddress> addrs =
                        n.getInetAddresses();

                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();

                    if (!a.isLoopbackAddress()
                            && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}

        return "TV-LAN-IP";
    }

    private class StreamClient implements Runnable {
        private final Socket socket;

        private MediaCodec decoder;
        private byte[] sps;
        private byte[] pps;

        StreamClient(Socket s) {
            socket = s;
        }

        private void startDecoder() throws Exception {
            if (decoder != null) return;

            if (sps == null || pps == null) {
                return;
            }

            Surface currentSurface = surface;

            if (currentSurface == null || !currentSurface.isValid()) {
                setStatus("Waiting for TV display surface...");
                return;
            }

            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    WIDTH,
                    HEIGHT
            );

            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

            decoder = MediaCodec.createDecoderByType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
            );

            decoder.configure(format, currentSurface, null, 0);
            decoder.start();

            setStatus("H.264 decoder started — receiving video");
        }

        private void queueFrame(
                byte[] payload,
                long ptsUs,
                int flags
        ) throws Exception {

            if (decoder == null) {
                startDecoder();
            }

            if (decoder == null) {
                return;
            }

            int index;

            // Do not silently discard frames if the decoder is temporarily busy.
            do {
                index = decoder.dequeueInputBuffer(10000);

                if (index < 0 && (!running.get() || socket.isClosed())) {
                    return;
                }
            } while (index < 0);

            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer buffer = inputBuffers[index];

            buffer.clear();

            if (payload.length > buffer.capacity()) {
                decoder.queueInputBuffer(index, 0, 0, ptsUs, 0);
                throw new IOException(
                        "Frame too large: " + payload.length
                );
            }

            buffer.put(payload);

            decoder.queueInputBuffer(
                    index,
                    0,
                    payload.length,
                    ptsUs,
                    flags
            );

            drainDecoder();
        }

        private void drainDecoder() {
            if (decoder == null) return;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (true) {
                int outputIndex = decoder.dequeueOutputBuffer(info, 0);

                if (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, true);

                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    setStatus("Video format detected — displaying stream...");

                } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // Legacy API compatibility.
                    decoder.getOutputBuffers();

                } else {
                    break;
                }
            }
        }

        @Override
        public void run() {
            try {
                DataInputStream in = new DataInputStream(
                        socket.getInputStream()
                );

                while (running.get() && !socket.isClosed()) {
                    int type;

                    try {
                        type = in.readUnsignedByte();
                    } catch (IOException eof) {
                        break;
                    }

                    int length = in.readInt();

                    if (length <= 0 || length > MAX_PACKET) {
                        throw new IOException(
                                "Bad packet length: " + length
                        );
                    }

                    long ptsUs = in.readLong();
                    int flags = in.readInt();

                    byte[] payload = new byte[length];
                    in.readFully(payload);

                    if (type == 0) {
                        sps = payload;
                        setStatus("Received SPS — waiting for PPS...");
                        startDecoder();

                    } else if (type == 2) {
                        pps = payload;
                        setStatus("Received PPS — preparing decoder...");
                        startDecoder();

                    } else if (type == 1) {
                        if (decoder == null) {
                            startDecoder();
                        }

                        if (decoder != null) {
                            queueFrame(payload, ptsUs, flags);
                        }

                    } else {
                        throw new IOException(
                                "Unknown packet type: " + type
                        );
                    }
                }

            } catch (Exception e) {
                setStatus("Stream error: " + e.getMessage());

            } finally {
                if (decoder != null) {
                    try {
                        decoder.stop();
                    } catch (Exception ignored) {}

                    try {
                        decoder.release();
                    } catch (Exception ignored) {}

                    decoder = null;
                }

                try {
                    socket.close();
                } catch (Exception ignored) {}

                setStatus("Disconnected — waiting for phone");
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        startServer();
    }

    @Override
    public void surfaceChanged(
            SurfaceHolder holder,
            int format,
            int width,
            int height
    ) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
    }

    @Override
    protected void onDestroy() {
        running.set(false);

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}

        super.onDestroy();
    }
}

