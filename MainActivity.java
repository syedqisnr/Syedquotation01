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
import com.getcapacitor.BridgeActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends BridgeActivity {
    private static final int SPEECH_REQUEST_CODE = 3003;
    private static final int FILE_CHOOSER_REQUEST_CODE = 4004;
    private static final int CAMERA_DIRECT_REQUEST_CODE = 5005;
    private static final int GALLERY_DIRECT_REQUEST_CODE = 6006;


    @Override
    public void onBackPressed() {
        try {
            WebView webView = getBridge().getWebView();
            webView.evaluateJavascript("(function(){ if(window.optimalSmartBack){ return window.optimalSmartBack(); } return false; })()", value -> {
                if (value == null || "false".equals(value) || "null".equals(value)) {
                    MainActivity.super.onBackPressed();
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

    public class AndroidBridge {
        @JavascriptInterface
        public void openCamera() { openNativeCamera(); }

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
