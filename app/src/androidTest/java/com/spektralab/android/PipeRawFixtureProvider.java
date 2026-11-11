package com.spektralab.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Test APK process has no app/Kotlin runtime: this provider uses only platform Java APIs. */
public final class PipeRawFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/x-adobe-dng"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/audit.dng".equals(uri.getPath()) || !"r".equals(mode)) {
            throw new FileNotFoundException("Unknown fixture");
        }
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(new Runnable() {
                @Override public void run() {
                    try (OutputStream destination = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                         InputStream source = getContext().getAssets().open("audit.dng")) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = source.read(buffer)) != -1) destination.write(buffer, 0, count);
                    } catch (IOException ignored) {
                        // Seekability probing closes its unread first pipe. Broken pipe is expected.
                    }
                }
            }, "raw-pipe-fixture").start();
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
