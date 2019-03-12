/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import static com.android.providers.downloads.MediaStoreDownloadsHelper.getDocIdForMediaStoreDownload;
import static com.android.providers.downloads.MediaStoreDownloadsHelper.getMediaStoreIdString;
import static com.android.providers.downloads.MediaStoreDownloadsHelper.getMediaStoreUri;
import static com.android.providers.downloads.MediaStoreDownloadsHelper.isMediaStoreDownload;
import static com.android.providers.downloads.MediaStoreDownloadsHelper.isMediaStoreDownloadDir;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.sqlite.SQLiteQueryBuilder;
import android.media.MediaFile;
import android.mtp.MtpConstants;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Path;
import android.provider.DocumentsContract.Root;
import android.provider.Downloads;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongArray;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.FileSystemProvider;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Presents files located in {@link Environment#DIRECTORY_DOWNLOADS} and contents from
 * {@link DownloadManager}. {@link DownloadManager} contents include active downloads and completed
 * downloads added by other applications using
 * {@link DownloadManager#addCompletedDownload(String, String, boolean, String, String, long, boolean, boolean, Uri, Uri)}
 * .
 */
public class DownloadStorageProvider extends FileSystemProvider {
    private static final String TAG = "DownloadStorageProvider";
    private static final boolean DEBUG = false;

    private static final String AUTHORITY = Constants.STORAGE_AUTHORITY;
    private static final String DOC_ID_ROOT = Constants.STORAGE_ROOT_ID;

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_QUERY_ARGS
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SUMMARY, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
    };

    private DownloadManager mDm;

    private static final String[] DOWNLOADS_PROJECTION
            = new String[DownloadManager.UNDERLYING_COLUMNS.length + 1];
    static {
        System.arraycopy(DownloadManager.UNDERLYING_COLUMNS, 0,
                DOWNLOADS_PROJECTION, 0, DownloadManager.UNDERLYING_COLUMNS.length);
        DOWNLOADS_PROJECTION[DOWNLOADS_PROJECTION.length - 1]
                = Downloads.Impl.COLUMN_MEDIASTORE_URI;
    }

    private static final int NO_LIMIT = -1;

    @Override
    public boolean onCreate() {
        super.onCreate(DEFAULT_DOCUMENT_PROJECTION);
        mDm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        mDm.setAccessAllDownloads(true);
        mDm.setAccessFilename(true);

        return true;
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    private void copyNotificationUri(MatrixCursor result, Cursor cursor) {
        result.setNotificationUri(getContext().getContentResolver(), cursor.getNotificationUri());
    }

    /**
     * Called by {@link DownloadProvider} when deleting a row in the {@link DownloadManager}
     * database.
     */
    static void onDownloadProviderDelete(Context context, long id) {
        final Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, Long.toString(id));
        context.revokeUriPermission(uri, ~0);
    }

    static void onMediaProviderDownloadsDelete(Context context, long[] ids, String[] mimeTypes) {
        for (int i = 0; i < ids.length; ++i) {
            final boolean isDir = mimeTypes[i] == Document.MIME_TYPE_DIR;
            final Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY,
                    MediaStoreDownloadsHelper.getDocIdForMediaStoreDownload(ids[i], isDir));
            context.revokeUriPermission(uri, ~0);
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        // It's possible that the folder does not exist on disk, so we will create the folder if
        // that is the case. If user decides to delete the folder later, then it's OK to fail on
        // subsequent queries.
        getTopLevelDownloadsDirectory().mkdirs();

        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, DOC_ID_ROOT);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_RECENTS
                | Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH
                | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher_download);
        row.add(Root.COLUMN_TITLE, getContext().getString(R.string.root_downloads));
        row.add(Root.COLUMN_DOCUMENT_ID, DOC_ID_ROOT);
        row.add(Root.COLUMN_QUERY_ARGS, SUPPORTED_QUERY_ARGS);
        return result;
    }

    @Override
    public Path findDocumentPath(@Nullable String parentDocId, String docId) throws FileNotFoundException {

        // parentDocId is null if the client is asking for the path to the root of a doc tree.
        // Don't share root information with those who shouldn't know it.
        final String rootId = (parentDocId == null) ? DOC_ID_ROOT : null;

        if (parentDocId == null) {
            parentDocId = DOC_ID_ROOT;
        }

        final File parent = getFileForDocId(parentDocId);

        final File doc = getFileForDocId(docId);

        return new Path(rootId, findDocumentPath(parent, doc));
    }

    /**
     * Calls on {@link FileSystemProvider#createDocument(String, String, String)}, and then creates
     * a new database entry in {@link DownloadManager} if it is not a raw file and not a folder.
     */
    @Override
    public String createDocument(String parentDocId, String mimeType, String displayName)
            throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            String newDocumentId = super.createDocument(parentDocId, mimeType, displayName);
            if (!Document.MIME_TYPE_DIR.equals(mimeType)
                    && !RawDocumentsHelper.isRawDocId(parentDocId)) {
                File newFile = getFileForDocId(newDocumentId);
                newDocumentId = Long.toString(mDm.addCompletedDownload(
                        newFile.getName(), newFile.getName(), true, mimeType,
                        newFile.getAbsolutePath(), 0L,
                        false, true));
            }
            return newDocumentId;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void deleteDocument(String docId) throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                super.deleteDocument(docId);
                return;
            }

            int count;
            if (isMediaStoreDownload(docId)) {
                count = getContext().getContentResolver().delete(
                        getMediaStoreUri(docId), null, null);
            } else {
                count = mDm.remove(Long.parseLong(docId));
            }

            if (count != 1) {
                throw new IllegalStateException("Failed to delete " + docId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String renameDocument(String docId, String displayName)
            throws FileNotFoundException {
        final long token = Binder.clearCallingIdentity();

        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                return super.renameDocument(docId, displayName);
            }

            displayName = FileUtils.buildValidFatFilename(displayName);
            if (isMediaStoreDownload(docId)) {
                renameMediaStoreDownload(docId, displayName);
            } else {
                final long id = Long.parseLong(docId);
                if (!mDm.rename(getContext(), id, displayName)) {
                    throw new IllegalStateException(
                            "Failed to rename to " + displayName + " in downloadsManager");
                }
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Cursor queryDocument(String docId, String[] projection) throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                return super.queryDocument(docId, projection);
            }

            final DownloadsCursor result = new DownloadsCursor(projection,
                    getContext().getContentResolver());

            if (DOC_ID_ROOT.equals(docId)) {
                includeDefaultDocument(result);
            } else if (isMediaStoreDownload(docId)) {
                cursor = getContext().getContentResolver().query(getMediaStoreUri(docId),
                        null, null, null);
                copyNotificationUri(result, cursor);
                if (cursor.moveToFirst()) {
                    includeDownloadFromMediaStore(result, cursor, null /* filePaths */);
                }
            } else {
                cursor = mDm.query(new Query().setFilterById(Long.parseLong(docId)),
                        DOWNLOADS_PROJECTION);
                copyNotificationUri(result, cursor);
                if (cursor.moveToFirst()) {
                    // We don't know if this queryDocument() call is from Downloads (manage)
                    // or Files. Safely assume it's Files.
                    includeDownloadFromCursor(result, cursor, null /* filePaths */,
                            null /* mediaStoreIds */, null /* queryArgs */);
                }
            }
            result.start();
            return result;
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Cursor queryChildDocuments(String parentDocId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        return queryChildDocuments(parentDocId, projection, sortOrder, false);
    }

    @Override
    public Cursor queryChildDocumentsForManage(
            String parentDocId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        return queryChildDocuments(parentDocId, projection, sortOrder, true);
    }

    private Cursor queryChildDocuments(String parentDocId, String[] projection,
            String sortOrder, boolean manage) throws FileNotFoundException {

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            if (RawDocumentsHelper.isRawDocId(parentDocId)) {
                return super.queryChildDocuments(parentDocId, projection, sortOrder);
            }

            final DownloadsCursor result = new DownloadsCursor(projection,
                    getContext().getContentResolver());
            final ArrayList<Uri> notificationUris = new ArrayList<>();
            if (isMediaStoreDownloadDir(parentDocId)) {
                includeDownloadsFromMediaStore(result, null /* queryArgs */,
                        null /* idsToExclude */, null /* filePaths */, notificationUris,
                        getMediaStoreIdString(parentDocId), NO_LIMIT, manage);
            } else {
                assert (DOC_ID_ROOT.equals(parentDocId));
                if (manage) {
                    cursor = mDm.query(
                            new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true),
                            DOWNLOADS_PROJECTION);
                } else {
                    cursor = mDm.query(
                            new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true)
                                    .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL),
                            DOWNLOADS_PROJECTION);
                }
                final Set<String> filePaths = new HashSet<>();
                final LongArray mediaStoreIds = new LongArray();
                while (cursor.moveToNext()) {
                    includeDownloadFromCursor(result, cursor, filePaths, mediaStoreIds,
                            null /* queryArgs */);
                }
                notificationUris.add(cursor.getNotificationUri());
                includeDownloadsFromMediaStore(result, null /* queryArgs */,
                        mediaStoreIds, filePaths, notificationUris,
                        null /* parentId */, NO_LIMIT, manage);
                includeFilesFromSharedStorage(result, filePaths, null);
            }
            result.setNotificationUris(getContext().getContentResolver(), notificationUris);
            result.start();
            return result;
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection,
            @Nullable Bundle queryArgs, @Nullable CancellationSignal signal)
            throws FileNotFoundException {
        final DownloadsCursor result =
                new DownloadsCursor(projection, getContext().getContentResolver());

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();

        int limit = 12;
        if (queryArgs != null) {
            limit = queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT, -1);

            if (limit < 0) {
                // Use default value, and no QUERY_ARG* is honored.
                limit = 12;
            } else {
                // We are honoring the QUERY_ARG_LIMIT.
                Bundle extras = new Bundle();
                result.setExtras(extras);
                extras.putStringArray(ContentResolver.EXTRA_HONORED_ARGS, new String[]{
                        ContentResolver.QUERY_ARG_LIMIT
                });
            }
        }

        Cursor cursor = null;
        final ArrayList<Uri> notificationUris = new ArrayList<>();
        try {
            cursor = mDm.query(new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true)
                    .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL),
                    DOWNLOADS_PROJECTION);
            final LongArray mediaStoreIds = new LongArray();
            while (cursor.moveToNext() && result.getCount() < limit) {
                final String mimeType = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                final String uri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIAPROVIDER_URI));

                // Skip images and videos that have been inserted into the MediaStore so we
                // don't duplicate them in the recent list. The audio root of
                // MediaDocumentsProvider doesn't support recent, we add it into recent list.
                if (mimeType == null || (MediaFile.isImageMimeType(mimeType)
                        || MediaFile.isVideoMimeType(mimeType)) && !TextUtils.isEmpty(uri)) {
                    continue;
                }
                includeDownloadFromCursor(result, cursor, null /* filePaths */,
                        mediaStoreIds, null /* queryArgs */);
            }
            notificationUris.add(cursor.getNotificationUri());
            includeDownloadsFromMediaStore(result, null /* queryArgs */, mediaStoreIds,
                    null /* filePaths */, notificationUris, null /* parentId */,
                    (limit - result.getCount()), false /* includePending */);
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }

        result.setNotificationUris(getContext().getContentResolver(), notificationUris);
        result.start();
        return result;
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String[] projection, Bundle queryArgs)
            throws FileNotFoundException {

        final DownloadsCursor result =
                new DownloadsCursor(projection, getContext().getContentResolver());
        final ArrayList<Uri> notificationUris = new ArrayList<>();

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            cursor = mDm.query(new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true)
                    .setFilterByString(DocumentsContract.getSearchDocumentsQuery(queryArgs)),
                    DOWNLOADS_PROJECTION);
            final LongArray mediaStoreIds = new LongArray();
            final Set<String> filePaths = new HashSet<>();
            while (cursor.moveToNext()) {
                includeDownloadFromCursor(result, cursor, filePaths, mediaStoreIds, queryArgs);
            }
            notificationUris.add(cursor.getNotificationUri());
            includeDownloadsFromMediaStore(result, queryArgs, mediaStoreIds, filePaths,
                    notificationUris, null /* parentId */, NO_LIMIT, true /* includePending */);

            includeSearchFilesFromSharedStorage(result, projection, filePaths, queryArgs);
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }

        final String[] handledQueryArgs = DocumentsContract.getHandledQueryArguments(queryArgs);
        if (handledQueryArgs.length > 0) {
            final Bundle extras = new Bundle();
            extras.putStringArray(ContentResolver.EXTRA_HONORED_ARGS, handledQueryArgs);
            result.setExtras(extras);
        }

        result.setNotificationUris(getContext().getContentResolver(), notificationUris);
        result.start();
        return result;
    }

    private void includeSearchFilesFromSharedStorage(DownloadsCursor result,
            String[] projection, Set<String> filePaths,
            Bundle queryArgs) throws FileNotFoundException {
        final List<File> downloadsDirs = getDownloadsDirectories();
        result.setIncludedDownloadDirs(downloadsDirs);
        final int size = downloadsDirs.size();
        for (int i = 0; i < size; ++i) {
            final File downloadDir = downloadsDirs.get(i);
            try (Cursor rawFilesCursor = super.querySearchDocuments(downloadDir,
                    projection, filePaths, queryArgs)) {

                final boolean shouldExcludeMedia = queryArgs.getBoolean(
                        DocumentsContract.QUERY_ARG_EXCLUDE_MEDIA, false /* defaultValue */);
                while (rawFilesCursor.moveToNext()) {
                    final String mimeType = rawFilesCursor.getString(
                            rawFilesCursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE));
                    // When the value of shouldExcludeMedia is true, don't add media files into
                    // the result to avoid duplicated files. MediaScanner will scan the files
                    // into MediaStore. If the behavior is changed, we need to add the files back.
                    if (!shouldExcludeMedia || !isMediaMimeType(mimeType)) {
                        String docId = rawFilesCursor.getString(
                                rawFilesCursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID));
                        File rawFile = getFileForDocId(docId);
                        includeFileFromSharedStorage(result, rawFile);
                    }
                }
            }
        }
    }

    @Override
    public String getDocumentType(String docId) throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                return super.getDocumentType(docId);
            }

            final ContentResolver resolver = getContext().getContentResolver();
            final Uri contentUri;
            if (isMediaStoreDownload(docId)) {
                contentUri = getMediaStoreUri(docId);
            } else {
                final long id = Long.parseLong(docId);
                contentUri = mDm.getDownloadUri(id);
            }
            return resolver.getType(contentUri);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                return super.openDocument(docId, mode, signal);
            }

            final ContentResolver resolver = getContext().getContentResolver();
            final Uri contentUri;
            if (isMediaStoreDownload(docId)) {
                contentUri = getMediaStoreUri(docId);
            } else {
                final long id = Long.parseLong(docId);
                contentUri = mDm.getDownloadUri(id);
            }
            return resolver.openFileDescriptor(contentUri, mode, signal);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    protected File getFileForDocId(String docId, boolean visible) throws FileNotFoundException {
        if (RawDocumentsHelper.isRawDocId(docId)) {
            return new File(RawDocumentsHelper.getAbsoluteFilePath(docId));
        }

        if (isMediaStoreDownload(docId)) {
            return getFileForMediaStoreDownload(docId);
        }

        if (DOC_ID_ROOT.equals(docId)) {
            return getTopLevelDownloadsDirectory();
        }

        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        String localFilePath = null;
        try {
            cursor = mDm.query(new Query().setFilterById(Long.parseLong(docId)),
                    DOWNLOADS_PROJECTION);
            if (cursor.moveToFirst()) {
                localFilePath = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));
            }
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }

        if (localFilePath == null) {
            throw new IllegalStateException("File has no filepath. Could not be found.");
        }
        return new File(localFilePath);
    }

    @Override
    protected String getDocIdForFile(File file) throws FileNotFoundException {
        return RawDocumentsHelper.getDocIdForFile(file);
    }

    @Override
    protected Uri buildNotificationUri(String docId) {
        return DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);
    }

    private static boolean isMediaMimeType(String mimeType) {
        return MediaFile.isImageMimeType(mimeType) || MediaFile.isVideoMimeType(mimeType)
                || MediaFile.isAudioMimeType(mimeType);
    }

    private void includeDefaultDocument(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, DOC_ID_ROOT);
        // We have the same display name as our root :)
        row.add(Document.COLUMN_DISPLAY_NAME,
                getContext().getString(R.string.root_downloads));
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        row.add(Document.COLUMN_FLAGS,
                Document.FLAG_DIR_PREFERS_LAST_MODIFIED | Document.FLAG_DIR_SUPPORTS_CREATE);
    }

    /**
     * Adds the entry from the cursor to the result only if the entry is valid. That is,
     * if the file exists in the file system.
     */
    private void includeDownloadFromCursor(MatrixCursor result, Cursor cursor,
            Set<String> filePaths, LongArray mediaStoreIds, Bundle queryArgs) {
        final long id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
        final String docId = String.valueOf(id);

        final String displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
        String summary = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION));
        String mimeType = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
        if (mimeType == null) {
            // Provide fake MIME type so it's openable
            mimeType = "vnd.android.document/file";
        }

        if (queryArgs != null) {
            final boolean shouldExcludeMedia = queryArgs.getBoolean(
                    DocumentsContract.QUERY_ARG_EXCLUDE_MEDIA, false /* defaultValue */);
            if (shouldExcludeMedia) {
                final String uri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIAPROVIDER_URI));

                // Skip media files that have been inserted into the MediaStore so we
                // don't duplicate them in the search list.
                if (isMediaMimeType(mimeType) && !TextUtils.isEmpty(uri)) {
                    return;
                }
            }
        }

        // size could be -1 which indicates that download hasn't started.
        final long size = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

        String localFilePath = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));

        int extraFlags = Document.FLAG_PARTIAL;
        final int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        switch (status) {
            case DownloadManager.STATUS_SUCCESSFUL:
                // Verify that the document still exists in external storage. This is necessary
                // because files can be deleted from the file system without their entry being
                // removed from DownloadsManager.
                if (localFilePath == null || !new File(localFilePath).exists()) {
                    return;
                }
                extraFlags = Document.FLAG_SUPPORTS_RENAME;  // only successful is non-partial
                break;
            case DownloadManager.STATUS_PAUSED:
                summary = getContext().getString(R.string.download_queued);
                break;
            case DownloadManager.STATUS_PENDING:
                summary = getContext().getString(R.string.download_queued);
                break;
            case DownloadManager.STATUS_RUNNING:
                final long progress = cursor.getLong(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                if (size > 0) {
                    String percent =
                            NumberFormat.getPercentInstance().format((double) progress / size);
                    summary = getContext().getString(R.string.download_running_percent, percent);
                } else {
                    summary = getContext().getString(R.string.download_running);
                }
                break;
            case DownloadManager.STATUS_FAILED:
            default:
                summary = getContext().getString(R.string.download_error);
                break;
        }

        final long lastModified = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));

        if (!DocumentsContract.matchSearchQueryArguments(queryArgs, displayName, mimeType,
                lastModified, size)) {
            return;
        }

        includeDownload(result, docId, displayName, summary, size, mimeType,
                lastModified, extraFlags, status == DownloadManager.STATUS_RUNNING);
        if (mediaStoreIds != null) {
            final String mediaStoreUri = cursor.getString(
                    cursor.getColumnIndex(Downloads.Impl.COLUMN_MEDIASTORE_URI));
            if (mediaStoreUri != null) {
                mediaStoreIds.add(ContentUris.parseId(Uri.parse(mediaStoreUri)));
            }
        }
        if (filePaths != null) {
            filePaths.add(localFilePath);
        }
    }

    private void includeDownload(MatrixCursor result,
            String docId, String displayName, String summary, long size,
            String mimeType, long lastModifiedMs, int extraFlags, boolean isPending) {

        int flags = Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE | extraFlags;
        if (mimeType.startsWith("image/")) {
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        if (typeSupportsMetadata(mimeType)) {
            flags |= Document.FLAG_SUPPORTS_METADATA;
        }

        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SUMMARY, summary);
        row.add(Document.COLUMN_SIZE, size == -1 ? null : size);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_FLAGS, flags);
        // Incomplete downloads get a null timestamp.  This prevents thrashy UI when a bunch of
        // active downloads get sorted by mod time.
        if (!isPending) {
            row.add(Document.COLUMN_LAST_MODIFIED, lastModifiedMs);
        }
    }

    /**
     * Takes all the top-level files from the Downloads directory and adds them to the result.
     *
     * @param result cursor containing all documents to be returned by queryChildDocuments or
     *            queryChildDocumentsForManage.
     * @param downloadedFilePaths The absolute file paths of all the files in the result Cursor.
     * @param searchString query used to filter out unwanted results.
     */
    private void includeFilesFromSharedStorage(DownloadsCursor result,
            Set<String> downloadedFilePaths, @Nullable String searchString)
            throws FileNotFoundException {
        final List<File> downloadsDirs = getDownloadsDirectories();
        result.setIncludedDownloadDirs(downloadsDirs);
        // Add every file from the Downloads directory to the result cursor. Ignore files that
        // were in the supplied downloaded file paths.
        final int size = downloadsDirs.size();
        for (int i = 0; i < size; ++i) {
            final File downloadsDir = downloadsDirs.get(i);
            for (File file : FileUtils.listFilesOrEmpty(downloadsDir)) {
                boolean inResultsAlready = downloadedFilePaths.contains(file.getAbsolutePath());
                boolean containsQuery = searchString == null || file.getName().contains(
                        searchString);
                if (!inResultsAlready && containsQuery) {
                    includeFileFromSharedStorage(result, file);
                }
            }
        }
    }

    private List<File> getDownloadsDirectories() {
        final List<File> downloadsDirectories = new ArrayList<>();
        downloadsDirectories.add(getTopLevelDownloadsDirectory());
        final File sandboxDir = Environment.buildExternalStorageAndroidSandboxDirs()[0];
        for (File file : FileUtils.listFilesOrEmpty(sandboxDir)) {
            final File downloadDir = new File(file, Environment.DIRECTORY_DOWNLOADS);
            if (downloadDir.exists()) {
                downloadsDirectories.add(downloadDir);
            }
        }
        return downloadsDirectories;
    }

    /**
     * Adds a file to the result cursor. It uses a combination of {@code #RAW_PREFIX} and its
     * absolute file path for its id. Directories are not to be included.
     *
     * @param result cursor containing all documents to be returned by queryChildDocuments or
     *            queryChildDocumentsForManage.
     * @param file file to be included in the result cursor.
     */
    private void includeFileFromSharedStorage(MatrixCursor result, File file)
            throws FileNotFoundException {
        includeFile(result, null, file);
    }

    private static File getTopLevelDownloadsDirectory() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private void renameMediaStoreDownload(String docId, String displayName) {
        final File before = getFileForMediaStoreDownload(docId);
        final File after = new File(before.getParentFile(), displayName);

        if (after.exists()) {
            throw new IllegalStateException("Already exists " + after);
        }
        if (!before.renameTo(after)) {
            throw new IllegalStateException("Failed to rename from " + before + " to " + after);
        }

        final long token = Binder.clearCallingIdentity();
        try {
            final Uri mediaStoreUri = getMediaStoreUri(docId);
            final ContentValues values = new ContentValues();
            values.put(FileColumns.DATA, after.getAbsolutePath());
            values.put(FileColumns.DISPLAY_NAME, displayName);
            final int count = getContext().getContentResolver().update(mediaStoreUri, values,
                    null, null);
            if (count != 1) {
                throw new IllegalStateException("Failed to update " + mediaStoreUri
                        + ", values=" + values);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private File getFileForMediaStoreDownload(String docId) {
        final long token = Binder.clearCallingIdentity();
        try {
            String filePath = null;
            try (Cursor cursor = getContext().getContentResolver().query(
                    getMediaStoreUri(docId), null, null, null)) {
                if (cursor.moveToNext()) {
                    filePath = cursor.getString(cursor.getColumnIndex(FileColumns.DATA));
                }
            }
            if (filePath == null) {
                throw new IllegalStateException("Filepath could not be found for"
                        + " mediastore docId: " + docId);
            }
            return new File(filePath);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void includeDownloadsFromMediaStore(@NonNull MatrixCursor result,
            @Nullable Bundle queryArgs, @Nullable LongArray idsToExclude,
            @Nullable Set<String> filePaths, @NonNull ArrayList<Uri> notificationUris,
            @Nullable String parentId, int limit, boolean includePending) {
        if (limit == 0) {
            return;
        }

        final long token = Binder.clearCallingIdentity();
        final Pair<String, String[]> selectionPair
                = buildSearchSelection(queryArgs, idsToExclude, parentId);
        final Uri.Builder queryUriBuilder = MediaStore.Files.EXTERNAL_CONTENT_URI.buildUpon();
        if (limit != NO_LIMIT) {
            queryUriBuilder.appendQueryParameter(MediaStore.PARAM_LIMIT, String.valueOf(limit));
        }
        if (includePending) {
            MediaStore.setIncludePending(queryUriBuilder);
        }
        try (Cursor cursor = getContext().getContentResolver().query(
                queryUriBuilder.build(), null,
                selectionPair.first, selectionPair.second, null)) {
            while (cursor.moveToNext()) {
                includeDownloadFromMediaStore(result, cursor, filePaths);
            }
            notificationUris.add(MediaStore.Files.EXTERNAL_CONTENT_URI);
            notificationUris.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void includeDownloadFromMediaStore(@NonNull MatrixCursor result,
            @NonNull Cursor mediaCursor, @Nullable Set<String> filePaths) {
        final String mimeType = getMimeType(mediaCursor);
        final boolean isDir = Document.MIME_TYPE_DIR.equals(mimeType);
        final String docId = getDocIdForMediaStoreDownload(
                mediaCursor.getLong(mediaCursor.getColumnIndex(FileColumns._ID)), isDir);
        final String displayName = mediaCursor.getString(
                mediaCursor.getColumnIndex(FileColumns.DISPLAY_NAME));
        // TODO: Get downloads description from MediaProvider.
        final String description = null;
        final long size = mediaCursor.getLong(
                mediaCursor.getColumnIndex(FileColumns.SIZE));
        final long lastModifiedMs = mediaCursor.getLong(
                mediaCursor.getColumnIndex(FileColumns.DATE_MODIFIED)) * 1000;
        final boolean isPending = mediaCursor.getInt(
                mediaCursor.getColumnIndex(FileColumns.IS_PENDING)) == 1;

        int extraFlags = isPending ? Document.FLAG_PARTIAL : 0;
        if (!Document.MIME_TYPE_DIR.equals(mimeType)) {
            extraFlags |= Document.FLAG_SUPPORTS_RENAME;
        }

        includeDownload(result, docId, displayName, description, size, mimeType,
                lastModifiedMs, extraFlags, isPending);
        if (filePaths != null) {
            filePaths.add(mediaCursor.getString(
                    mediaCursor.getColumnIndex(FileColumns.DATA)));
        }
    }

    private String getMimeType(@NonNull Cursor mediaCursor) {
        final int format = mediaCursor.getInt(mediaCursor.getColumnIndex(
                FileColumns.FORMAT));
        // TODO: Remove once b/123311895 is fixed.
        if (format == MtpConstants.FORMAT_ASSOCIATION) {
            return Document.MIME_TYPE_DIR;
        }
        final String mimeType = mediaCursor.getString(
                mediaCursor.getColumnIndex(FileColumns.MIME_TYPE));
        if (mimeType == null) {
            return Document.MIME_TYPE_DIR;
        }
        return mimeType;
    }

    // Copied from MediaDocumentsProvider with some tweaks
    private static Pair<String, String[]> buildSearchSelection(@Nullable Bundle queryArgs,
            @Nullable LongArray idsToExclude, @Nullable String parentId) {
        final StringBuilder selection = new StringBuilder();
        final ArrayList<String> selectionArgs = new ArrayList<>();

        selection.append(FileColumns.IS_DOWNLOAD + "=?");
        selectionArgs.add("1");

        if (parentId == null && idsToExclude != null && idsToExclude.size() > 0) {
            selection.append(" AND ");
            selection.append(FileColumns._ID + " NOT IN (");
            final int size = idsToExclude.size();
            for (int i = 0; i < size; ++i) {
                selection.append(idsToExclude.get(i) + ((i == size - 1) ? ")" : ","));
            }
        }

        if (parentId != null) {
            selection.append(" AND ");
            selection.append(FileColumns.PARENT + "=?");
            selectionArgs.add(parentId);
        } else {
            selection.append(" AND ");
            // SELECT _id FROM files where is_download=1
            final String subQuery = SQLiteQueryBuilder.buildQueryString(false,
                    MediaStore.Files.TABLE, new String[] { FileColumns._ID },
                    FileColumns.IS_DOWNLOAD + "=1", null, null, null, null);
            selection.append(FileColumns.PARENT + " NOT IN ("
                    + subQuery + ")");
        }

        if (queryArgs != null) {
            final boolean shouldExcludeMedia = queryArgs.getBoolean(
                    DocumentsContract.QUERY_ARG_EXCLUDE_MEDIA, false /* defaultValue */);
            if (shouldExcludeMedia) {
                selection.append(" AND ");
                selection.append(FileColumns.MEDIA_TYPE + "=?");
                selectionArgs.add(String.valueOf(FileColumns.MEDIA_TYPE_NONE));
            }

            final String displayName = queryArgs.getString(
                    DocumentsContract.QUERY_ARG_DISPLAY_NAME);
            if (!TextUtils.isEmpty(displayName)) {
                selection.append(" AND ");
                selection.append(FileColumns.DISPLAY_NAME + " LIKE ?");
                selectionArgs.add("%" + displayName + "%");
            }

            final long lastModifiedAfter = queryArgs.getLong(
                    DocumentsContract.QUERY_ARG_LAST_MODIFIED_AFTER, -1 /* defaultValue */);
            if (lastModifiedAfter != -1) {
                selection.append(" AND ");
                selection.append(FileColumns.DATE_MODIFIED
                        + " > " + lastModifiedAfter / 1000);
            }

            final long fileSizeOver = queryArgs.getLong(
                    DocumentsContract.QUERY_ARG_FILE_SIZE_OVER, -1 /* defaultValue */);
            if (fileSizeOver != -1) {
                selection.append(" AND ");
                selection.append(FileColumns.SIZE + " > " + fileSizeOver);
            }

            final String[] mimeTypes = queryArgs.getStringArray(
                    DocumentsContract.QUERY_ARG_MIME_TYPES);
            if (mimeTypes != null && mimeTypes.length > 0) {
                selection.append(" AND ");
                selection.append(FileColumns.MIME_TYPE + " IN (");
                for (int i = 0; i < mimeTypes.length; ++i) {
                    selection.append("?").append((i == mimeTypes.length - 1) ? ")" : ",");
                    selectionArgs.add(mimeTypes[i]);
                }
            }
        }

        return new Pair<>(selection.toString(), selectionArgs.toArray(new String[0]));
    }

    /**
     * A MatrixCursor that spins up a file observer when the first instance is
     * started ({@link #start()}, and stops the file observer when the last instance
     * closed ({@link #close()}. When file changes are observed, a content change
     * notification is sent on the Downloads content URI.
     *
     * <p>This is necessary as other processes, like ExternalStorageProvider,
     * can access and modify files directly (without sending operations
     * through DownloadStorageProvider).
     *
     * <p>Without this, contents accessible by one a Downloads cursor instance
     * (like the Downloads root in Files app) can become state.
     */
    private static final class DownloadsCursor extends MatrixCursor {

        private static final Object mLock = new Object();
        @GuardedBy("mLock")
        private static int mOpenCursorCount = 0;
        @GuardedBy("mLock")
        private static @Nullable ContentChangedRelay mFileWatcher;
        @GuardedBy("mLock")
        private List<File> mIncludedDownloadDirs;

        private final ContentResolver mResolver;

        DownloadsCursor(String[] projection, ContentResolver resolver) {
            super(resolveDocumentProjection(projection));
            mResolver = resolver;
        }

        void setIncludedDownloadDirs(List<File> downloadDirs) {
            synchronized (mLock) {
                mIncludedDownloadDirs = downloadDirs;
            }
        }

        void start() {
            synchronized (mLock) {
                if (mOpenCursorCount++ == 0 && mIncludedDownloadDirs != null) {
                    mFileWatcher = new ContentChangedRelay(mResolver, mIncludedDownloadDirs);
                    mFileWatcher.startWatching();
                }
            }
        }

        @Override
        public void close() {
            super.close();
            synchronized (mLock) {
                if (--mOpenCursorCount == 0 && mFileWatcher != null) {
                    mFileWatcher.stopWatching();
                    mFileWatcher = null;
                }
            }
        }
    }

    /**
     * A file observer that notifies on the Downloads content URI(s) when
     * files change on disk.
     */
    private static class ContentChangedRelay extends FileObserver {
        private static final int NOTIFY_EVENTS = ATTRIB | CLOSE_WRITE | MOVED_FROM | MOVED_TO
                | CREATE | DELETE | DELETE_SELF | MOVE_SELF;

        private File[] mDownloadDirs;
        private final ContentResolver mResolver;

        public ContentChangedRelay(ContentResolver resolver, List<File> downloadDirs) {
            super(downloadDirs, NOTIFY_EVENTS);
            mDownloadDirs = downloadDirs.toArray(new File[0]);
            mResolver = resolver;
        }

        @Override
        public void startWatching() {
            super.startWatching();
            if (DEBUG) Log.d(TAG, "Started watching for file changes in: "
                    + Arrays.toString(mDownloadDirs));
        }

        @Override
        public void stopWatching() {
            super.stopWatching();
            if (DEBUG) Log.d(TAG, "Stopped watching for file changes in: "
                    + Arrays.toString(mDownloadDirs));
        }

        @Override
        public void onEvent(int event, String path) {
            if ((event & NOTIFY_EVENTS) != 0) {
                if (DEBUG) Log.v(TAG, "Change detected at path: " + path);
                mResolver.notifyChange(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, null, false);
                mResolver.notifyChange(Downloads.Impl.CONTENT_URI, null, false);
            }
        }
    }
}
