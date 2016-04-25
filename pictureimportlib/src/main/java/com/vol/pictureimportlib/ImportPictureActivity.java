package com.vol.pictureimportlib;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;

import com.squareup.picasso.MemoryPolicy;
import com.squareup.picasso.NetworkPolicy;
import com.squareup.picasso.Picasso;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Activity to import a picture from anywhere supported
 * <p>
 * Created by Stéphane Konstantaropoulos <stephane@voyageonline.co.uk>
 */
public class ImportPictureActivity extends Activity {

    public static final String ARG_WIDTH = "arg_width";
    public static final String ARG_HEIGHT = "arg_height";
    public static final String ARG_CROP = "arg_crop";
    public static final String RES_IMAGE_FILE = "res_image_file";
    private static final String TAG = ImportPictureActivity.class.getSimpleName();
    private static final int REQ_FROM_GALLERY = 1;
    private static final int REQ_CROP = 2;

    private Uri mImageUri;
    private ProgressDialog progressDialog;

    private static String getTempFilename(Context context) throws IOException {
        File outputDir = context.getCacheDir();
        File outputFile = File.createTempFile("image", "tmp", outputDir);
        return outputFile.getAbsolutePath();
    }

    private static File getFromMediaUriPfd(Context context, ContentResolver resolver, Uri uri) {
        if (uri == null) {
            return null;
        }

        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
            FileDescriptor fd = pfd.getFileDescriptor();
            input = new FileInputStream(fd);

            String tempFilename = getTempFilename(context);
            output = new FileOutputStream(tempFilename);

            int read;
            byte[] bytes = new byte[4096];
            while ((read = input.read(bytes)) != -1) {
                output.write(bytes, 0, read);
            }
            return new File(tempFilename);
        } catch (IOException ignored) {
            // Nothing we can do
        } finally {
            closeSilently(input);
            closeSilently(output);
        }
        return null;
    }

    private static void closeSilently(@Nullable Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable t) {
            // Do nothing
        }
    }

    private static int getOrientationForMedia(Context context, Uri contentUri) {
        int res = 0;

        try {
            String[] proj = {MediaStore.Images.Media.ORIENTATION};
            Cursor cursor = context.getContentResolver().query(contentUri, proj, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int column_index = cursor.getColumnIndex(proj[0]);
                    res = cursor.getInt(column_index);
                }
                cursor.close();
            }

        } catch (Exception e) {
            Log.e(TAG, "error getting orientation for: " + contentUri, e);
        }

        return res;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private static int getOrientationForDocuments(Context context, Uri contentUri) {
        String wholeID = DocumentsContract.getDocumentId(contentUri);

        // Split at colon, use second item in the array
        String id = wholeID.split(":")[1];
        String[] column = {MediaStore.Images.Media.ORIENTATION};

        // where id is equal to
        String sel = MediaStore.Images.Media._ID + "=?";
        Cursor cursor = context.getContentResolver().
                query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        column, sel, new String[]{id}, null);

        int orientation = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(column[0]);
                orientation = cursor.getInt(columnIndex);
            }
            cursor.close();
        }

        return orientation;
    }

    private static Bitmap scaleCenterCrop(Bitmap source, int newHeight, int newWidth) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        // Compute the scaling factors to fit the new height and width, respectively.
        // To cover the final image, the final scaling will be the bigger
        // of these two.
        float xScale = (float) newWidth / sourceWidth;
        float yScale = (float) newHeight / sourceHeight;
        float scale = Math.max(xScale, yScale);

        // Now get the size of the source bitmap when scaled
        float scaledWidth = scale * sourceWidth;
        float scaledHeight = scale * sourceHeight;

        // Let's find out the upper left coordinates if the scaled bitmap
        // should be centered in the new size give by the parameters
        float left = (newWidth - scaledWidth) / 2;
        float top = (newHeight - scaledHeight) / 2;

        // The target rectangle for the new, scaled version of the source bitmap will now
        // be
        RectF targetRect = new RectF(left, top, left + scaledWidth, top + scaledHeight);

        // Finally, we create a new bitmap of the specified size and draw our new,
        // scaled bitmap onto it.
        Bitmap dest = Bitmap.createBitmap(newWidth, newHeight, source.getConfig());
        Canvas canvas = new Canvas(dest);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setAntiAlias(true);
        p.setFilterBitmap(true);
        canvas.drawBitmap(source, null, targetRect, p);

        return dest;
    }

    private boolean isCropAvailable() {
        Intent intent = new Intent("com.android.camera.action.CROP");
        if (intent.resolveActivity(getPackageManager()) != null) {
            return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File file1 = new File(getCacheDir() + "/pictureimportlib/");
        if (!file1.exists()) {
            file1.mkdirs();
        }

        File photo;
        try {
            photo = this.createTemporaryFile("image", ".jpg");
            photo.delete();
        } catch (Exception e) {
            Log.d(TAG, "Can't create file to take picture!");
            finish();
            return;
        }

        mImageUri = FileProvider.getUriForFile(this, getString(R.string.filesAuthority), photo);

        Intent pickIntent = new Intent();
        pickIntent.setType("image/*");
        pickIntent.setAction(Intent.ACTION_GET_CONTENT);
        Intent takePhotoIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        List<Intent> photoIntents = new ArrayList<>();
        PackageManager pm = getApplicationContext().getPackageManager();
        for (ResolveInfo ri : pm.queryIntentActivities(takePhotoIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
            Intent intent = new Intent();
            intent.setClassName(ri.activityInfo.applicationInfo.packageName, ri.activityInfo.name);
            intent.setAction(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantUriPermission(ri.activityInfo.applicationInfo.packageName, mImageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, mImageUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                intent.setClipData(ClipData.newRawUri(null, mImageUri));
            }
            photoIntents.add(intent);
        }

        if ("Huawei".equals(Build.BRAND)) {
            grantUriPermission("com.huawei.android.internal.app", mImageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        Intent chooserIntent = Intent.createChooser(pickIntent, getString(R.string.select_or_take_picture));
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, photoIntents.toArray(new Intent[photoIntents.size()]));
        startActivityForResult(chooserIntent, REQ_FROM_GALLERY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQ_FROM_GALLERY) {
                if (progressDialog == null) {
                    progressDialog = new ProgressDialog(this);
                    progressDialog.setMessage(getString(R.string.please_wait));
                    progressDialog.show();
                }

                new Thread() {
                    @Override
                    public void run() {
                        super.run();
                        try {
                            Bitmap bitmap = loadBitmap(data);

                            if (bitmap == null) {
                                Log.e(TAG, "could not load bitmap, bailing out");
                                finish();
                                return;
                            }

                            if (isCropAvailable() && getIntent().getBooleanExtra(ARG_CROP, false) && getIntent().hasExtra(ARG_HEIGHT) && getIntent().hasExtra(ARG_WIDTH)) {
                                startCropIntent(bitmap);
                                return;
                            } else {
                                processBitmapAndReturn(bitmap);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception: ", e);
                            setResult(Activity.RESULT_CANCELED);
                            finish();
                        } finally {
                            progressDialog.dismiss();
                        }
                    }
                }.start();
            } else if (requestCode == REQ_CROP) {
                Log.d(TAG, "result from crop");

                new Thread() {
                    @Override
                    public void run() {
                        super.run();
                        try {
                            Bitmap bitmap = loadBitmap(data);

                            if (bitmap == null) {
                                Log.e(TAG, "could not load bitmap, bailing out");
                                finish();
                                return;
                            }

                            processBitmapAndReturn(bitmap);

                        } catch (IOException e) {
                            Log.e(TAG, "IOException: ", e);
                            setResult(Activity.RESULT_CANCELED);
                            finish();
                        } catch (OutOfMemoryError e) {
                            Log.e(TAG, "OutOfMemoryError: ", e);
                            setResult(Activity.RESULT_CANCELED);
                            finish();
                        } finally {
                            progressDialog.dismiss();
                        }
                    }
                }.start();
            } else {
                finish();
            }
        } else {
            finish();
        }
    }

    private void processBitmapAndReturn(Bitmap bitmap) {
        Bitmap scaled;
        if (getIntent().hasExtra(ARG_HEIGHT) && getIntent().hasExtra(ARG_WIDTH)) {
            scaled = scaleCenterCrop(bitmap, getIntent().getIntExtra(ARG_HEIGHT, -1), getIntent().getIntExtra(ARG_WIDTH, -1));
            bitmap.recycle();
        } else {
            scaled = bitmap;
        }

        File photoFile = new File(getCacheDir() + "/pictureimportlib/", "pictureimportlib" + System.currentTimeMillis() + ".jpg");
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(photoFile);
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream);
            scaled.recycle();
            stream.flush();
            Intent intent = new Intent();
            intent.putExtra(RES_IMAGE_FILE, photoFile);
            setResult(RESULT_OK, intent);
            finish();
            Log.d(TAG, "Image size on disk in kb: " + (photoFile.length() / 1024));
        } catch (Exception e) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            Log.e(TAG, "Exception: ", e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    Log.e(TAG, "IOException: ", e);
                }
            }
        }
    }

    private Bitmap loadBitmap(Intent data) throws IOException {
        Bitmap bitmap;
        try {
            if (data == null) {
                File file = new File(getCacheDir() + "/tmp/" + mImageUri.getLastPathSegment());
                bitmap = Picasso.with(ImportPictureActivity.this).load(file).memoryPolicy(MemoryPolicy.NO_CACHE, MemoryPolicy.NO_STORE)
                        .networkPolicy(NetworkPolicy.NO_CACHE).get();
                file.delete();
            }
            else {
                Uri uri = data.getData();
                InputStream inputStream = getContentResolver().openInputStream(uri);
                bitmap = BitmapFactory.decodeStream(inputStream);
            }
        } catch (IOException e) {
            Log.e(TAG, "io exception", e);
            if (data == null) {
                Log.e(TAG, "data is null, bailing out");
                return null;
            }

            bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), data.getData());
            final int orientation;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(ImportPictureActivity.this, data.getData()) &&
                    (ContextCompat.checkSelfPermission(ImportPictureActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)) {
                orientation = getOrientationForDocuments(ImportPictureActivity.this, data.getData());
            } else {
                orientation = getOrientationForMedia(ImportPictureActivity.this, data.getData());
            }

            //for phones that dont give us orientation data
            int tempOrientation = 0;
            if (orientation == ExifInterface.ORIENTATION_UNDEFINED) {
                File imageFile = getFromMediaUriPfd(ImportPictureActivity.this, ImportPictureActivity.this.getContentResolver(), data.getData());
                ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
                switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        tempOrientation = 90;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        tempOrientation = 180;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        tempOrientation = 270;
                        break;
                    default:
                        tempOrientation = 0;
                        break;
                }
            }

            Matrix matrix = new Matrix();
            if (orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                matrix.preRotate(orientation);
            } else if (tempOrientation != ExifInterface.ORIENTATION_UNDEFINED) {
                matrix.preRotate(tempOrientation);
            }

            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (bitmap != rotatedBitmap) {
                bitmap.recycle();
                bitmap = rotatedBitmap;
            }
        }

        return bitmap;
    }

    private void startCropIntent(Bitmap bitmap) throws Exception {
        File photoFile = createTemporaryFile("image", ".jpg");
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(photoFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream);
            bitmap.recycle();
            stream.flush();

            mImageUri = FileProvider.getUriForFile(ImportPictureActivity.this, getString(R.string.filesAuthority), photoFile);

            Intent intent = new Intent("com.android.camera.action.CROP");
            intent.setDataAndType(mImageUri, "image/*");

            PackageManager pm = getApplicationContext().getPackageManager();

            String matchPackage = null;
            String matchClass = null;

            for (ResolveInfo ri : pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                Log.d(TAG, "match: " + ri.activityInfo.applicationInfo.packageName + " " + ri.activityInfo.name);
                if ("com.google.android.apps.plus".equals(ri.activityInfo.applicationInfo.packageName)) {
                    matchPackage = ri.activityInfo.applicationInfo.packageName;
                    matchClass = ri.activityInfo.name;
                }
            }

            for (ResolveInfo ri : pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                intent = new Intent("com.android.camera.action.CROP");
                intent.setDataAndType(mImageUri, "image/*");
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                int width = getIntent().getIntExtra(ARG_WIDTH, -1);
                int height = getIntent().getIntExtra(ARG_HEIGHT, -1);

                intent.putExtra("aspectX", width);
                intent.putExtra("aspectY", height);
                intent.putExtra("outputX", width);
                intent.putExtra("outputY", height);
                intent.putExtra("scale", true);

                if (matchPackage != null) {
                    intent.setClassName(matchPackage, matchClass);
                }
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                grantUriPermission(ri.activityInfo.applicationInfo.packageName, mImageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, mImageUri);
                startActivityForResult(intent, REQ_CROP);
                break;
            }

        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    Log.e(TAG, "IOException: ", e);
                }
            }
        }
    }

    @Override
    public void finish() {
        super.finish();
    }

    private File createTemporaryFile(String part, String ext) throws Exception {
        File tempDir = getCacheDir();
        tempDir = new File(tempDir.getAbsolutePath() + "/tmp/");
        if (!tempDir.exists()) {
            tempDir.mkdir();
        }
        return File.createTempFile(part, ext, tempDir);
    }
}
