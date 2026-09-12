package com.optimalsurgicals.quotationapp;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
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
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import com.getcapacitor.BridgeActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Executor;

public class MainActivity extends BridgeActivity {
    private static final int SPEECH_REQUEST_CODE = 3003;
    private static final int FILE_CHOOSER_REQUEST_CODE = 4004;
    private static final int CAMERA_DIRECT_REQUEST_CODE = 5005;
    private static final int GALLERY_DIRECT_REQUEST_CODE = 6006;


    private long lastBackPressTime = 0L;

    @Override
    public void onBackPressed() {
        try {
            WebView webView = getBridge().getWebView();
            // Call the REAL in-app back handler (handleUniversalBack / window.app.handleBackButton).
            // 'window.optimalSmartBack' never exists in the live app (it only lives inside an
            // exported-HTML string template), so this used to always fall through and close the app.
            String js = "(function(){ try{"
                    + " if(typeof window.handleUniversalBack==='function'){ return !!window.handleUniversalBack(); }"
                    + " if(window.app && typeof window.app.handleBackButton==='function'){ return !!window.app.handleBackButton(); }"
                    + " }catch(e){} return false; })()";
            webView.evaluateJavascript(js, value -> {
                boolean handledInApp = value != null && "true".equals(value);
                if (handledInApp) return;

                // Not handled in-app (already on the dashboard/root screen).
                // Require a second back-press within 2s before actually exiting, so a single
                // accidental edge-swipe never instantly kills the app.
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < 2000) {
                    MainActivity.super.onBackPressed();
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(MainActivity.this, "Press back again to exit", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            super.onBackPressed();
        }
    }

    private String currentSpeechTarget = "global";
    private ValueCallback<Uri[]> mFilePathCallback;
    private Uri mCameraOutputUri;

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

                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                    if (mFilePathCallback != null) {
                        mFilePathCallback.onReceiveValue(null);
                        mFilePathCallback = null;
                    }
                    mFilePathCallback = filePathCallback;

                    try {
                        // 1. Camera Intent with FileProvider output URI
                        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera_" + System.currentTimeMillis() + ".jpg");
                        mCameraOutputUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", photoFile);
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraOutputUri);
                        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        // 2. Gallery / Document Selection Intent
                        Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        contentSelectionIntent.setType("image/*");

                        // 3. System Chooser with both options
                        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                        chooserIntent.putExtra(Intent.EXTRA_TITLE, "Select Photo from Gallery or Take with Camera");
                        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{takePictureIntent});

                        startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE);
                        return true;
                    } catch (Exception e) {
                        if (mFilePathCallback != null) {
                            mFilePathCallback.onReceiveValue(null);
                            mFilePathCallback = null;
                        }
                        Toast.makeText(MainActivity.this, "Chooser Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        return false;
                    }
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
        ArrayList<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        ArrayList<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }

        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 100);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            boolean anyDenied = false;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { anyDenied = true; break; }
            }
            if (anyDenied) {
                Toast.makeText(this, "Some permissions were denied — camera/gallery save may not work until allowed in Settings.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void passBitmapToWebView(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            // Resize if huge to prevent memory crash
            int maxDim = 2000;
            Bitmap scaled = bitmap;
            if (bitmap.getWidth() > maxDim || bitmap.getHeight() > maxDim) {
                float s = Math.min((float) maxDim / bitmap.getWidth(), (float) maxDim / bitmap.getHeight());
                scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * s), Math.round(bitmap.getHeight() * s), true);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, baos);
            byte[] bytes = baos.toByteArray();
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            runOnUiThread(() -> {
                String js = "if(window.loadCapturedImageDirect){ window.loadCapturedImageDirect('data:image/jpeg;base64," + b64 + "'); } " +
                            "else if(window.loadStampFromBase64){ window.loadStampFromBase64('data:image/jpeg;base64," + b64 + "'); } " +
                            "else if(window.loadCapturedPhotoBase64){ window.loadCapturedPhotoBase64('data:image/jpeg;base64," + b64 + "'); }";
                getBridge().getWebView().evaluateJavascript(js, null);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 1. Web File Chooser Callback
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mFilePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK) {
                    if (data == null || data.getData() == null) {
                        if (mCameraOutputUri != null) {
                            results = new Uri[]{mCameraOutputUri};
                        }
                    } else {
                        String dataString = data.getDataString();
                        ClipData clipData = data.getClipData();
                        if (clipData != null) {
                            results = new Uri[clipData.getItemCount()];
                            for (int i = 0; i < clipData.getItemCount(); i++) {
                                results[i] = clipData.getItemAt(i).getUri();
                            }
                        } else if (dataString != null) {
                            results = new Uri[]{Uri.parse(dataString)};
                        }
                    }
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
        }

        // 2. Direct Native Camera Result
        if (requestCode == CAMERA_DIRECT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            try {
                Bitmap bmp = null;
                if (mCameraOutputUri != null) {
                    InputStream in = getContentResolver().openInputStream(mCameraOutputUri);
                    bmp = BitmapFactory.decodeStream(in);
                    if (in != null) in.close();
                } else if (data != null && data.getExtras() != null) {
                    bmp = (Bitmap) data.getExtras().get("data");
                }
                if (bmp != null) {
                    passBitmapToWebView(bmp);
                    Toast.makeText(this, "📷 Camera Photo Loaded!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Camera load error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        // 3. Direct Native Gallery Result
        if (requestCode == GALLERY_DIRECT_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream in = getContentResolver().openInputStream(data.getData());
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (in != null) in.close();
                if (bmp != null) {
                    passBitmapToWebView(bmp);
                    Toast.makeText(this, "📁 Gallery Photo Loaded!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Gallery load error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        // 4. Speech Recognition Result
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                String cleanSpoken = matches.get(0).replace("'", "\\'").replace("\"", "\\\"");
                getBridge().getWebView().evaluateJavascript("window.app && window.app.onSpeechResult ? window.app.onSpeechResult('" + currentSpeechTarget + "', '" + cleanSpoken + "') : null;", null);
            }
        }
    }

    private void saveImageToGalleryDirect(final String base64Data, final String fileName) {
        saveImageToGalleryDirect(base64Data, fileName, null);
    }

    // Reports a real success/failure result back to the WebView instead of the old
    // fire-and-forget approach, so the UI can stop lying about whether the save worked.
    private void notifyJsSaveResult(final String callbackId, final boolean success, final String message) {
        if (callbackId == null || callbackId.isEmpty()) return;
        try {
            WebView webView = getBridge() != null ? getBridge().getWebView() : null;
            if (webView == null) return;
            String safeMsg = message == null ? "" : message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
            String safeId = callbackId.replace("\\", "\\\\").replace("'", "\\'");
            String js = "window.__onGallerySaveResult && window.__onGallerySaveResult('" + safeId + "', " + success + ", '" + safeMsg + "');";
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    // Same pattern for real biometric authentication results (fingerprint/face).
    private void notifyJsBiometricResult(final String callbackId, final boolean success, final String message) {
        if (callbackId == null || callbackId.isEmpty()) return;
        try {
            WebView webView = getBridge() != null ? getBridge().getWebView() : null;
            if (webView == null) return;
            String safeMsg = message == null ? "" : message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
            String safeId = callbackId.replace("\\", "\\\\").replace("'", "\\'");
            String js = "window.__onBiometricAuthResult && window.__onBiometricAuthResult('" + safeId + "', " + success + ", '" + safeMsg + "');";
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    private void saveImageToGalleryDirect(final String base64Data, final String fileName, final String callbackId) {
        runOnUiThread(() -> {
            try {
                if (base64Data == null || base64Data.isEmpty()) {
                    notifyJsSaveResult(callbackId, false, "No image data received");
                    return;
                }

                // Verify we actually hold storage permission on pre-Android-10 devices;
                // on Android 10+ the MediaStore insert below works without it.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "Storage permission not granted — allow it in Settings to save images.", Toast.LENGTH_LONG).show();
                    notifyJsSaveResult(callbackId, false, "Storage permission not granted");
                    return;
                }

                String cleanBase64 = base64Data.contains(",") ? base64Data.split(",")[1] : base64Data;
                byte[] imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                if (bitmap == null) {
                    Toast.makeText(MainActivity.this, "Save Error: could not decode image data", Toast.LENGTH_SHORT).show();
                    notifyJsSaveResult(callbackId, false, "Could not decode image data");
                    return;
                }

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
                    boolean written = false;
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        written = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                        out.flush();
                        out.close();
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);
                    }
                    if (written) {
                        Toast.makeText(MainActivity.this, "✅ Image Saved to Phone Photos / Gallery!", Toast.LENGTH_LONG).show();
                        notifyJsSaveResult(callbackId, true, "Saved");
                    } else {
                        // Clean up the empty/broken MediaStore row so it doesn't show as a 0-byte ghost image.
                        try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
                        Toast.makeText(MainActivity.this, "Save Error: could not write image bytes", Toast.LENGTH_SHORT).show();
                        notifyJsSaveResult(callbackId, false, "Could not write image bytes");
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Save Error: MediaStore refused the request", Toast.LENGTH_SHORT).show();
                    notifyJsSaveResult(callbackId, false, "MediaStore insert returned null");
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Save Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                notifyJsSaveResult(callbackId, false, String.valueOf(e.getMessage()));
            }
        });
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void openCamera() { openNativeCamera(); }

        // Triggers the device's REAL fingerprint/face sensor via Android's native
        // BiometricPrompt and reports a genuine success/failure result back to the WebView.
        // (Previously the app only faked this with a JS timer that always "succeeded".)
        @JavascriptInterface
        public void authenticateBiometric(final String callbackId) {
            runOnUiThread(() -> {
                try {
                    BiometricManager biometricManager = BiometricManager.from(MainActivity.this);
                    int canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
                    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                        String reason;
                        switch (canAuth) {
                            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                                reason = "No fingerprint/face hardware on this device"; break;
                            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                                reason = "Biometric hardware is currently unavailable"; break;
                            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                                reason = "No fingerprint/face enrolled — add one in phone Settings"; break;
                            default:
                                reason = "Biometric authentication is not available";
                        }
                        notifyJsBiometricResult(callbackId, false, reason);
                        return;
                    }

                    Executor executor = ContextCompat.getMainExecutor(MainActivity.this);
                    BiometricPrompt biometricPrompt = new BiometricPrompt(MainActivity.this, executor,
                            new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            notifyJsBiometricResult(callbackId, true, "Verified");
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            notifyJsBiometricResult(callbackId, false, String.valueOf(errString));
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();
                            // Fingerprint didn't match — the system dialog stays open and lets
                            // the user retry, so we don't report a final result yet.
                        }
                    });

                    BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Optimal Command — Unlock")
                            .setSubtitle("Verify your fingerprint or face to continue")
                            .setNegativeButtonText("Use PIN instead")
                            .build();

                    biometricPrompt.authenticate(promptInfo);
                } catch (Exception e) {
                    notifyJsBiometricResult(callbackId, false, String.valueOf(e.getMessage()));
                }
            });
        }

        @JavascriptInterface
        public void openNativeCamera() {
            runOnUiThread(() -> {
                try {
                    Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                    if (dir != null) dir.mkdirs();
                    File photoFile = new File(dir, "camera_" + System.currentTimeMillis() + ".jpg");
                    mCameraOutputUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", photoFile);
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraOutputUri);
                    takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(takePictureIntent, CAMERA_DIRECT_REQUEST_CODE);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        startActivityForResult(intent, CAMERA_DIRECT_REQUEST_CODE);
                    } catch (Exception ex) {
                        Toast.makeText(MainActivity.this, "Camera error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openGallery() { openNativeGallery(); }

        @JavascriptInterface
        public void openNativeGallery() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    startActivityForResult(intent, GALLERY_DIRECT_REQUEST_CODE);
                } catch (Exception e) {
                    try {
                        Intent pickPhoto = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                        startActivityForResult(pickPhoto, GALLERY_DIRECT_REQUEST_CODE);
                    } catch (Exception ex) {
                        Toast.makeText(MainActivity.this, "Gallery error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void saveImageToGallery(final String base64Data, final String fileName) {
            saveImageToGalleryDirect(base64Data, fileName, null);
        }

        @JavascriptInterface
        public void saveImageToGallery(final String base64Data, final String fileName, final String callbackId) {
            saveImageToGalleryDirect(base64Data, fileName, callbackId);
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
                        startActivity(Intent.createChooser(shareIntent, "Share Stamped Photo via"));
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
}
