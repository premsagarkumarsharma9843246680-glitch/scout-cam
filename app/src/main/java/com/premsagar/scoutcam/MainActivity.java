package com.premsagar.scoutcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
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
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.webkit.WebViewAssetLoader;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Scout Cam.
 * The real camera (CameraX) draws the viewfinder and takes full-resolution photos.
 * The camera UI (assets/www/index.html) runs in a transparent WebView on top of it.
 */
public class MainActivity extends ComponentActivity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_PICK = 3;

    private WebView web;
    private PreviewView previewView;
    private PermissionRequest pendingWebPermission;
    private ValueCallback<Uri[]> pendingFileCallback;

    // camera
    private final ExecutorService camExec = Executors.newSingleThreadExecutor();
    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private boolean cameraWanted = false;
    private float zoomRatio = 1f;
    private int lastPreviewW = 0, lastPreviewH = 0;
    private int bindRetries = 0;
    private volatile byte[] lastFrame;
    private volatile long lastFrameAt = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(1, 1);
        plp.gravity = Gravity.TOP | Gravity.START;
        root.addView(previewView, plp);

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

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
                    if (hasCameraPermission()) {
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
        web.loadUrl(START_URL);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void js(final String code) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    // ---------------------------------------------------------------- camera

    private void initCamera() {
        if (provider != null) {
            bindCamera();
            return;
        }
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                bindCamera();
            } catch (Exception e) {
                js("window.scoutCameraError && scoutCameraError(" + JSONObject.quote("Camera could not start: " + e.getMessage()) + ")");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private int displayRotation() {
        Display d = previewView.getDisplay();
        return d != null ? d.getRotation() : Surface.ROTATION_0;
    }

    private void bindCamera() {
        if (provider == null || isFinishing()) return;
        ViewPort viewPort = (previewView.getWidth() > 2 && previewView.getHeight() > 2)
                ? previewView.getViewPort(displayRotation()) : null;
        if (viewPort == null) {
            if (bindRetries++ < 40) previewView.postDelayed(this::bindCamera, 50);
            return;
        }
        bindRetries = 0;

        ResolutionSelector previewSel = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build();
        Preview preview = new Preview.Builder().setResolutionSelector(previewSel).build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ResolutionSelector photoSel = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build();
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(photoSel)
                .setTargetRotation(displayRotation())
                .build();

        ResolutionSelector analysisSel = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(analysisSel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(camExec, this::analyze);

        try {
            provider.unbindAll();
            UseCaseGroup group = new UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .addUseCase(analysis)
                    .build();
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group);
        } catch (Exception first) {
            // Some phones can't run all three at once: keep preview + photo.
            try {
                provider.unbindAll();
                UseCaseGroup group = new UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(imageCapture)
                        .build();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group);
            } catch (Exception e) {
                js("window.scoutCameraError && scoutCameraError(" + JSONObject.quote("Camera could not start: " + e.getMessage()) + ")");
                return;
            }
        }

        camera.getCameraControl().setZoomRatio(zoomRatio);
        float max = 8f;
        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
        if (zs != null) max = zs.getMaxZoomRatio();
        js("window.scoutCameraReady && scoutCameraReady(" + max + ")");
    }

    /** Keeps a small, upright copy of the latest frame for the scene scanner. */
    private void analyze(ImageProxy image) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAt < 250) return;
            Bitmap full = image.toBitmap();
            Rect crop = new Rect(image.getCropRect());
            if (!crop.intersect(0, 0, full.getWidth(), full.getHeight()) || crop.width() < 2 || crop.height() < 2) {
                crop = new Rect(0, 0, full.getWidth(), full.getHeight());
            }
            Matrix m = new Matrix();
            float sc = 640f / Math.max(crop.width(), crop.height());
            if (sc < 1f) m.postScale(sc, sc);
            m.postRotate(image.getImageInfo().getRotationDegrees());
            Bitmap out = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height(), m, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 72, bos);
            lastFrame = bos.toByteArray();
            lastFrameAt = now;
        } catch (Throwable ignored) {
        } finally {
            image.close();
        }
    }

    private void takePicture(String fileName) {
        if (imageCapture == null) {
            js("scoutSaved(false,'',0,0,'','camera not ready')");
            return;
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            js("scoutSaved(false,'',0,0,'','allow storage access, then take the photo again')");
            return;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Scout Cam");
        }
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v).build();
        imageCapture.setTargetRotation(displayRotation());
        imageCapture.takePicture(opts, camExec, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults results) {
                Uri uri = results.getSavedUri();
                if (uri == null) {
                    js("scoutSaved(false,'',0,0,'','photo was not saved')");
                    return;
                }
                int[] size = imageSize(uri);
                String thumb = thumbnail(uri, size);
                js("scoutSaved(true," + JSONObject.quote(uri.toString()) + "," + size[0] + "," + size[1] + ",'" + thumb + "','')");
            }

            @Override
            public void onError(ImageCaptureException e) {
                js("scoutSaved(false,'',0,0,''," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
            }
        });
    }

    /** Width, height (as displayed) and rotation of a saved photo. */
    private int[] imageSize(Uri uri) {
        int w = 0, h = 0, rot = 0;
        ContentResolver r = getContentResolver();
        try (InputStream in = r.openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            w = o.outWidth;
            h = o.outHeight;
        } catch (Exception ignored) {
        }
        try (InputStream in = r.openInputStream(uri)) {
            if (in != null) rot = new ExifInterface(in).getRotationDegrees();
        } catch (Exception ignored) {
        }
        if (rot == 90 || rot == 270) return new int[]{h, w, rot};
        return new int[]{w, h, rot};
    }

    private String thumbnail(Uri uri, int[] size) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            int sample = 1;
            int longSide = Math.max(size[0], size[1]);
            while (longSide / (sample * 2) >= 240) sample *= 2;
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) return "";
            if (size[2] != 0) {
                Matrix m = new Matrix();
                m.postRotate(size[2]);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 75, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- activity

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA) {
            if (pendingWebPermission != null) {
                if (granted) pendingWebPermission.grant(pendingWebPermission.getResources());
                else pendingWebPermission.deny();
                pendingWebPermission = null;
            }
            if (cameraWanted) {
                if (granted) initCamera();
                else js("window.scoutCameraError && scoutCameraError('Scout Cam needs the camera. Tap below, or allow it in Settings > Apps > Scout Cam > Permissions.')");
            }
        }
    }

    @SuppressWarnings("deprecation")
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
        if (web == null) {
            super.onBackPressed();
            return;
        }
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
        if (provider != null) provider.unbindAll();
        camExec.shutdown();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- bridge

    /** Functions the page can call as window.ScoutNative.*. */
    private class Bridge {

        @JavascriptInterface
        public void startCamera() {
            runOnUiThread(() -> {
                cameraWanted = true;
                if (hasCameraPermission()) initCamera();
                else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            });
        }

        /** Places the real camera preview exactly under the page's viewfinder (CSS pixels). */
        @JavascriptInterface
        public void setPreviewRect(final float x, final float y, final float w, final float h) {
            runOnUiThread(() -> {
                float d = getResources().getDisplayMetrics().density;
                int pw = Math.max(2, Math.round(w * d));
                int ph = Math.max(2, Math.round(h * d));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pw, ph);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = Math.round(x * d);
                lp.topMargin = Math.round(y * d);
                previewView.setLayoutParams(lp);
                boolean changed = pw != lastPreviewW || ph != lastPreviewH;
                lastPreviewW = pw;
                lastPreviewH = ph;
                if (changed && provider != null) previewView.postDelayed(MainActivity.this::bindCamera, 60);
            });
        }

        @JavascriptInterface
        public void setZoom(float ratio) {
            zoomRatio = ratio;
            Camera c = camera;
            if (c == null) return;
            ZoomState zs = c.getCameraInfo().getZoomState().getValue();
            float r = ratio;
            if (zs != null) r = Math.max(zs.getMinZoomRatio(), Math.min(zs.getMaxZoomRatio(), ratio));
            c.getCameraControl().setZoomRatio(r);
        }

        @JavascriptInterface
        public void focus(final float x, final float y) {
            runOnUiThread(() -> {
                if (camera == null) return;
                MeteringPoint p = previewView.getMeteringPointFactory()
                        .createPoint(x * previewView.getWidth(), y * previewView.getHeight());
                FocusMeteringAction a = new FocusMeteringAction.Builder(p,
                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(4, TimeUnit.SECONDS)
                        .build();
                camera.getCameraControl().startFocusAndMetering(a);
            });
        }

        /** Latest small preview frame (base64 JPEG), or "" if none yet. */
        @JavascriptInterface
        public String grabFrame() {
            byte[] f = lastFrame;
            return f == null ? "" : Base64.encodeToString(f, Base64.NO_WRAP);
        }

        /** Takes a full-resolution photo into Pictures/Scout Cam; answers via scoutSaved(...). */
        @JavascriptInterface
        public void takePicture(final String fileName) {
            runOnUiThread(() -> MainActivity.this.takePicture(fileName));
        }

        /** Full saved photo as base64 JPEG (for the editor). */
        @JavascriptInterface
        public String readImage(String uriString) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(uriString))) {
                if (in == null) return "";
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        /** Saves an edited JPEG (base64) into Pictures/Scout Cam. Returns "ok", "perm" or an error. */
        @JavascriptInterface
        public String saveImage(String base64, String fileName) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                return Build.VERSION.SDK_INT >= 29 ? saveWithMediaStore(bytes, fileName) : saveLegacy(bytes, fileName);
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
