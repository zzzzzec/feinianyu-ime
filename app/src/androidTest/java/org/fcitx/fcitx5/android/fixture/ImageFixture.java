// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.fixture;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

public final class ImageFixture {
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
