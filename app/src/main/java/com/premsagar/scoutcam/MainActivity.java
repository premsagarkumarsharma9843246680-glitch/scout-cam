package com.premsagar.scoutcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Scout Cam: hosts the camera UI (assets/www/index.html) in a full-screen WebView,
 * gives it the camera, and lets it save photos straight into the Gallery.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_PICK = 3;

    private WebView web;
    private PermissionRequest pendingWebPermission;
    private ValueCallback<Uri[]> pendingFileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        // Serve the bundled page from a secure https origin so the live camera is allowed.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (hasCamera()) {
                        request.grant(request.getResources());
                    } else {
                        pendingWebPermission = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "Choose a photo"), REQ_PICK);
                    return true;
                } catch (Exception e) {
                    pendingFileCallback = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "ScoutNative");

        // Ask for the camera up front so the viewfinder starts straight away.
        if (!hasCamera()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        web.loadUrl(START_URL);
    }

    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA) {
            if (pendingWebPermission != null) {
                if (granted) pendingWebPermission.grant(pendingWebPermission.getResources());
                else pendingWebPermission.deny();
                pendingWebPermission = null;
            } else if (granted && web != null) {
                web.evaluateJavascript("window.scoutRetryCamera && scoutRetryCamera()", null);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && pendingFileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            pendingFileCallback.onReceiveValue(result);
            pendingFileCallback = null;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.scoutBack && scoutBack()) ? '1' : '0'", value -> {
            if (!"\"1\"".equals(value)) finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Functions the page can call as window.ScoutNative.*. */
    private class Bridge {

        /** Saves a JPEG (base64) into Pictures/Scout Cam. Returns "ok", "perm" or an error message. */
        @JavascriptInterface
        public String saveImage(String base64, String fileName) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    return saveWithMediaStore(bytes, fileName);
                } else {
                    return saveLegacy(bytes, fileName);
                }
            } catch (Throwable t) {
                return String.valueOf(t.getMessage());
            }
        }

        @JavascriptInterface
        public void haptic() {
            runOnUiThread(() -> {
                if (web != null) web.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            });
        }

        @JavascriptInterface
        public boolean isApp() {
            return true;
        }
    }

    private String saveWithMediaStore(byte[] bytes, String fileName) throws Exception {
        ContentResolver resolver = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Scout Cam");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return "Gallery is not available";
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) return "Could not open file";
            out.write(bytes);
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, v, null, null);
        return "ok";
    }

    @SuppressWarnings("deprecation")
    private String saveLegacy(byte[] bytes, String fileName) throws Exception {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
            return "perm";
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Scout Cam");
        if (!dir.exists() && !dir.mkdirs()) return "Could not create folder";
        File f = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        return "ok";
    }
}
