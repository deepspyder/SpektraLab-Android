package com.spektralab.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;

/** Independent test-provider process, platform Java only. IDs/names deliberately collide. */
public final class MigrationFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    private File source() { return new File(getContext().getFilesDir(), "migration-fixtures/sample.png"); }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.equals("seed") || extras == null) throw new UnsupportedOperationException();
        byte[] bytes = extras.getByteArray("png");
        if (bytes == null || bytes.length > 1024 * 1024) throw new IllegalArgumentException();
        File target = source();
        target.getParentFile().mkdirs();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(target)) {
            stream.write(bytes);
            stream.getFD().sync();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        return new Bundle();
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection != null ? projection : new String[]{"_id", "_display_name", "_size"};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals("_id")) values[i] = 42L;
            else if (columns[i].equals("_display_name")) values[i] = "same-name.png";
            else if (columns[i].equals("_size")) values[i] = source().length();
        }
        cursor.addRow(values);
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri.getPath().contains("denied")) throw new SecurityException("Audit permission denied");
        if (uri.getPath().contains("missing")) throw new FileNotFoundException("Audit offline source");
        if (!mode.equals("r")) throw new FileNotFoundException("Read only fixture");
        if (!uri.getPath().contains("pipe")) return ParcelFileDescriptor.open(source(), ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (FileInputStream input = new FileInputStream(source());
                     OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                } catch (Exception ignored) { }
            }, "migration-fixture-pipe").start();
            return descriptors[0];
        } catch (java.io.IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
