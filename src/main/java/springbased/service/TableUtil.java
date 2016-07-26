package springbased.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import org.apache.log4j.Logger;

import org.mongodb.morphia.Datastore;
import springbased.bean.ConnectionInfo;
import springbased.bean.CopyTableDataRequest;
import springbased.bean.RequestStatusEnum;
import springbased.config.DataStoreFactory;
import springbased.dao.AbstractDAO;
import springbased.monitor.ThreadLocalErrorMonitor;
import springbased.monitor.ThreadLocalMonitor;
import springbased.readonly.ReadOnlyConnection;

public class TableUtil {

  private static final int BATCH = 300;

  private static final Logger log = Logger.getLogger(TableUtil.class);

  public static Map<String, List<String>> columnMap = new HashMap<String, List<String>>();

  public static void execute(ConnectionInfo targetConnInfo,
      String targetSchema, ConnectionInfo sourceConnInfo, String sourceSchema,
      List<String> tableList, long jobId) throws SQLException, InterruptedException {
    ThreadLocalMonitor.getInfo().setTableUtilState("collecting table");
    ReadOnlyConnection sourceConn = MigrationService.getReadOnlyConnection(sourceConnInfo);
    //Connection targetConn = MigrationService.getConnection(targetConnInfo);
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    HashMap<String, String> tempTableList = new HashMap<String, String>();
    try {
      pstmt = sourceConn.prepareStatement(
          "select table_name,TEMPORARY from dba_tables dt where upper(owner)=?  and not exists "
              + "(select 1 from dba_tab_columns dtc where upper(dtc.owner)=? "
              + "and dtc.table_name=dt.table_name and dtc.data_type in ('ROWID','UROWID')) "
              + " order by table_name");
      pstmt.setString(1, sourceSchema.toUpperCase());
      pstmt.setString(2, sourceSchema.toUpperCase());
      pstmt.setFetchSize(2000);
      rs = pstmt.executeQuery();
      while (rs.next()) {
        String tableName = rs.getString(1);
        tableList.add(tableName);
        String tempTable = rs.getString(2);
        tempTableList.put(tableName, tempTable);
        if (ThreadLocalErrorMonitor.isDebugMode()) {          
          log.info(sourceSchema + "find out table:" + tableName + " temporary:" + tempTable);
        }
      }
    } catch (SQLException e) {
      log.error(e);
    } finally {
      rs.close();
      pstmt.close();
      sourceConn.close();
    }

    try {
      copyDataToTable(targetConnInfo, targetSchema, sourceConnInfo, sourceSchema,
          tableList, tempTableList, jobId);
    } catch (NullPointerException e ) {
      log.error(e);
      throw new NullPointerException(e.getMessage());
    }
  }

  public static String timeString() {
    return "[" + new Timestamp(System.currentTimeMillis()).toString() + "] ";
  }

  private static void copyDataToTable(ConnectionInfo targetConnInfo,
      String targetSchema, ConnectionInfo sourceConnInfo, String sourceSchema,
      List<String> tableList, HashMap<String, String> tempTableList, long jobId)
          throws SQLException, InterruptedException {
    ThreadLocalMonitor.getInfo().setTableUtilState("collecting PK contrain");
    ReadOnlyConnection sourceConn = MigrationService.getReadOnlyConnection(sourceConnInfo);
    // get primary key info
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    HashMap<String, List<String>> pkmap = new HashMap<String, List<String>>();
    HashMap<String, String> pktable2namemap = new HashMap<String, String>();
    try {
      pstmt = sourceConn.prepareStatement(
          "select dcc.table_name,dcc.column_name,dcc.constraint_name "
              + " from dba_cons_columns dcc, dba_constraints dc where upper(dc.owner)=? "
              + " and upper(dcc.owner)=? and dcc.constraint_name=dc.constraint_name "
              + "and dc.constraint_type='P' order by dcc.table_name,position ");
      pstmt.setString(1, sourceSchema.toUpperCase());
      pstmt.setString(2, sourceSchema.toUpperCase());
      pstmt.setFetchSize(2000);
      rs = pstmt.executeQuery();

      while (rs.next()) {
        String table_name = rs.getString(1);
        String column_name = rs.getString(2);
        String constraint_name = rs.getString(3);
        if (!pkmap.containsKey(table_name)) {
          pkmap.put(table_name, new ArrayList<String>());
          pktable2namemap.put(table_name, constraint_name);
        }
        List<String> cols = pkmap.get(table_name);
        cols.add(column_name);
        if (ThreadLocalErrorMonitor.isDebugMode()) {
          log.info(sourceSchema + "find constraint_name:" + constraint_name);;
        }
      }
    } catch (SQLException e) {
      log.error(e);
    } finally {
      rs.close();
      pstmt.close();
      sourceConn.close();
    }
    List<String> tableDDLList = new ArrayList<String>();
    List<String> pkDDLList = new ArrayList<String>();
    Map<String, Map<String, Integer>> scaleMap = new HashMap<String, Map<String, Integer>>();

    for (int i = 0; i < tableList.size(); i++) {
      String tableName = tableList.get(i);
      Set<String> tableWithBlobClobColumns = ThreadLocalMonitor.getInfo().getTablesWithBlobClobColumns();
      ThreadLocalMonitor.getFutures().add(ThreadLocalMonitor.getThreadPool().submit(() -> {
        try {
          // construct table DDL
          Map<String, Integer> columnMap = new HashMap<String, Integer>();
          String tableDDL = constructTableDDL(tableName, sourceSchema,
              sourceConnInfo, targetSchema, tempTableList, columnMap, tableWithBlobClobColumns);
          // add primary key DDL
          String pkDDL = addPKDDL(tableName, targetSchema, pkmap,
              pktable2namemap);
          scaleMap.put(tableName, columnMap);
          tableDDLList.add(tableDDL);
          if (pkDDL != null) {
            pkDDLList.add(pkDDL);
          }
        } catch (Exception e) {
          log.error(e);
        }
      }));
    }
    // monitor threads
    monitorAndBlock(ThreadLocalMonitor.getFutures(), tableList.size());
    
    ThreadLocalMonitor.getInfo().setTableUtilState("creating table");
    for (int i = 0; i < tableDDLList.size(); i++) {
      int rate = (i * 100 / tableDDLList.size());
      ThreadLocalMonitor.getInfo().setProcessRate(new Integer(rate).toString());
      Connection targetConn = MigrationService.getConnection(targetConnInfo);
      PreparedStatement ps = null;
      String tableDDL = null;
      try {
        tableDDL = tableDDLList.get(i);
        // create table
        ps = targetConn.prepareStatement(tableDDL);
        ps.execute();
        log.info("successfully run:" + tableDDL);
      } catch (SQLException e) {
        ThreadLocalErrorMonitor.add(tableDDL, e);
        log.error(e);
      } finally {
        ps.close();
        targetConn.close();
      }
    }
    ThreadLocalMonitor.getInfo().setProcessRate("");
    ThreadLocalMonitor.getInfo().setTableUtilState("creating PK");
    for (int i = 0; i < pkDDLList.size(); i++) {
      int rate = (i * 100 / pkDDLList.size());
      ThreadLocalMonitor.getInfo().setProcessRate(new Integer(rate).toString());
      Connection targetConn = MigrationService.getConnection(targetConnInfo);
      PreparedStatement ps = null;
      String pkDDL = null;
      try {
        pkDDL = pkDDLList.get(i);
        ps = targetConn.prepareStatement(pkDDL);
        ps.execute();
        log.info("successfully run:" + pkDDL);
      } catch (SQLException e) {
        ThreadLocalErrorMonitor.add(pkDDL, e);
        log.error(e);
      } finally {
        ps.close();
        targetConn.close();
      }
    }
    ThreadLocalMonitor.getInfo().setProcessRate("");
    ThreadLocalMonitor.getInfo().setTableUtilState("migrateTableData");
    
    // multi-thread processing
    List<String> blobClobFirstTableList = new ArrayList<>();
    for (String table : tableList) {
      if (ThreadLocalMonitor.getInfo().getTablesWithBlobClobColumns().contains(table)) {
        blobClobFirstTableList.add(0, table);
      } else {
        blobClobFirstTableList.add(table);
      }
    }
    for (int i = 0; i < blobClobFirstTableList.size(); i++) {
      String tableName = blobClobFirstTableList.get(i);
      try {
        ThreadLocalMonitor.getFutures()
            .add(ThreadLocalMonitor.getThreadPool().submit(() -> {
              try {
                migrateTableData(tableName, sourceSchema, sourceConnInfo,
                    targetSchema, targetConnInfo, pkmap, jobId);
              } catch (Exception e) {
                log.error(e);
              }
            }));
      } catch (NullPointerException e) {
        log.error(e);
        throw new NullPointerException(e.getMessage());
      }
    }
    // monitor
    monitorAndBlock(ThreadLocalMonitor.getFutures(), tableList.size());
    monitorCopyTableDataRequestsAndBlock();
    ThreadLocalMonitor.getInfo().setProcessRate("");
    ThreadLocalMonitor.getInfo().setTableUtilState("");
  }

  public static String constructTableDDL(String tableName, String sourceSchema,
      ConnectionInfo sourceConnInfo, String targetSchema,
      HashMap<String, String> tempTableList, Map<String, Integer> columnMap, Set<String> tableWithBlobClobColumns)
      throws SQLException, InterruptedException {
    ThreadLocalMonitor.getInfo().setTableUtilState("constructTableDDL");
    ReadOnlyConnection sourceConn = MigrationService.getReadOnlyConnection(sourceConnInfo);
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    StringBuffer sb = new StringBuffer();
    String ddl = null;
    try {
      String temporary = tempTableList.get(tableName);
      boolean isTempTable = false;
      String globalTemp = "";
      if (temporary != null && temporary.equals("Y")) {
        isTempTable = true;
        globalTemp = " GLOBAL TEMPORARY ";
      }

      sb.append("create " + globalTemp + " table " + targetSchema + "."
          + tableName + " (");
      pstmt = sourceConn.prepareStatement(
          "select column_name , data_type,NULLABLE,char_used, char_length,data_length,"
              + "data_precision,data_scale from dba_tab_columns where upper(owner)=? and upper(table_name)=? "
              + "order by column_id");
      pstmt.setString(1, sourceSchema.toUpperCase());
      pstmt.setString(2, tableName.toUpperCase());
      //pstmt.setFetchSize(5000);
      rs = pstmt.executeQuery();
      while (rs.next()) {
        String colname = rs.getString(1);
        String datatype = rs.getString(2);
        String nullable = rs.getString(3);
        String lengthType = rs.getString(4);
        Integer datalength = null;
        if (rs.wasNull()) {
          datalength = rs.getInt(6);
        } else {
          datalength = lengthType.equals("C") ? rs.getInt(5) : rs.getInt(6);
        }
        if (rs.wasNull()) {
          datalength = null;
        }
        Integer dataprecision = rs.getInt(7);
        if (rs.wasNull()) {
          dataprecision = null;
        }
        Integer datascale = rs.getInt(8);
        if (rs.wasNull()) {
          datascale = null;
        } else {
          columnMap.put(colname, new Integer(datascale));
        }
        // transform
        if (datatype.equals("VARCHAR2")) {
          datatype = "VARCHAR2(" + datalength.intValue() + ")";
        } else if (datatype.equals("NVARCHAR2")) {
          datatype = "NVARCHAR2(" + datalength.intValue() + ")";
        } else if (datatype.equals("CHAR")) {
          datatype = "VARCHAR(" + datalength.intValue() + ")";
        } else if (datatype.equals("RAW")) {
          datatype = "RAW(" + datalength.intValue() + ")";
        }
        sb.append("\"" + colname + "\" " + datatype + ""
            + (nullable.equals("N") ? " NOT NULL" : "") + " ,");
        // collection tables with blob or clob columns
        if (datatype.equals("BLOB") || datatype.equals("CLOB")) {
          tableWithBlobClobColumns.add(tableName);
        }
      }

      ddl = sb.toString();
      ddl = ddl.substring(0, ddl.length() - 1);
      ddl += ")";

      // Define the temp table behavior
      if (isTempTable) {
        ddl += "ON COMMIT DELETE ROWS";
      }
      if (ThreadLocalErrorMonitor.isDebugMode()) {      
        log.info("create table DDL:" + ddl);
      }
      return ddl;
    } catch (SQLException e) {
      log.error(e);
    } finally {
      rs.close();
      pstmt.close();
      sourceConn.close();
    }
    return null;
  }

  public static String addPKDDL(String tableName, String targetSchema,
      HashMap<String, List<String>> pkmap,
      HashMap<String, String> pktable2namemap) {
    StringBuffer sb = new StringBuffer();
    sb.append(
        "alter table " + targetSchema + "." + tableName + " add constraint ");

    String colListString = "";
    // fetch everything here
    String pkName = pktable2namemap.get(tableName);
    if (pkName == null || "".equals(pkName)) {
      return null;
    }
    List<String> cols = pkmap.get(tableName);
    if (cols == null || cols.isEmpty()) {
      return null;
    }
    if (pkName.equals(tableName)) {
      pkName = "PK_" + pkName;
    }
    sb.append(pkName + " primary key (");
    if (cols != null) {
      for (int i = 0; i < cols.size(); i++) {
        String column_name = cols.get(i);
        colListString += column_name + ",";
      }
    }

    if (colListString != null) {
      colListString = colListString.substring(0, colListString.length() - 1);
      colListString += ")";
    }
    sb.append(colListString);
    if (ThreadLocalErrorMonitor.isDebugMode()) {
      log.info("constructed add primary key DDL:" + sb.toString());
    }
    return sb.toString();
  }

  public static void migrateTableData(String tableName, String sourceSchema,
                                      ConnectionInfo sourceConnInfo, String targetSchema,
                                      ConnectionInfo targetConnInfo, Map<String, List<String>> pkmap, long jobId)
          throws SQLException, InterruptedException {
    int rows = 0;
    String queryString = "SELECT * FROM " + sourceSchema + "." + tableName + "";
    PreparedStatement pstmt = null;
    PreparedStatement prepStmnt = null;
    ResultSet queryResult = null;
    String insertString = null;
    ReadOnlyConnection sourceConn = MigrationService.getReadOnlyConnection(sourceConnInfo);
    Connection targetConn = null;
    try {
      pstmt = sourceConn.prepareStatement(queryString);
      pstmt.setFetchSize(300);
      queryResult = pstmt.executeQuery();
      ResultSetMetaData md = queryResult.getMetaData();

      String columns = "";
      for (int j = 0; j < md.getColumnCount(); j++) {
        columns = columns + "?,";
      }
      columns = columns.substring(0, columns.length() - 1);

      insertString = "INSERT INTO " + targetSchema + "." + tableName
          + " VALUES (" + columns + ")";
      if (sourceConnInfo.equals(targetConnInfo)) {
        // prevent from dead lock
        targetConn = sourceConn.getConnection();   
      } else {
        targetConn = MigrationService.getConnection(targetConnInfo);
      }
      prepStmnt = targetConn.prepareStatement(insertString);

      int cols = md.getColumnCount();
      
      while (queryResult.next()) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException(
              "migrateTableData thread is interrupted");
        }
        
        for (int i = 1; i <= cols; i++) {
          int type = md.getColumnType(i);
          if (type == Types.DECIMAL || type == Types.DOUBLE
              || type == Types.NUMERIC) {

            if (type == Types.NUMERIC) {
              BigDecimal d = queryResult.getBigDecimal(i);
              if (queryResult.wasNull()) {
                prepStmnt.setNull(i, type);
              } else {
                if (d.precision() > 34
                    && !tableName.contains("EMAIL_TEMPLATE")) {
                  prepStmnt.setBigDecimal(i, new BigDecimal(0));
                } else if (md.getPrecision(i) == 126) {
                  prepStmnt.setFloat(i, d.floatValue());
                } else {
                  prepStmnt.setBigDecimal(i, d);
                }

              }
            } else {
              double d = queryResult.getDouble(i);
              if (queryResult.wasNull()) {
                prepStmnt.setNull(i, type);
              } else {
                prepStmnt.setDouble(i, d);
              }
            }

          } else if (type == Types.INTEGER) {
            int n = queryResult.getInt(i);
            if (queryResult.wasNull()) {
              prepStmnt.setNull(i, type);
            } else {
              prepStmnt.setInt(i, n);
            }

          } else if (type == Types.SMALLINT) {
            short n = queryResult.getShort(i);
            if (queryResult.wasNull()) {
              prepStmnt.setNull(i, type);
            } else {
              prepStmnt.setShort(i, n);
            }

          } else if (type == Types.VARCHAR) {
            String s = queryResult.getString(i);
            prepStmnt.setString(i, s);
          } else if (type == Types.NVARCHAR || type == Types.CHAR) {
            String s = queryResult.getString(i);
            prepStmnt.setString(i, s);
          } else if (type == Types.DATE) {
            Date day = queryResult.getDate(i);
            prepStmnt.setDate(i, day);
          } else if (type == Types.TIME) {
            Time time = queryResult.getTime(i);
            prepStmnt.setTime(i, time);
          } else if (type == Types.TIMESTAMP) {
            Timestamp timestamp = queryResult.getTimestamp(i);

            prepStmnt.setTimestamp(i, timestamp);
            if (timestamp != null) {
              Calendar cal = Calendar.getInstance();
              cal.setTimeInMillis(timestamp.getTime());
              int realyear = cal.get(Calendar.YEAR);

              if (realyear == 0 || realyear < -4713 || realyear > 9999) {
                prepStmnt.setTimestamp(i, null);
              }
            }

          } else if (type == Types.CLOB) {
            if (TableUtil.isPkAnId(md, tableName, sourceSchema, pkmap)) {
              String idColumnName = pkmap.get(tableName).get(0);
              if (TableUtil.populateCopyTableDataRequest(tableName, sourceSchema, sourceConnInfo,
                      targetSchema, targetConnInfo, idColumnName,  sourceConn, jobId)) {
                return;
              }
            }
            String nclob = queryResult.getString(i);
            prepStmnt.setString(i, nclob);
          } else if (type == Types.BLOB) {
            if (TableUtil.isPkAnId(md, tableName, sourceSchema, pkmap)) {
              String idColumnName = pkmap.get(tableName).get(0);
              if (TableUtil.populateCopyTableDataRequest(tableName, sourceSchema, sourceConnInfo,
                      targetSchema, targetConnInfo, idColumnName,  sourceConn, jobId)) {
                return;
              }
            }
            Blob blob = queryResult.getBlob(i);
            if (blob == null) {
              prepStmnt.setNull(i, Types.BLOB);
            } else {
              long lenght = blob.length();
              byte[] bytes = new byte[Long.valueOf(lenght).intValue()];
              try {
                blob.getBinaryStream().read(bytes);
              } catch (IOException e) {
                log.error(e);
              }
              prepStmnt.setBytes(i, bytes);
            }
          } else if (type == Types.VARBINARY || type == Types.BINARY) {
            byte[] s = queryResult.getBytes(i);
            prepStmnt.setBytes(i, s);
          } else {
            byte[] s = queryResult.getBytes(i);
            prepStmnt.setBytes(i, s);
          }
        }
        rows++;
        prepStmnt.addBatch();
        log.info(targetSchema + "add batch:" + insertString);
        
        if (BATCH > 0) {
          try {
            if (rows % BATCH == 0) {
              prepStmnt.executeBatch();
              //targetConn.commit();
              log.info(targetSchema + "batch executed!");
              prepStmnt.clearBatch();
            }
          } catch (SQLException e) {
            log.error(e);
            ThreadLocalErrorMonitor.add(insertString, e);
          }
        }
      }
      prepStmnt.executeBatch();
      //targetConn.commit();
      log.info(targetSchema + "batch executed!");
      prepStmnt.clearBatch();
      columns = null;
    } catch (SQLException e) {
      log.error(e);
      ThreadLocalErrorMonitor.add(insertString, e);
    } catch (Exception e) {
      ThreadLocalErrorMonitor.add(tableName, e);
      log.error("exception caught when copying data of table:" + tableName);
      log.error(e);
    } finally {
      queryResult.close();
      pstmt.close();
      prepStmnt.close();
      targetConn.close();
      sourceConn.close();
    }
  }

  private static boolean populateCopyTableDataRequest(String tableName, String sourceSchema,
                                                   ConnectionInfo sourceConnInfo, String targetSchema,
                                                   ConnectionInfo targetConnInfo, String idColumnName,
                                                   ReadOnlyConnection sourceConn, long jobId) throws SQLException {
    final long THRESHOLD = BATCH * 15;
    final int LOADPERREQUEST = BATCH * 7;
    Datastore ds = DataStoreFactory.getCopyTableDataRequestDS();
    long maxId = maxId(sourceConn,tableName, sourceSchema, idColumnName);
    if (maxId < THRESHOLD) {
      // not huge table, only one request
      CopyTableDataRequest request = new CopyTableDataRequest().setConnectionUrl(sourceConnInfo.getUrl())
              .setUsername(sourceConnInfo.getUsername()).setPassword(sourceConnInfo.getPassword())
              .setTargetConnectionUrl(targetConnInfo.getUrl()).setTargetUsername(targetConnInfo.getUsername())
              .setTargetPassword(targetConnInfo.getPassword()).setSchema(sourceSchema).setTargetSchema(targetSchema)
              .setTable(tableName).setIdColumnName(idColumnName).setStartId(1).setEndId(maxId).setMigrationJobId(jobId);
      ds.save(request);
      return true;
    }
    long numberRequests = maxId / LOADPERREQUEST + 1;
    for (int i = 0; i < numberRequests ; i ++) {
      // split to requests
      CopyTableDataRequest request = new CopyTableDataRequest().setConnectionUrl(sourceConnInfo.getUrl())
              .setUsername(sourceConnInfo.getUsername()).setPassword(sourceConnInfo.getPassword())
              .setTargetConnectionUrl(targetConnInfo.getUrl()).setTargetUsername(targetConnInfo.getUsername())
              .setTargetPassword(targetConnInfo.getPassword()).setSchema(sourceSchema).setTargetSchema(targetSchema)
              .setTable(tableName).setIdColumnName(idColumnName).setStartId(i * LOADPERREQUEST + 1)
              .setEndId(i * LOADPERREQUEST + LOADPERREQUEST).setMigrationJobId(jobId);
      ds.save(request);
    }
    return true;
  }

  private static long maxId(ReadOnlyConnection sourceConn, String tableName, String sourceSchema, String idColumnName)
          throws SQLException {
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
      String sql = "select max( " + idColumnName + " ) from " + sourceSchema + "." + tableName ;
      ps = sourceConn.prepareStatement(sql);
      rs = ps.executeQuery();
      rs.next();
      long maxIdValue = rs.getLong(1);
      return maxIdValue;
    } finally {
      rs.close();
      ps.close();
    }

  }

  private static boolean isPkAnId(ResultSetMetaData md, String tableName, String sourceSchema, Map<String, List<String>> pkmap)
          throws SQLException {
    if (pkmap.get(tableName).size() != 1) {
      return false;
    }
    String idColumnName = pkmap.get(tableName).get(0);
    int cols = md.getColumnCount();
    for (int i = 1; i <= cols; i++) {
      if (md.getColumnName(i).equalsIgnoreCase(idColumnName)) {
        int type = md.getColumnType(i);
        if (type == Types.NUMERIC || type == Types.BIGINT || type == Types.INTEGER) {
          return true;
        }
      }
    }
    return false;
  }

  private static void monitorAndBlock(Set<Future<?>> futures, int totalSize)
      throws InterruptedException {
    // monitor threads
    while (!futures.isEmpty()) {
      if (Thread.currentThread().isInterrupted()) {
        ThreadLocalMonitor.getThreadPool().shutdownNow();
        throw new InterruptedException("Migration Job is interrupted");
      } else {
        Thread.yield();
      }
      ThreadLocalMonitor.getInfo().setProcessRate(
              String.valueOf( ( 100 - ( futures.size() * 100 / totalSize) ) / 2) );
      Iterator<Future<?>> itFuture = futures.iterator();
      while (itFuture.hasNext()) {
        if (itFuture.next().isDone()) {
          itFuture.remove();
        }
      }
    }

  }

  private static void monitorCopyTableDataRequestsAndBlock() throws InterruptedException {
    Datastore ds = DataStoreFactory.getCopyTableDataRequestDS();
    List<CopyTableDataRequest> requests = ds.find(CopyTableDataRequest.class).asList();
    int totalSize = requests.size();
    while (!requests.isEmpty()) {
      if (Thread.currentThread().isInterrupted()) {
        ThreadLocalMonitor.getThreadPool().shutdownNow();
        throw new InterruptedException("Migration Job is interrupted");
      } else {
        Thread.sleep(1000);
      }
      requests = ds.find(CopyTableDataRequest.class).asList();
      final Iterator<CopyTableDataRequest> requestIterator = requests.iterator();
      while (requestIterator.hasNext()) {
        CopyTableDataRequest r = requestIterator.next();
        if (r.getStatus() == RequestStatusEnum.FINISHED) {
          ds.delete(r);
          requestIterator.remove();
        }
      }
      String processRate = String.valueOf( 50 + ( 100 - ( requests.size() * 100 / totalSize) ) / 2);
      log.info("Table Util processRate:" +processRate);
      ThreadLocalMonitor.getInfo().setProcessRate(processRate);
    }
  }

}
