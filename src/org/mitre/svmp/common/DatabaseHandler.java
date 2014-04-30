/*
 Copyright 2013 The MITRE Corporation, All Rights Reserved.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this work except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package org.mitre.svmp.common;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import org.mitre.svmp.performance.MeasurementInfo;
import org.mitre.svmp.performance.PointPerformanceData;
import org.mitre.svmp.performance.SpanPerformanceData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author Joe Portner
 */
public class DatabaseHandler extends SQLiteOpenHelper {
    private static final String TAG = DatabaseHandler.class.getName();

    public static final String DB_NAME = "org.mitre.svmp.db";
    public static final int DB_VERSION = 9;

    public static final int TABLE_CONNECTIONS = 0;
    public static final int TABLE_MEASUREMENT_INFO = 1; // groups together performance data
    public static final int TABLE_PERFORMANCE_DATA = 2; // raw performance data
    public static final String[] Tables = new String[]{
        "Connections",
        "MeasurementInfo",
        "PerformanceData"
    };

    // this is used to generate queries to create new tables with appropriate constraints
    // foreign keys constraints are automatically added for matching table names
    // see SQLite Datatypes: http://www.sqlite.org/datatype3.html
    public static final String[][][] TableColumns = new String[][][] {
        // column, type, constraints
        {
            {"ConnectionID", "INTEGER", "PRIMARY KEY"},
            {"Description", "TEXT"},
            {"Username", "TEXT"},
            {"Host", "TEXT"},
            {"Port", "INTEGER"},
            {"EncryptionType", "INTEGER"},
            {"Domain", "TEXT"},
            {"AuthType", "INTEGER DEFAULT 1"},
            {"SessionToken", "TEXT DEFAULT ''"},
            {"CertificateAlias", "TEXT DEFAULT ''"},
            {"SessionExpires", "INTEGER DEFAULT 0"},
            {"SessionGracePeriod", "INTEGER DEFAULT 0"},
            {"LastDisconnected", "INTEGER DEFAULT 0"} // what time the client disconnected (used to find if session token is still good)
        }, {
            {"StartDate", "INTEGER", "PRIMARY KEY"},
            {"ConnectionID", "INTEGER"}, // foreign key
            {"MeasureInterval", "INTEGER"},
            {"PingInterval", "INTEGER"}
        }, {
            {"MeasureDate", "INTEGER", "PRIMARY KEY"},
            {"StartDate", "INTEGER"},     // foreign key
            {"FrameCount", "INTEGER"},    // count since last measurement
            {"SensorUpdates", "INTEGER"}, // count since last measurement
            {"TouchUpdates", "INTEGER"},  // count since last measurement
            {"CPUUsage", "REAL"},         // percentage (0.0 to 1.0)
            {"MemoryUsage", "INTEGER"},   // measured in kB
            {"WifiStrength", "REAL"},     // percentage (0.0 to 1.0)
            {"BatteryLevel", "REAL"},     // percentage (0.0 to 1.0)
            {"CellNetwork", "INTEGER"},   // what network the device is on (see TelephonyManager.NETWORK_* constants)
            {"CellValues", "TEXT"},       // a variety of cell values (depends on network type; LTE, GSM, CDMA/EVDO...)
            {"Ping", "INTEGER"}           // last ping response in ms
        }
    };

    private SQLiteDatabase db;
    private Context context;

    public DatabaseHandler(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context;
    }

    private SQLiteDatabase getDb() {
        if( db == null )
            db = this.getWritableDatabase();
        return db;
    }

    public void close() {
        // cleanup
        if (db != null && db.isOpen())
            db.close();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // loop through the tables and create them from the TableColumns jagged array
        for (int i = 0; i < Tables.length; i++)
            createTable(i, db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        switch(oldVersion) {
            case 1:
                // changed Connections table, recreate it
                recreateTable(TABLE_CONNECTIONS, db);
            case 2:
                addTableColumn(TABLE_CONNECTIONS, 6, "''", db);
                addTableColumn(TABLE_CONNECTIONS, 7, "0", db);
            case 3:
                // changed auth types, now the IDs begin with 1, not 0
                db.execSQL("UPDATE Connections SET AuthType=1 WHERE AuthType=0;");
            case 4:
                // added measurement info and performance data tables, no need to change existing info
                createTable(TABLE_MEASUREMENT_INFO, db);
                createTable(TABLE_PERFORMANCE_DATA, db);
            case 5:
                addTableColumn(TABLE_CONNECTIONS, 8, "''", db); // SessionToken column added
            case 6:
                addTableColumn(TABLE_CONNECTIONS, 9, "''", db); // CertificateAlias column added
            case 7:
                // changed encryption types, removed SSL/untrusted, now we just have SSL
                db.execSQL("UPDATE Connections SET EncryptionType=1 WHERE EncryptionType=2;");
            case 8:
                // changed session handling, now the client is aware when a session token is not valid
                db.execSQL("UPDATE Connections SET SessionToken='';"); // clear out all existing session tokens
                addTableColumn(TABLE_CONNECTIONS, 10, "0", db); // SessionExpires column added
                addTableColumn(TABLE_CONNECTIONS, 11, "0", db); // SessionGracePeriod column added
                addTableColumn(TABLE_CONNECTIONS, 12, "0", db); // LastDisconnected column added
            default:
                break;
        }
    }

    // This is needed to enable cascade operations for foreign keys (delete and update)
    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) {
            // Enable foreign key constraints
            db.execSQL("PRAGMA foreign_keys=ON;");
        }
    }

    // only used during onUpgrade
    private void addTableColumn(int tableID, int colNum, String defaultVal, SQLiteDatabase db) {
        String query = String.format("ALTER TABLE %s ADD COLUMN %s %s DEFAULT %s",
                Tables[tableID], // table name
                TableColumns[tableID][colNum][0], // column name
                TableColumns[tableID][colNum][1], // column type
                defaultVal);
        // try to create the table with the constructed query
        try {
            db.execSQL(query);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // if the table exists, drop it; then, create the table again
    private void recreateTable(int tableID, SQLiteDatabase db) {
        // try to drop the table
        try {
            db.execSQL(String.format("DROP TABLE IF EXISTS %s", Tables[tableID]));
        } catch (Exception e) {
            e.printStackTrace();
        }
        // create the table again
        createTable(tableID, db);
    }

    // creates a table, along with constraints, based on the TableColumns jagged array
    private void createTable(int tableID, SQLiteDatabase db) {
        StringBuilder query = new StringBuilder();
        StringBuilder primaryKeys = new StringBuilder();
        StringBuilder foreignKeys = new StringBuilder();

        query.append(String.format("CREATE TABLE %s (", Tables[tableID]));

        for (int i = 0; i < TableColumns[tableID].length; i++) {
            if (i > 0)
                query.append(", ");

            // append column name, type, and constraints
            for (int j = 0; j < TableColumns[tableID][i].length; j++) {
                // if this is a primary key option, add it to the string and save it for the end
                if (j == 2 && TableColumns[tableID][i][j].equals("PRIMARY KEY")) {
                    if (primaryKeys.length() > 0)
                        primaryKeys.append(", ");
                    primaryKeys.append(TableColumns[tableID][i][0]);
                }
                // if this is another option (UNIQUE, NOT NULL, etc) add it now
                else {
                    if (j > 0)
                        query.append(" ");
                    query.append(TableColumns[tableID][i][j]);
                }
            }

            // loop through tables to construct foreign key constraints (looks at 1st column/primary key of each table)
            String foreignTable = "";
            for (int k = 0; k < Tables.length; k++) {

                if (k < tableID // skip the same table, skip tables that haven't been added yet
                        && TableColumns[k][0][0].equals(TableColumns[tableID][i][0])
                        && TableColumns[k][0].length > 2
                        && TableColumns[k][0][2].equals("PRIMARY KEY")) {
                    foreignTable = Tables[k];
                    break;
                }
            }
            if (foreignTable.length() > 0) {
                String constraint = String.format(", FOREIGN KEY (%s) REFERENCES %s (%s) ON DELETE CASCADE ON UPDATE CASCADE",
                        TableColumns[tableID][i][0],
                        foreignTable,
                        TableColumns[tableID][i][0]);
                foreignKeys.append(constraint);
            }
        }

        // append primary key constraint(s)
        if( primaryKeys.length() > 0 ) {
            query.append(String.format(", PRIMARY KEY (%s)", primaryKeys.toString()));
        }

        query.append(foreignKeys.toString());
        query.append(");");

        Log.d(TAG, String.format("Creating table: %s", query.toString()));

        // try to create the table with the constructed query
        try {
            db.execSQL(query.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<ConnectionInfo> getConnectionInfoList() {
        // run the query
        Cursor cursor = _getConnectionInfoCursor(null);

        // try to get results and add ConnectionInfo objects to the list
        List<ConnectionInfo> connectionInfoList = new ArrayList<ConnectionInfo>();
        while (cursor.moveToNext()) {
            // construct a new ConnectionInfo from the cursor
            ConnectionInfo connectionInfo = makeConnectionInfo(cursor);

                // add the ConnectionInfo to the list
            if (connectionInfo != null)
                connectionInfoList.add(connectionInfo);
        }

        // cleanup
        try {
            cursor.close();
        } catch (Exception e) {
            // don't care
        }

        return connectionInfoList;
    }

    // returns a ConnectionInfo that matches the given ConnectionID (null if none found)
    public ConnectionInfo getConnectionInfo(int id) {
        return _getConnectionInfo("ConnectionID=?", String.valueOf(id));
    }

    // returns a ConnectionInfo that does NOT match the given ConnectionID, but matches the given description (null if none found)
    public ConnectionInfo getConnectionInfo(int id, String description) {
        return _getConnectionInfo("ConnectionID!=? AND LOWER(Description)=TRIM(LOWER(?))",
                String.valueOf(id), description);
    }

    private ConnectionInfo _getConnectionInfo(String selection, String... selectionArgs) {
        // run the query
        Cursor cursor = _getConnectionInfoCursor(selection, selectionArgs);

        // try to get results and make a ConnectionInfo object to return
        ConnectionInfo connectionInfo = null;
        if (cursor.moveToFirst())
            connectionInfo = makeConnectionInfo(cursor);

        // cleanup
        try {
            cursor.close();
        } catch (Exception e) {
            // don't care
        }

        return connectionInfo;
    }

    // selection and selectionArgs are optional
    private Cursor _getConnectionInfoCursor(String selection, String... selectionArgs) {
        // prepared statement for speed and security
        Cursor cursor = getDb().query(
                Tables[TABLE_CONNECTIONS], // table
                null, // columns (null == "*")
                selection, // selection ('where' clause)
                selectionArgs, // selection args
                null, // group by
                null, // having
                null // order by
        );

        return cursor;
    }

    // returns an empty string if the connection doesn't have a valid session token
    public String getSessionToken(ConnectionInfo connectionInfo) {
        // run the query
        Cursor cursor = getDb().query(
                Tables[TABLE_CONNECTIONS], // table
                new String[] {"SessionToken", "SessionExpires", "SessionGracePeriod", "LastDisconnected"}, // columns (null == "*")
                "ConnectionID=?", // selection ('where' clause)
                new String[] {String.valueOf(connectionInfo.getConnectionID())}, // selection args
                null, // group by
                null, // having
                null // order by
        );

        // try to get results and find a Session Token to return
        String sessionToken = "";
        if (cursor.moveToFirst()) {
            String token = cursor.getString(0);

            if (token != null && token.length() > 0) {
                // we have a token, check to see if it's valid
                long expires = cursor.getLong(1); // the longest the session is valid before it expires
                int gracePeriod = cursor.getInt(2); // the grace period, in seconds, the client has to reconnect
                long disconnected = cursor.getLong(3); // the time the client last disconnected

                SimpleDateFormat sdf = new SimpleDateFormat("h:mm:ss a");
                Log.v(TAG, String.format("Found session info, [token: '%s', expires: '%s', gracePeriod: '%d' seconds, disconnected: '%s']",
                        token, sdf.format(new Date(expires)), gracePeriod, sdf.format(new Date(disconnected))
                ));

                // find the earliest date this session is no longer valid, either by expiring or exceeding the grace period)
                long time = disconnected + (1000 * gracePeriod);
                if (disconnected == 0 || expires < time)
                    time = expires;
                Date expireDate = new Date(time);

                if (expireDate.after(new Date())) {
                    // the session hasn't expired yet, get the token
                    sessionToken = token;
                }
                else {
                    // we have session info, but it's expired; clear it
                    clearSessionInfo(connectionInfo);
                }
            }
        }

        return sessionToken;
    }

    public List<MeasurementInfo> getAllMeasurementInfo() {
        Cursor cursor = getDb().query(
                Tables[TABLE_MEASUREMENT_INFO], // table
                null, // columns (null == "*")
                null, // selection ('where' clause)
                null, // selection args
                null, // group by
                null, // having
                null // order by
        );

        // try to get results and add MeasurementInfo objects to the list
        List<MeasurementInfo> measurementInfoList = new ArrayList<MeasurementInfo>();
        while (cursor.moveToNext()) {
            // construct a new MeasurementInfo from the cursor
            MeasurementInfo measurementInfo = makeMeasurementInfo(cursor);

            // add the MeasurementInfo to the list
            if (measurementInfo != null)
                measurementInfoList.add(measurementInfo);
        }

        // cleanup
        try {
            cursor.close();
        } catch (Exception e) {
            // don't care
        }

        return measurementInfoList;
    }

    public List<String> getAllPerformanceData(MeasurementInfo measurementInfo) {
        Cursor cursor = getDb().query(
                Tables[TABLE_PERFORMANCE_DATA], // table
                null, // columns (null == "*")
                "StartDate=?", // selection ('where' clause)
                new String[] {String.valueOf(measurementInfo.getStartDate().getTime())}, // selection args
                null, // group by
                null, // having
                null // order by
        );

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ");

        // try to get results and add String objects to the list
        List<String> performanceDataList = new ArrayList<String>();
        while (cursor.moveToNext()) {
            // construct a new String from the cursor
            String performanceData = makePerformanceData(cursor, measurementInfo, dateFormat);

            // add the String to the list
            if (performanceData != null)
                performanceDataList.add(performanceData);
        }

        return performanceDataList;
    }

    private ConnectionInfo makeConnectionInfo(Cursor cursor) {
        try {
            // get values from query
            int connectionID = cursor.getInt(0);
            String description = cursor.getString(1);
            String username = cursor.getString(2);
            String host = cursor.getString(3);
            int port = cursor.getInt(4);
            int encryptionType = cursor.getInt(5);
            String domain = cursor.getString(6);
            int authType = cursor.getInt(7);
            String certificateAlias = cursor.getString(9);

            return new ConnectionInfo(connectionID, description, username, host, port, encryptionType,
                    authType, certificateAlias);
        } catch( Exception e ) {
            e.printStackTrace();
            return null;
        }
    }

    private MeasurementInfo makeMeasurementInfo(Cursor cursor) {
        try {
            // get values from query
            long startDate = cursor.getLong(0);
            int connectionID = cursor.getInt(1);
            int measureInterval = cursor.getInt(2);
            int pingInterval = cursor.getInt(3);

            return new MeasurementInfo(new Date(startDate), connectionID, measureInterval, pingInterval);
        } catch( Exception e ) {
            e.printStackTrace();
            return null;
        }
    }

    private String makePerformanceData(Cursor cursor, MeasurementInfo measurementInfo, SimpleDateFormat dateFormat) {
        try {
            // get values from query
            long measureDate = cursor.getLong(0);
            //long startDate = cursor.getLong(1); // don't need this, it's just a foreign key
            int frameCount = cursor.getInt(2);
            int sensorUpdates = cursor.getInt(3);
            int touchUpdates = cursor.getInt(4);
            double cpuUsage = cursor.getDouble(5);
            int memoryUsage = cursor.getInt(6);
            double wifiStrength = cursor.getDouble(7);
            double batteryLevel = cursor.getDouble(8);
            int cellNetwork = cursor.getInt(9);
            String cellValues = cursor.getString(10);
            int ping = cursor.getInt(11);

            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(dateFormat.format(new Date(measureDate)));
            stringBuilder.append(",");
            stringBuilder.append(numberPerSecond(frameCount, measurementInfo.getMeasureInterval()));
            stringBuilder.append(",");
            stringBuilder.append(numberPerSecond(sensorUpdates, measurementInfo.getMeasureInterval()));
            stringBuilder.append(",");
            stringBuilder.append(numberPerSecond(touchUpdates, measurementInfo.getMeasureInterval()));
            stringBuilder.append(",");
            stringBuilder.append(cpuUsage);
            stringBuilder.append(",");
            stringBuilder.append(memoryUsage);
            stringBuilder.append(",");
            stringBuilder.append(wifiStrength);
            stringBuilder.append(",");
            stringBuilder.append(batteryLevel);
            stringBuilder.append(",");
            stringBuilder.append(Utility.cellNetwork(cellNetwork));
            stringBuilder.append(",");
            stringBuilder.append(cellValues);
            stringBuilder.append(",");
            stringBuilder.append(ping);

            return stringBuilder.toString();
        } catch( Exception e ) {
            e.printStackTrace();
            return null;
        }
    }
    private double numberPerSecond(int input, int interval) {
        if (interval > 0) // sanity
            return ((double)input) / (interval/1000);
        return input;
    }

    public long insertConnectionInfo(ConnectionInfo connectionInfo) {
        // attempt insert
        return insertRecord(TABLE_CONNECTIONS, makeContentValues(connectionInfo));
    }

    public long insertMeasurementInfo(MeasurementInfo measurementInfo) {
        ContentValues contentValues = new ContentValues();
        contentValues.put("StartDate", measurementInfo.getStartDate().getTime());
        contentValues.put("ConnectionID", measurementInfo.getConnectionID());
        contentValues.put("MeasureInterval", measurementInfo.getMeasureInterval());
        contentValues.put("PingInterval", measurementInfo.getPingInterval());

        return insertRecord(TABLE_MEASUREMENT_INFO, contentValues);
    }

    public long insertPerformanceData(long startDate, SpanPerformanceData spanMeasurements,
                                      PointPerformanceData pointMeasurements) {
        ContentValues contentValues = new ContentValues();
        contentValues.put("MeasureDate", System.currentTimeMillis());
        contentValues.put("StartDate", startDate);
        contentValues.put("FrameCount", spanMeasurements.getFrameCount());
        contentValues.put("SensorUpdates", spanMeasurements.getSensorUpdates());
        contentValues.put("TouchUpdates", spanMeasurements.getTouchUpdates());
        contentValues.put("CpuUsage", pointMeasurements.getCpuUsage());
        contentValues.put("MemoryUsage", pointMeasurements.getMemoryUsage());
        contentValues.put("WifiStrength", pointMeasurements.getWifiStrength());
        contentValues.put("BatteryLevel", pointMeasurements.getBatteryLevel());
        contentValues.put("CellNetwork", pointMeasurements.getCellNetwork());
        contentValues.put("CellValues", pointMeasurements.getCellValues());
        contentValues.put("Ping", pointMeasurements.getPing());

        return insertRecord(TABLE_PERFORMANCE_DATA, contentValues);
    }

    private long insertRecord(int tableID, ContentValues contentValues) {
        long result = -1;

        // attempt insert
        try {
            result = getDb().insert(Tables[tableID], null, contentValues);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // return result
        return result;
    }

    public long updateConnectionInfo(ConnectionInfo connectionInfo) {
        // attempt insert
        return updateRecord(
                TABLE_CONNECTIONS,
                makeContentValues(connectionInfo),
                "ConnectionID=?",
                String.valueOf(connectionInfo.getConnectionID())
        );
    }

    public long updateSessionInfo(ConnectionInfo connectionInfo, String token, long expires, int gracePeriod) {
        // create content values
        ContentValues contentValues = new ContentValues();
        contentValues.put("SessionToken", token);
        contentValues.put("SessionExpires", expires); // when max length from initial connect time is reached, session expires
        contentValues.put("SessionGracePeriod", gracePeriod);

        // attempt update
        return updateRecord(
                TABLE_CONNECTIONS,
                contentValues,
                "ConnectionID=?",
                String.valueOf(connectionInfo.getConnectionID())
        );
    }

    public long clearSessionInfo(ConnectionInfo connectionInfo) {
        return updateSessionInfo(connectionInfo, "", 0, 0);
    }

    // when the client disconnects, we store the timestamp
    public long updateLastDisconnected(ConnectionInfo connectionInfo, long disconnected) {
        // create content values
        ContentValues contentValues = new ContentValues();
        contentValues.put("LastDisconnected", disconnected);

        // attempt update
        return updateRecord(
                TABLE_CONNECTIONS,
                contentValues,
                "ConnectionID=?",
                String.valueOf(connectionInfo.getConnectionID())
        );
    }

    private long updateRecord(int tableID, ContentValues contentValues, String whereClause, String... whereArgs) {
        long result = -1;

        // attempt update
        try {
            result = getDb().update(Tables[tableID], contentValues, whereClause, whereArgs);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // return result
        return result;
    }

    public long wipeAllPerformanceData() {
        // delete all measurement info (will cascade delete all performance data)
        return getDb().delete(Tables[TABLE_MEASUREMENT_INFO], null, null);
    }

    private ContentValues makeContentValues(ConnectionInfo connectionInfo) {
        ContentValues contentValues = new ContentValues();

        if (connectionInfo != null) {
            contentValues.put("Description", connectionInfo.getDescription());
            contentValues.put("Username", connectionInfo.getUsername());
            contentValues.put("Host", connectionInfo.getHost());
            contentValues.put("Port", connectionInfo.getPort());
            contentValues.put("EncryptionType", connectionInfo.getEncryptionType());
            contentValues.put("Domain", "");
            contentValues.put("AuthType", connectionInfo.getAuthType());
            contentValues.put("CertificateAlias", connectionInfo.getCertificateAlias());
        }

        return contentValues;
    }

    public long deleteConnectionInfo(int connectionID) {
        // attempt delete
        return deleteRecord(TABLE_CONNECTIONS, "ConnectionID=?", String.valueOf(connectionID));
    }

    private long deleteRecord(int tableID, String whereClause, String... whereArgs) {
        long result = -1;

        // attempt delete
        try {
            result = getDb().delete(Tables[tableID], whereClause, whereArgs);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // return result
        return result;
    }
}