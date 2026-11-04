package com.lancast.phone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 71;
    private EditText hostInput;
    private TextView info;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(24, 24, 24, 24);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("LanCast — Phone Sender");
        title.setTextSize(24);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Enter the TV Ethernet IP address. Keep both devices on the same LAN.");
        root.addView(hint);

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setText("192.168.1.6");
        hostInput.setHint("TV IP address");
        root.addView(hostInput);

        Button start = new Button(this);
        start.setText("Start screen sharing");
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("Stop sharing");
        root.addView(stop);

        info = new TextView(this);
        info.setText("Experimental 720p / 30 FPS / H.264");
        root.addView(info);

        start.setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            if (host.length() == 0) { info.setText("Enter TV IP first."); return; }
            Intent capture = ((MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE))
                    .createScreenCaptureIntent();
            startActivityForResult(capture, REQUEST_CAPTURE);
        });
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            info.setText("Stopped.");
        });
        setContentView(root);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);
            service.putExtra("host", hostInput.getText().toString().trim());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            info.setText("Connecting to TV and starting capture...");
        } else if (requestCode == REQUEST_CAPTURE) {
            info.setText("Screen capture permission was cancelled.");
        }
    }
}
