// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.fixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.inputmethod.*;
import android.widget.*;
import java.io.*;

/** Test APK only. Pure framework Java so it also runs outside instrumentation. */
public class ImageClipboardActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 90, 24, 24);
        TextView heading = new TextView(this);
        heading.setText("Image clipboard test receiver");
        heading.setTextSize(20);
        content.addView(heading);
        TextView result = new TextView(this);
        result.setText("No image received");
        result.setTextSize(16);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("Received image preview");
        EditText editor = new EditText(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo attrs) {
                InputConnection base = super.onCreateInputConnection(attrs);
                if (getIntent().getBooleanExtra("acceptImages", true)) attrs.contentMimeTypes = new String[]{"image/*"};
                return new InputConnectionWrapper(base, true) {
                    @Override public boolean commitContent(InputContentInfo info, int flags, Bundle opts) {
                        boolean grant = (flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0;
                        try {
                            if (grant) info.requestPermission();
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            try (InputStream input = getContentResolver().openInputStream(info.getContentUri())) {
                                byte[] buffer = new byte[65536];
                                int count;
                                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                            }
                            byte[] bytes = output.toByteArray();
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bitmap == null) throw new IOException("Not an image");
                            try (OutputStream saved = new FileOutputStream(new File(getFilesDir(), "received-image.png"))) { saved.write(bytes); }
                            preview.setImageBitmap(bitmap);
                            result.setText("IMAGE_RECEIVED " + info.getDescription().getMimeType(0) + " " + bytes.length + " bytes grant=" + grant + " SHA256=" + ImageFixture.hash(bytes));
                            return true;
                        } catch (Exception error) {
                            result.setText("IMAGE_ERROR " + error.getClass().getSimpleName());
                            return false;
                        } finally { if (grant) info.releasePermission(); }
                    }
                };
            }
        };
        editor.setHint("Image-capable editor");
        editor.setSingleLine(true);
        Button copy = new Button(this);
        copy.setText("Copy test image");
        copy.setOnClickListener(view -> {
            try {
                try (OutputStream output = new FileOutputStream(new File(getCacheDir(), "clipboard-fixture.png"))) { output.write(ImageFixture.png()); }
                Uri uri = Uri.parse("content://" + getPackageName() + ".image-source/image");
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newUri(getContentResolver(), "Test image", uri));
                editor.requestFocus();
                getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception error) { result.setText("SOURCE_ERROR " + error.getClass().getSimpleName()); }
        });
        content.addView(copy);
        Button replace = new Button(this);
        replace.setText("Replace clipboard and remove source");
        replace.setOnClickListener(view -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("", "Source image removed"));
            new File(getCacheDir(), "clipboard-fixture.png").delete();
            result.setText("Source removed; history image must remain readable");
            editor.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
        content.addView(replace);
        content.addView(editor);
        content.addView(result);
        content.addView(preview, new LinearLayout.LayoutParams(-1, 220));
        setContentView(content);
        editor.requestFocus();
    }
}
