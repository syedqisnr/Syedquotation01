package com.optimalsurgicals.quotationapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.getcapacitor.BridgeActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends BridgeActivity {
    private static final int SPEECH_REQUEST_CODE = 3003;
    private String currentSpeechTarget = "global";
    private long lastBackPressTime = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestAppPermissions();
        createReminderNotificationChannel();

        // Register modern AndroidX OnBackPressedCallback for edge-swipe & hardware back gestures
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getBridge().getWebView();
                if (webView != null) {
                    webView.evaluateJavascript(
                        "(function(){ return (typeof window.handleAppBackButton === 'function') ? window.handleAppBackButton() : false; })()",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                if (value != null && (value.equals("true") || value.contains("true"))) {
                                    // Successfully navigated back within app or closed modal!
                                    return;
                                }
                                // User is on Home Dashboard; enforce double-back to exit
                                runOnUiThread(() -> {
                                    long currentTime = System.currentTimeMillis();
                                    if (currentTime - lastBackPressTime > 2500) {
                                        lastBackPressTime = currentTime;
                                        Toast.makeText(MainActivity.this, "Tap back again to exit Optimal Intelligence", Toast.LENGTH_SHORT).show();
                                    } else {
                                        setEnabled(false);
                                        MainActivity.this.finish();
                                    }
                                });
                            }
                        }
                    );
                } else {
                    MainActivity.this.finish();
                }
            }
        });

        try {
            WebView webView = getBridge().getWebView();
            webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
            
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(final PermissionRequest request) {
                    runOnUiThread(() -> request.grant(request.getResources()));
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    String scheme = uri != null ? uri.getScheme() : "";
                    if (scheme != null && (scheme.equals("whatsapp") || scheme.equals("intent") || scheme.equals("tel") || scheme.equals("mailto"))) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                            startActivity(intent);
                            return true;
                        } catch (Exception e) {
                            return true;
                        }
                    }
                    return false;
                }
            });

            webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
                if (url != null && url.startsWith("data:image")) {
                    saveImageToGalleryDirect(url, "Optimal_Intelligence_" + System.currentTimeMillis() + ".jpg");
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void requestAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] perms = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            };
            ActivityCompat.requestPermissions(this, perms, 1001);
        } else {
            String[] perms = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            };
            ActivityCompat.requestPermissions(this, perms, 1001);
        }
    }

    /** Pre-creates the notification channel so Follow-Up reminders can post a sound+vibration
     *  alert the very first time one fires, without waiting on any other code path. */
    private void createReminderNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(ReminderReceiver.CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        ReminderReceiver.CHANNEL_ID,
                        "Follow-Up Reminders",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Alerts you when a scheduled hospital/client follow-up is due.");
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveImageToGallery(String base64Data, String filename) {
            runOnUiThread(() -> saveImageToGalleryDirect(base64Data, filename));
        }

        @JavascriptInterface
        public void shareImage(String base64Data, String filename, String caption) {
            runOnUiThread(() -> shareImageDirect(base64Data, filename, caption));
        }

        @JavascriptInterface
        public void sharePdf(String base64PdfData, String filename, String caption) {
            runOnUiThread(() -> sharePdfDirect(base64PdfData, filename, caption));
        }

        @JavascriptInterface
        public void savePdfToDownloads(String base64PdfData, String filename) {
            runOnUiThread(() -> savePdfToDownloadsDirect(base64PdfData, filename));
        }

        @JavascriptInterface
        public void startVoiceDictation(String targetFieldId) {
            runOnUiThread(() -> {
                currentSpeechTarget = targetFieldId != null ? targetFieldId : "global";
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak into Optimal Intelligence Voice Ledger...");
                try {
                    startActivityForResult(intent, SPEECH_REQUEST_CODE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Voice recognition not available", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }

        @JavascriptInterface
        public String getVersion() {
            return "22.0.1 (135 Tools)";
        }

        /**
         * Schedules a real Android alarm that fires a sound + vibration notification at
         * triggerAtMillis, even if the app has been closed. Used by the Follow-Up & Reminder
         * Hub so a due follow-up is never missed just because nobody opened the app that day.
         */
        @JavascriptInterface
        public void scheduleFollowUpReminder(String id, String title, String message, long triggerAtMillis) {
            runOnUiThread(() -> {
                try {
                    AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                    if (am == null || id == null) return;

                    Intent intent = new Intent(MainActivity.this, ReminderReceiver.class);
                    intent.putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id);
                    intent.putExtra(ReminderReceiver.EXTRA_TITLE, title);
                    intent.putExtra(ReminderReceiver.EXTRA_MESSAGE, message);

                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        flags |= PendingIntent.FLAG_IMMUTABLE;
                    }
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(
                            MainActivity.this, id.hashCode(), intent, flags
                    );

                    // Inexact-but-power-friendly alarm: does not require the special
                    // SCHEDULE_EXACT_ALARM permission and still reliably fires while the
                    // device is idle/doze, within a few minutes of the requested time.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                    } else {
                        am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not schedule reminder: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void cancelFollowUpReminder(String id) {
            runOnUiThread(() -> {
                try {
                    AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                    if (am == null || id == null) return;
                    Intent intent = new Intent(MainActivity.this, ReminderReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        flags |= PendingIntent.FLAG_IMMUTABLE;
                    }
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(
                            MainActivity.this, id.hashCode(), intent, flags
                    );
                    am.cancel(pendingIntent);
                } catch (Exception e) {
                    // Non-fatal — worst case a stale reminder still fires once.
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spokenText = results.get(0);
                WebView webView = getBridge().getWebView();
                if (webView != null) {
                    String js = "if(window.handleNativeSpeechResult){ window.handleNativeSpeechResult('" +
                            spokenText.replace("'", "\\'") + "', '" + currentSpeechTarget + "'); }";
                    webView.evaluateJavascript(js, null);
                }
            }
        }
    }

    /**
     * Runs a small JS snippet in the WebView so the web layer gets a REAL confirmation
     * of whether a native save/share operation actually succeeded, instead of the web
     * layer optimistically assuming success the instant it calls the bridge method.
     */
    private void notifyJs(final String jsExpression) {
        runOnUiThread(() -> {
            WebView webView = getBridge().getWebView();
            if (webView != null) {
                webView.evaluateJavascript(jsExpression, null);
            }
        });
    }

    private String jsStringLiteral(String s) {
        if (s == null) return "null";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    private void saveImageToGalleryDirect(String base64Data, String filename) {
        try {
            String cleanData = base64Data;
            if (cleanData.contains(",")) {
                cleanData = cleanData.substring(cleanData.indexOf(",") + 1);
            }
            byte[] imageBytes = Base64.decode(cleanData, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            String name = (filename != null && !filename.isEmpty()) ? filename : ("Optimal_Stamped_" + System.currentTimeMillis() + ".jpg");
            String mime = name.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            Bitmap.CompressFormat format = name.toLowerCase().endsWith(".png") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OptimalIntelligence");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        bitmap.compress(format, 98, out);
                        out.flush();
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);

                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
                    Toast.makeText(this, "✅ Image Saved to Gallery (Pictures/OptimalIntelligence)", Toast.LENGTH_LONG).show();
                    notifyJs("window.__onNativeGallerySaveResult && window.__onNativeGallerySaveResult(true, " + jsStringLiteral(name) + ", null)");
                } else {
                    Toast.makeText(this, "❌ Could not create gallery entry", Toast.LENGTH_LONG).show();
                    notifyJs("window.__onNativeGallerySaveResult && window.__onNativeGallerySaveResult(false, " + jsStringLiteral(name) + ", " + jsStringLiteral("MediaStore insert returned null") + ")");
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OptimalIntelligence");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(format, 98, out);
                    out.flush();
                }
                Intent mediaScan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaScan.setData(Uri.fromFile(file));
                sendBroadcast(mediaScan);
                MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
                Toast.makeText(this, "✅ Image Saved to Gallery: " + file.getName(), Toast.LENGTH_LONG).show();
                notifyJs("window.__onNativeGallerySaveResult && window.__onNativeGallerySaveResult(true, " + jsStringLiteral(file.getName()) + ", null)");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Save Gallery Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            notifyJs("window.__onNativeGallerySaveResult && window.__onNativeGallerySaveResult(false, " + jsStringLiteral(filename) + ", " + jsStringLiteral(e.getMessage()) + ")");
        }
    }

    private void shareImageDirect(String base64Data, String filename, String caption) {
        try {
            String cleanData = base64Data;
            if (cleanData.contains(",")) {
                cleanData = cleanData.substring(cleanData.indexOf(",") + 1);
            }
            byte[] imageBytes = Base64.decode(cleanData, Base64.DEFAULT);

            File cachePath = new File(getCacheDir(), "images");
            if (!cachePath.exists()) cachePath.mkdirs();
            String name = (filename != null && !filename.isEmpty()) ? filename : ("Optimal_Image_" + System.currentTimeMillis() + ".jpg");
            File newFile = new File(cachePath, name);
            try (FileOutputStream stream = new FileOutputStream(newFile)) {
                stream.write(imageBytes);
            }

            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", newFile);
            if (contentUri != null) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setDataAndType(contentUri, name.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg");
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                if (caption != null && !caption.isEmpty()) {
                    shareIntent.putExtra(Intent.EXTRA_TEXT, caption);
                }
                startActivity(Intent.createChooser(shareIntent, "Share Document via"));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error sharing: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void sharePdfDirect(String base64PdfData, String filename, String caption) {
        try {
            String cleanData = base64PdfData;
            if (cleanData.contains(",")) {
                cleanData = cleanData.substring(cleanData.indexOf(",") + 1);
            }
            byte[] pdfBytes = Base64.decode(cleanData, Base64.DEFAULT);

            File cachePath = new File(getCacheDir(), "documents");
            if (!cachePath.exists()) cachePath.mkdirs();
            String name = (filename != null && !filename.isEmpty()) ? filename : ("Optimal_Stamped_" + System.currentTimeMillis() + ".pdf");
            File pdfFile = new File(cachePath, name);
            try (FileOutputStream stream = new FileOutputStream(pdfFile)) {
                stream.write(pdfBytes);
            }

            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
            if (contentUri != null) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setDataAndType(contentUri, "application/pdf");
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                if (caption != null && !caption.isEmpty()) {
                    shareIntent.putExtra(Intent.EXTRA_TEXT, caption);
                }
                startActivity(Intent.createChooser(shareIntent, "Share Stamped PDF via"));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error sharing PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void savePdfToDownloadsDirect(String base64PdfData, String filename) {
        try {
            String cleanData = base64PdfData;
            if (cleanData.contains(",")) {
                cleanData = cleanData.substring(cleanData.indexOf(",") + 1);
            }
            byte[] pdfBytes = Base64.decode(cleanData, Base64.DEFAULT);
            String name = (filename != null && !filename.isEmpty()) ? filename : ("Optimal_Stamped_" + System.currentTimeMillis() + ".pdf");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OptimalIntelligence");
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        out.write(pdfBytes);
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    Toast.makeText(this, "✅ PDF Saved to Downloads/OptimalIntelligence", Toast.LENGTH_LONG).show();
                    notifyJs("window.__onNativePdfSaveResult && window.__onNativePdfSaveResult(true, " + jsStringLiteral(name) + ", null)");
                } else {
                    Toast.makeText(this, "❌ Could not create Downloads entry", Toast.LENGTH_LONG).show();
                    notifyJs("window.__onNativePdfSaveResult && window.__onNativePdfSaveResult(false, " + jsStringLiteral(name) + ", " + jsStringLiteral("MediaStore insert returned null") + ")");
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OptimalIntelligence");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(pdfBytes);
                }
                Toast.makeText(this, "✅ PDF Saved: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                notifyJs("window.__onNativePdfSaveResult && window.__onNativePdfSaveResult(true, " + jsStringLiteral(file.getName()) + ", null)");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error saving PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            notifyJs("window.__onNativePdfSaveResult && window.__onNativePdfSaveResult(false, " + jsStringLiteral(filename) + ", " + jsStringLiteral(e.getMessage()) + ")");
        }
    }

    /**
     * Nested (not a separate top-level file) so the existing CI workflow — which only
     * injects MainActivity.java into the generated Android project — picks this up
     * automatically without needing a second file-injection step.
     *
     * Fires when a scheduled Follow-Up alarm goes off (see scheduleFollowUpReminder
     * above). Posts an audible + vibrating notification even if the app is closed, so a
     * follow-up is never silently missed just because nobody happened to open the
     * Follow-Up Hub that day.
     */
    public static class ReminderReceiver extends BroadcastReceiver {

        public static final String CHANNEL_ID = "optimal_followup_reminders";
        public static final String EXTRA_REMINDER_ID = "reminder_id";
        public static final String EXTRA_TITLE = "reminder_title";
        public static final String EXTRA_MESSAGE = "reminder_message";

        @Override
        public void onReceive(Context context, Intent intent) {
            String reminderId = intent.getStringExtra(EXTRA_REMINDER_ID);
            String title = intent.getStringExtra(EXTRA_TITLE);
            String message = intent.getStringExtra(EXTRA_MESSAGE);
            if (title == null) title = "Optimal Intelligence Follow-Up";
            if (message == null) message = "You have a follow-up due. Tap to open the app.";

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
                if (channel == null) {
                    channel = new NotificationChannel(
                            CHANNEL_ID,
                            "Follow-Up Reminders",
                            NotificationManager.IMPORTANCE_HIGH
                    );
                    channel.setDescription("Alerts you when a scheduled hospital/client follow-up is due.");
                    channel.enableVibration(true);
                    channel.setVibrationPattern(new long[]{0, 350, 200, 350});
                    channel.setSound(soundUri, new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    nm.createNotificationChannel(channel);
                }
            }

            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent contentIntent = PendingIntent.getActivity(
                    context,
                    (reminderId != null ? reminderId.hashCode() : 0),
                    launchIntent,
                    flags
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setVibrate(new long[]{0, 350, 200, 350})
                    .setContentIntent(contentIntent);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setSound(soundUri);
            }

            int notificationId = reminderId != null ? reminderId.hashCode() : (int) System.currentTimeMillis();
            nm.notify(notificationId, builder.build());
        }
    }
}
