// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.fixture;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

public final class ImageFixture {
    public static byte[] png(int width, int height) {
        if (width == 128 && height == 96) return png();
        width = Math.max(100, Math.min(2000, width));
        height = Math.max(100, Math.min(8000, height));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(221, 232, 250));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float unit = width / 600f;
        paint.setColor(Color.rgb(65, 82, 130));
        paint.setTextSize(34 * unit);
        canvas.drawText("周末计划", 30 * unit, 65 * unit, paint);
        for (int top = 105, row = 1; top < height / unit; top += 120, row++) {
            paint.setColor(row % 2 == 0 ? Color.rgb(196, 217, 229) : Color.rgb(238, 215, 191));
            canvas.drawRoundRect(24 * unit, top * unit, width - 24 * unit, (top + 95) * unit, 12 * unit, 12 * unit, paint);
            paint.setColor(Color.rgb(65, 82, 100));
            paint.setTextSize(25 * unit);
            canvas.drawText(row + ". " + (row % 2 == 0 ? "散步 · 买点水果" : "出门走走 · 带上相机"), 44 * unit, (top + 58) * unit, paint);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    public static byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(40, 180, 190));
        Paint paint = new Paint();
        paint.setColor(Color.rgb(250, 150, 50));
        canvas.drawRect(0, 48, 128, 96, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(64, 48, 24, paint);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    public static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value));
        return result.toString();
    }
}
