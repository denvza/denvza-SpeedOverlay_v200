package com.speedoverlay.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button btnToggle;
    private TextView tvStatus;
    private boolean serviceRunning = false;

    // Launcher for overlay permission (Settings screen)
    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (Settings.canDrawOverlays(this)) {
                requestLocationPermission();
            } else {
                tvStatus.setText("⚠️ Overlay permission denied.\nPlease allow it in Settings.");
            }
        });

    // Launcher for location permission
    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            Boolean fine = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
            Boolean coarse = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);
            if (Boolean.TRUE.equals(fine) || Boolean.TRUE.equals(coarse)) {
                startOverlayService();
            } else {
                tvStatus.setText("⚠️ Location permission denied.\nSpeed cannot be measured without GPS.");
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);

        updateUI();

        btnToggle.setOnClickListener(v -> {
            if (serviceRunning) {
                stopOverlayService();
            } else {
                checkPermissionsAndStart();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void updateUI() {
        serviceRunning = OverlayService.isRunning;
        if (serviceRunning) {
            btnToggle.setText("Stop Overlay");
            btnToggle.setBackgroundTintList(getColorStateList(R.color.red));
            tvStatus.setText("✅ Overlay is active!\n\nYour speed is shown on screen.\nYou can drag it anywhere.");
        } else {
            btnToggle.setText("Start Overlay");
            btnToggle.setBackgroundTintList(getColorStateList(R.color.green));
            tvStatus.setText("Tap 'Start Overlay' to show your\nspeed on top of all other apps.");
        }
    }

    private void checkPermissionsAndStart() {
        // Step 1: Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            tvStatus.setText("Requesting overlay permission...");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayPermissionLauncher.launch(intent);
            return;
        }
        // Step 2: Check location permission
        requestLocationPermission();
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startOverlayService();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Speed overlay started!", Toast.LENGTH_SHORT).show();
        serviceRunning = true;
        updateUI();
    }

    private void stopOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        stopService(intent);
        Toast.makeText(this, "Speed overlay stopped.", Toast.LENGTH_SHORT).show();
        serviceRunning = false;
        updateUI();
    }
}
