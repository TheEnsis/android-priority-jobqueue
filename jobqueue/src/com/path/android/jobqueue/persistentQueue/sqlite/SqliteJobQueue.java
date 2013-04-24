package com.path.android.jobqueue.persistentQueue.sqlite;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import com.path.android.jobqueue.BaseJob;
import com.path.android.jobqueue.JobHolder;
import com.path.android.jobqueue.JobManager;
import com.path.android.jobqueue.JobQueue;
import com.path.android.jobqueue.log.JqLog;

import java.io.*;

/**
 * Persistent Job Queue that keeps its data in an sqlite database.
 */
public class SqliteJobQueue implements JobQueue {
    DbOpenHelper dbOpenHelper;
    private final long sessionId;
    SQLiteDatabase db;
    SqlHelper sqlHelper;
    String selectQuery;

    /**
     * @param context application context
     * @param sessionId session id should match {@link JobManager}
     * @param id uses this value to construct database name {@code "db_" + id}
     */
    public SqliteJobQueue(Context context, long sessionId, String id) {
        this.sessionId = sessionId;
        dbOpenHelper = new DbOpenHelper(context, "db_" + id);
        db = dbOpenHelper.getWritableDatabase();
        sqlHelper = new SqlHelper(db, DbOpenHelper.JOB_HOLDER_TABLE_NAME, DbOpenHelper.ID_COLUMN.columnName, DbOpenHelper.COLUMN_COUNT);
        selectQuery = sqlHelper.createSelect(
                DbOpenHelper.RUNNING_SESSION_ID_COLUMN.columnName + " != " + sessionId,
                1,
                new SqlHelper.Order(DbOpenHelper.PRIORITY_COLUMN, SqlHelper.Order.Type.DESC),
                new SqlHelper.Order(DbOpenHelper.CREATED_NS_COLUMN, SqlHelper.Order.Type.ASC),
                new SqlHelper.Order(DbOpenHelper.ID_COLUMN, SqlHelper.Order.Type.ASC)
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long insert(JobHolder jobHolder) {
        SQLiteStatement stmt = sqlHelper.getInsertStatement();
        long id;
        synchronized (stmt) {
            bindValues(stmt, jobHolder);
            id = stmt.executeInsert();
        }
        jobHolder.setId(id);
        return id;
    }

    private void bindValues(SQLiteStatement stmt, JobHolder jobHolder) {
        if (jobHolder.getId() != null) {
            stmt.bindLong(1, jobHolder.getId());
        }
        stmt.bindLong(2, jobHolder.getPriority());
        stmt.bindLong(3, jobHolder.getRunCount());
        byte[] baseJob = getSerializeBaseJob(jobHolder);
        if (baseJob != null) {
            stmt.bindBlob(4, baseJob);
        }
        stmt.bindLong(5, jobHolder.getCreatedNs());
        stmt.bindLong(6, jobHolder.getRunningSessionId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long insertOrReplace(JobHolder jobHolder) {
        if (jobHolder.getId() == null) {
            return insert(jobHolder);
        }
        jobHolder.setRunningSessionId(JobManager.NOT_RUNNING_SESSION_ID);
        SQLiteStatement stmt = sqlHelper.getInsertOrReplaceStatement();
        long id;
        synchronized (stmt) {
            bindValues(stmt, jobHolder);
            id = stmt.executeInsert();
        }
        jobHolder.setId(id);
        return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(JobHolder jobHolder) {
        if (jobHolder.getId() == null) {
            JqLog.w("called remove with null job id.");
            return;
        }
        delete(jobHolder.getId());
    }

    private void delete(Long id) {
        SQLiteStatement stmt = sqlHelper.getDeleteStatement();
        synchronized (stmt) {
            stmt.bindLong(1, id);
            stmt.execute();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long count() {
        SQLiteStatement stmt = sqlHelper.getCountStatement();
        synchronized (stmt) {
            stmt.bindLong(1, sessionId);
            return stmt.simpleQueryForLong();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JobHolder nextJobAndIncRunCount() {
        Cursor cursor = db.rawQuery(selectQuery, null);
        try {
            if (!cursor.moveToNext()) {
                return null;
            }
            JobHolder holder = createJobHolderFromCursor(cursor);
            onJobFetchedForRunning(holder);
            return holder;
        } catch (InvalidBaseJobException e) {
            //delete
            Long jobId = cursor.getLong(0);
            delete(jobId);
            return nextJobAndIncRunCount();
        } finally {
            cursor.close();
        }
    }

    private void onJobFetchedForRunning(JobHolder jobHolder) {
        SQLiteStatement stmt = sqlHelper.getOnJobFetchedForRunningStatement();
        jobHolder.setRunCount(jobHolder.getRunCount() + 1);
        jobHolder.setRunningSessionId(sessionId);
        synchronized (stmt) {
            stmt.bindLong(1, jobHolder.getRunCount());
            stmt.bindLong(2, sessionId);
            stmt.bindLong(3, jobHolder.getId());
            stmt.execute();
        }
    }

    private JobHolder createJobHolderFromCursor(Cursor cursor) throws InvalidBaseJobException {
        BaseJob job = safeDeserialize(cursor.getBlob(3));
        if (job == null) {
            throw new InvalidBaseJobException();
        }
        return new JobHolder(
                cursor.getLong(DbOpenHelper.ID_COLUMN.columnIndex),
                cursor.getInt(DbOpenHelper.PRIORITY_COLUMN.columnIndex),
                cursor.getInt(DbOpenHelper.RUN_COUNT_COLUMN.columnIndex),
                job,
                cursor.getLong(DbOpenHelper.CREATED_NS_COLUMN.columnIndex),
                cursor.getLong(DbOpenHelper.RUNNING_SESSION_ID_COLUMN.columnIndex)
        );

    }

    private BaseJob safeDeserialize(byte[] bytes) {
        try {
            return deserialize(bytes);
        } catch (Throwable t) {
            JqLog.e(t, "error while deserializing job");
        }
        return null;
    }

    private <T> T deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        ObjectInputStream in = null;
        try {
            in = new ObjectInputStream(new ByteArrayInputStream(bytes));
            return (T) in.readObject();
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }


    private byte[] getSerializeBaseJob(JobHolder jobHolder) {
        return safeSerialize(jobHolder.getBaseJob());
    }

    private byte[] safeSerialize(Object object) {
        try {
            return serialize(object);
        } catch (Throwable t) {
            JqLog.e(t, "error while serializing object %s", object.getClass().getSimpleName());
        }
        return null;
    }

    private byte[] serialize(Object object) throws IOException {
        if (object == null) {
            return null;
        }
        ByteArrayOutputStream bos = null;
        try {
            ObjectOutput out = null;
            bos = new ByteArrayOutputStream();
            out = new ObjectOutputStream(bos);
            out.writeObject(object);
            // Get the bytes of the serialized object
            return bos.toByteArray();
        } finally {
            if (bos != null) {
                bos.close();
            }
        }
    }

    private static class InvalidBaseJobException extends Exception {

    }
}
