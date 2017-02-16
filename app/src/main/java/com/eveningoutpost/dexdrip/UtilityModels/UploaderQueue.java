package com.eveningoutpost.dexdrip.UtilityModels;

import android.database.Cursor;
import android.provider.BaseColumns;
import android.util.Log;
import android.util.LongSparseArray;

import com.activeandroid.Cache;
import com.activeandroid.Model;
import com.activeandroid.annotation.Column;
import com.activeandroid.annotation.Table;
import com.activeandroid.query.Delete;
import com.activeandroid.query.Select;
import com.activeandroid.util.SQLiteUtils;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.Calibration;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Treatments;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by jamorham on 15/11/2016.
 */

@Table(name = "UploaderQueue", id = BaseColumns._ID)
public class UploaderQueue extends Model {
    private static final boolean d = false;
    private final static String TAG = "UploaderQueue";
    private final static String[] schema = {
            "CREATE TABLE UploaderQueue (_id INTEGER PRIMARY KEY AUTOINCREMENT);",
            "ALTER TABLE UploaderQueue ADD COLUMN timestamp INTEGER;",
            "ALTER TABLE UploaderQueue ADD COLUMN action TEXT;",
            "ALTER TABLE UploaderQueue ADD COLUMN otype TEXT;",
            "ALTER TABLE UploaderQueue ADD COLUMN reference_id INTEGER;",
            "ALTER TABLE UploaderQueue ADD COLUMN reference_uuid TEXT;",
            "ALTER TABLE UploaderQueue ADD COLUMN bitfield_wanted INTEGER;",
            "ALTER TABLE UploaderQueue ADD COLUMN bitfield_complete INTEGER;",

            "CREATE INDEX index_UploaderQueue_action on UploaderQueue(action);",
            "CREATE INDEX index_UploaderQueue_otype on UploaderQueue(otype);",
            "CREATE INDEX index_UploaderQueue_timestamp on UploaderQueue(timestamp);",
            "CREATE INDEX index_UploaderQueue_complete on UploaderQueue(bitfield_complete);",
            "CREATE INDEX index_UploaderQueue_wanted on UploaderQueue(bitfield_wanted);"};

    // table creation
    private static boolean patched = false;
    private static long last_cleanup = 0;
    private static long last_new_entry = 0;
    private static long last_query = 0;

    // Bitfields
    public static final long MONGO_DIRECT = 1;
    public static final long NIGHTSCOUT_RESTAPI = 1 << 1;
    public static final long TEST_OUTPUT_PLUGIN = 1 << 2;
    public static final long INFLUXDB_RESTAPI = 1 << 3;


    public static final long DEFAULT_UPLOAD_CIRCUITS = 0;

    private static final LongSparseArray circuits_for_stats = new LongSparseArray<String>() {
        {
            append(MONGO_DIRECT, "Mongo Direct");
            append(NIGHTSCOUT_RESTAPI, "Nightscout REST");
            append(TEST_OUTPUT_PLUGIN, "Test Plugin");
            append(INFLUXDB_RESTAPI, "InfluxDB REST");
        }
    };


    //...


    @Expose
    @Column(name = "timestamp", index = true)
    public long timestamp;

    @Expose
    @Column(name = "action", index = true)
    public String action;

    @Expose
    @Column(name = "otype", index = true)
    public String type;

    @Expose
    @Column(name = "reference_id")
    public long reference_id;

    @Expose
    @Column(name = "reference_uuid")
    public String reference_uuid;

    @Expose
    @Column(name = "bitfield_wanted", index = true)
    public long bitfield_wanted;

    @Expose
    @Column(name = "bitfield_complete", index = true)
    public long bitfield_complete;

    //////////////////////////////////////////

    // patches and saves
    public Long saveit() {
        fixUpTable();
        return save();
    }

    public Long completed(long bitfield) {
        UserError.Log.d(TAG, "Marking bitfield " + bitfield + " completed on: " + getId() + " / " + action + " " + type + " " + reference_id);
        bitfield_complete |= bitfield;
        return saveit();
    }

    public String toS() {
        final Gson gson = new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .create();
        return gson.toJson(this);
    }

    //////////////////////////////////////////

    public static UploaderQueue newEntry(String action, Model obj) {
        UserError.Log.d(TAG, "new entry called");
        final UploaderQueue result = new UploaderQueue();
        result.bitfield_wanted = DEFAULT_UPLOAD_CIRCUITS
                | (Home.getPreferencesBooleanDefaultFalse("cloud_storage_mongodb_enable") ? MONGO_DIRECT : 0)
                | (Home.getPreferencesBooleanDefaultFalse("cloud_storage_api_enable") ? NIGHTSCOUT_RESTAPI : 0)
                | (Home.getPreferencesBooleanDefaultFalse("cloud_storage_influxdb_enable") ? INFLUXDB_RESTAPI : 0);
        if (result.bitfield_wanted == 0) return null; // no queue required
        result.timestamp = JoH.tsl();
        result.reference_id = obj.getId();
        // TODO this probably could be neater
        if (result.reference_uuid == null)
            result.reference_uuid = obj instanceof BgReading ? ((BgReading) obj).uuid : null;
        if (result.reference_uuid == null)
            result.reference_uuid = obj instanceof Treatments ? ((Treatments) obj).uuid : null;
        if (result.reference_uuid == null)
            result.reference_uuid = obj instanceof Calibration ? ((Calibration) obj).uuid : null;
        result.action = action;

        result.bitfield_complete = 0;
        result.type = obj.getClass().getSimpleName();
        result.saveit();
        if (d) UserError.Log.d(TAG, result.toS());
        last_new_entry = JoH.tsl();
        return result;
    }

    public static List<UploaderQueue> getPendingbyType(String className, long bitfield) {
        return getPendingbyType(className, bitfield, 500);
    }

    public static List<UploaderQueue> getPendingbyType(String className, long bitfield, int limit) {
        if (d) UserError.Log.d(TAG, "get Pending by type: " + className);
        last_query = JoH.tsl();
        try {
            final String bitfields = Long.toString(bitfield);
            return new Select()
                    .from(UploaderQueue.class)
                    .where("otype = ?", className)
                    .where("(bitfield_wanted & " + bitfields + ") == " + bitfields)
                    .where("(bitfield_complete & " + bitfields + ") != " + bitfields)
                    .orderBy("timestamp asc, _id asc") // would _id asc be sufficient?
                    .limit(limit)
                    .execute();
        } catch (android.database.sqlite.SQLiteException e) {
            if (d) UserError.Log.d(TAG, "Exception: " + e.toString());
            fixUpTable();
            return new ArrayList<UploaderQueue>();
        }
    }


    private static int getLegacyCount(Class which, Boolean rest, Boolean mongo, Boolean and) {
        try {
            String where = "";
            if (rest != null) where += " success = " + (rest ? "1 " : "0 ");
            if (and != null) where += (and ? " and " : " or ");
            if (mongo != null) where += " mongo_success = " + (mongo ? "1 " : "0 ");
            final String query = new Select("COUNT(*) as total").from(which).toSql();
            final Cursor resultCursor = Cache.openDatabase().rawQuery(query + ((where.length() > 0) ? " where " + where : ""), null);
            if (resultCursor.moveToNext()) {
                final int total = resultCursor.getInt(0);
                resultCursor.close();
                return total;
            } else {
                return 0;
            }

        } catch (Exception e) {
            Log.d(TAG, "Got exception getting count: " + e);
            return -1;
        }
    }


    private static int getCount(String where) {
        try {
            final String query = new Select("COUNT(*) as total").from(UploaderQueue.class).toSql();
            final Cursor resultCursor = Cache.openDatabase().rawQuery(query + where, null);
            if (resultCursor.moveToNext()) {
                final int total = resultCursor.getInt(0);
                resultCursor.close();
                return total;
            } else {
                return 0;
            }

        } catch (Exception e) {
            Log.d(TAG, "Got exception getting count: " + e);
            return 0;
        }
    }

    private static List<String> getClasses() {
        fixUpTable();
        final ArrayList<String> results = new ArrayList<>();
        final String query = new Select("distinct otype as otypes").from(UploaderQueue.class).toSql();
        final Cursor resultCursor = Cache.openDatabase().rawQuery(query, null);
        while (resultCursor.moveToNext()) {
            results.add(resultCursor.getString(0));
        }
        resultCursor.close();
        return results;
    }


    public static int getQueueSizeByType(String className, long bitfield, boolean completed) {
        fixUpTable();
        if (d) UserError.Log.d(TAG, "get Pending count by type: " + className);
        try {
            final String bitfields = Long.toString(bitfield);
            return getCount(" where otype = '" + className + "'" + " and (bitfield_wanted & " + bitfields + ") == " + bitfields + " and (bitfield_complete & " + bitfields + ") " + (completed ? "== " : "!= ") + bitfields);
        } catch (android.database.sqlite.SQLiteException e) {
            if (d) UserError.Log.d(TAG, "Exception: " + e.toString());
            fixUpTable();
            return 0;
        }
    }


    public static void cleanQueue() {
        // delete all completed records > 24 hours old
        fixUpTable();
        try {
            new Delete()
                    .from(UploaderQueue.class)
                    .where("timestamp < ?", JoH.tsl() - 86400000L)
                    .where("bitfield_wanted == bitfield_complete")
                    .execute();

            // delete everything > 7 days old
            new Delete()
                    .from(UploaderQueue.class)
                    .where("timestamp < ?", JoH.tsl() - 86400000L * 7L)
                    .execute();
        } catch (Exception e) {
            UserError.Log.d(TAG, "Exception cleaning uploader queue: " + e);
        }
        last_cleanup = JoH.tsl();
    }


    private static void fixUpTable() {
        if (patched) return;

        for (String patch : schema) {
            try {
                SQLiteUtils.execSql(patch);
            } catch (Exception e) {
                if (d)
                    UserError.Log.d(TAG, "Patch: " + patch + " generated exception as it should: " + e.toString());
            }
        }
        patched = true;
    }

    public static String getCircuitName(long i) {
        try {
            return circuits_for_stats.get(i).toString();
        } catch (Exception e) {
            return "Unknown Circuit";
        }
    }

    public static List<StatusItem> megaStatus() {
        final List<StatusItem> l = new ArrayList<>();
        // per circuit
        for (int i = 0, size = circuits_for_stats.size(); i < size; i++) {
            final long bitfield = circuits_for_stats.keyAt(i);

            // per class of data
            for (String type : getClasses()) {
                Log.d(TAG, "Getting stats for class: " + type + " in " + circuits_for_stats.valueAt(i));
                int count_pending = getQueueSizeByType(type, bitfield, false);
                int count_completed = getQueueSizeByType(type, bitfield, true);
                int count_total = count_pending + count_completed;

                if (count_total > 0) {
                    l.add(new StatusItem(circuits_for_stats.valueAt(i).toString(), count_pending + " " + type));
                }
            }

               /*
                // handle legacy tables
                if (bitfield == MONGO_DIRECT) {
                    // legacy
                    l.add(new StatusItem(circuits_for_stats.valueAt(i).toString(), getLegacyCount(BgSendQueue.class, null, false, null) + " Legacy Glucose Values"));
                    l.add(new StatusItem(circuits_for_stats.valueAt(i).toString(), getLegacyCount(CalibrationSendQueue.class, null, false, null) + " Legacy Calibrations"));
                }
                // handle legacy tables
                if (bitfield == NIGHTSCOUT_RESTAPI) {
                    // legacy
                    l.add(new StatusItem(circuits_for_stats.valueAt(i).toString(), getLegacyCount(BgSendQueue.class, false, null, null) + " Legacy Glucose Values"));
                    l.add(new StatusItem(circuits_for_stats.valueAt(i).toString(), getLegacyCount(CalibrationSendQueue.class, false, null, null) + " Legacy Calibrations"));
                }*/
        }

        if (MongoSendTask.exception != null) {
            l.add(new StatusItem("Exception", MongoSendTask.exception.toString(), StatusItem.Highlight.BAD));

        }


        if (last_query > 0)
            l.add(new StatusItem("Last poll", JoH.niceTimeSince(last_query)+" ago"));

        if (last_cleanup > 0)
            l.add(new StatusItem("Last clean up", JoH.niceTimeSince(last_cleanup)+ " ago"));

        return l;
    }
}
