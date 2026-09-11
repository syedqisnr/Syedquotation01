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
import android.webkit.WebView;
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
    private static final int FILE_CHOOSER_REQUEST_CODE = 4004;
    private String currentSpeechTarget = "global";
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Permissions requested on-demand only when downloading or speaking

        try {
            WebView webView = getBridge().getWebView();
            webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(final PermissionRequest request) {
                    runOnUiThread(() -> request.grant(request.getResources()));
                }

                // Required for <input type="file"> (Select/Choose from Gallery buttons) to work.
                // Without this override, replacing the WebChromeClient silently disables the
                // Capacitor bridge's built-in file chooser and the gallery picker never opens.
                @Override
                public boolean onShowFileChooser(WebView webViewParam, ValueCallback<Uri[]> callback, FileChooserParams fileChooserParams) {
                    if (MainActivity.this.filePathCallback != null) {
                        MainActivity.this.filePathCallback.onReceiveValue(null);
                        MainActivity.this.filePathCallback = null;
                    }
                    MainActivity.this.filePathCallback = callback;

                    try {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("image/*");
                        startActivityForResult(Intent.createChooser(intent, "Select Photo"), FILE_CHOOSER_REQUEST_CODE);
                    } catch (Exception e) {
                        MainActivity.this.filePathCallback = null;
                        return false;
                    }
                    return true;
                }
            });
            webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
                if (url != null && url.startsWith("data:image")) {
                    saveImageToGalleryDirect(url, "Optimal_Stamped_" + System.currentTimeMillis() + ".jpg");
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void requestAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                }, 100);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                }, 100);
            }
        }
    }

    private void saveImageToGalleryDirect(final String base64Data, final String fileName) {
        // Just-in-time permission check for legacy Android 9 and below
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            }
        }

        runOnUiThread(() -> {
            try {
                String cleanBase64 = base64Data.contains(",") ? base64Data.split(",")[1] : base64Data;
                byte[] imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                String name = (fileName != null && !fileName.isEmpty()) ? fileName : ("Optimal_Stamped_" + System.currentTimeMillis() + ".jpg");
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OptimalSurgicals");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                        out.close();
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);
                    }
                    Toast.makeText(MainActivity.this, "✅ Image Saved to Phone Photos / Gallery!", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Save Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }

        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                String cleanSpoken = matches.get(0).replace("'", "\\'").replace("\"", "\\\"");
                getBridge().getWebView().evaluateJavascript("window.app && window.app.onSpeechResult ? window.app.onSpeechResult('" + currentSpeechTarget + "', '" + cleanSpoken + "') : null;", null);
            }
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveImageToGallery(final String base64Data, final String fileName) {
            saveImageToGalleryDirect(base64Data, fileName);
        }

        @JavascriptInterface
        public void shareImage(final String base64Data, final String shareText) {
            runOnUiThread(() -> {
                try {
                    String cleanBase64 = base64Data.contains(",") ? base64Data.split(",")[1] : base64Data;
                    byte[] imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT);
                    File cachePath = new File(getCacheDir(), "images");
                    cachePath.mkdirs();
                    File imageFile = new File(cachePath, "stamped_delivery_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream stream = new FileOutputStream(imageFile);
                    stream.write(imageBytes);
                    stream.close();

                    Uri contentUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", imageFile);
                    if (contentUri != null) {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        shareIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        if (shareText != null) shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
                        shareIntent.setType("image/jpeg");
                        startActivity(Intent.createChooser(shareIntent, "Share Image via"));
                    }
                } catch (Exception e) {
                    try {
                        Intent waIntent = new Intent(Intent.ACTION_VIEW);
                        waIntent.setData(Uri.parse("https://api.whatsapp.com/send?text=" + Uri.encode(shareText)));
                        startActivity(waIntent);
                    } catch (Exception ex) {}
                }
            });
        }

        @JavascriptInterface
        public void sharePdf(final String base64PdfData, final String fileName, final String shareText) {
            runOnUiThread(() -> {
                try {
                    String cleanBase64 = base64PdfData.contains(",") ? base64PdfData.split(",")[1] : base64PdfData;
                    byte[] pdfBytes = Base64.decode(cleanBase64, Base64.DEFAULT);
                    File cachePath = new File(getCacheDir(), "documents");
                    cachePath.mkdirs();
                    String name = (fileName != null && !fileName.isEmpty()) ? fileName : ("Optimal_Doc_" + System.currentTimeMillis() + ".pdf");
                    File pdfFile = new File(cachePath, name);
                    FileOutputStream stream = new FileOutputStream(pdfFile);
                    stream.write(pdfBytes);
                    stream.close();

                    Uri contentUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", pdfFile);
                    if (contentUri != null) {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        shareIntent.setDataAndType(contentUri, "application/pdf");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        if (shareText != null) shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
                        shareIntent.setType("application/pdf");
                        startActivity(Intent.createChooser(shareIntent, "Share PDF via"));
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "PDF Share Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void savePdfToDownloads(final String base64PdfData, final String fileName) {
            runOnUiThread(() -> {
                try {
                    String cleanBase64 = base64PdfData.contains(",") ? base64PdfData.split(",")[1] : base64PdfData;
                    byte[] pdfBytes = Base64.decode(cleanBase64, Base64.DEFAULT);
                    String name = (fileName != null && !fileName.isEmpty()) ? fileName : ("Optimal_Doc_" + System.currentTimeMillis() + ".pdf");
                    
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OptimalSurgicals");
                        values.put(MediaStore.Downloads.IS_PENDING, 1);
                    }
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        OutputStream out = getContentResolver().openOutputStream(uri);
                        if (out != null) {
                            out.write(pdfBytes);
                            out.close();
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear();
                            values.put(MediaStore.Downloads.IS_PENDING, 0);
                            getContentResolver().update(uri, values, null, null);
                        }
                        Toast.makeText(MainActivity.this, "✅ PDF Saved to Downloads folder!", Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Save PDF Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void startSpeechRecognition(final String targetField) {
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, 102);
            }

            runOnUiThread(() -> {
                currentSpeechTarget = (targetField != null) ? targetField : "global";
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
                intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Optimal Command Voice Assistant — Speak now");
                try {
                    startActivityForResult(intent, SPEECH_REQUEST_CODE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Voice recognition not available", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        runOnUiThread(() -> {
            try {
                WebView webView = getBridge().getWebView();
                if (webView != null) {
                    webView.evaluateJavascript("window.app && window.app.handleBackButton ? window.app.handleBackButton() : false;", value -> {
                        if ("true".equals(value)) {
                            // Handled in-app by returning to Dashboard or closing modal!
                        } else if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            super.onBackPressed();
                        }
                    });
                } else {
                    super.onBackPressed();
                }
            } catch (Exception e) {
                super.onBackPressed();
            }
        });
    }

}
