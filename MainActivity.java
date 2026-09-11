package com.optimalsurgicals.quotationapp;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.core.app.ActivityCompat;
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
                Manifest.permission.READ_MEDIA_IMAGES
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

    @Override
    public void onBackPressed() {
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            webView.evaluateJavascript(
                "(function(){ return (typeof window.handleAppBackButton === 'function') ? window.handleAppBackButton() : false; })()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (value != null && (value.equals("true") || value.contains("true"))) {
                            // Handled inside webview (closed modal or went back to previous tool/dashboard)
                            return;
                        }
                        // If already on dashboard, require double-tap to exit
                        runOnUiThread(() -> {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastBackPressTime > 2500) {
                                lastBackPressTime = currentTime;
                                Toast.makeText(MainActivity.this, "Tap back again to exit Optimal Intelligence", Toast.LENGTH_SHORT).show();
                            } else {
                                MainActivity.super.onBackPressed();
                            }
                        });
                    }
                }
            );
        } else {
            super.onBackPressed();
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
            return "22.0.0 (133 Tools)";
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

    private void saveImageToGalleryDirect(String base64Data, String filename) {
        try {
            String cleanData = base64Data;
            if (cleanData.contains(",")) {
                cleanData = cleanData.substring(cleanData.indexOf(",") + 1);
            }
            byte[] imageBytes = Base64.decode(cleanData, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OptimalIntelligence");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    Toast.makeText(this, "✅ Image Saved to Pictures/OptimalIntelligence", Toast.LENGTH_LONG).show();
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OptimalIntelligence");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                }
                Intent mediaScan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaScan.setData(Uri.fromFile(file));
                sendBroadcast(mediaScan);
                Toast.makeText(this, "✅ Saved: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error saving image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            File newFile = new File(cachePath, filename);
            try (FileOutputStream stream = new FileOutputStream(newFile)) {
                stream.write(imageBytes);
            }

            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", newFile);
            if (contentUri != null) {
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
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
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OptimalIntelligence");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(pdfBytes);
                }
                Toast.makeText(this, "✅ PDF Saved: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error saving PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
