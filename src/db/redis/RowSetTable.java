package db.redis;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Таблица в памяти → {@link ResultSet} (через JDK CachedRowSet, без внешних jar).
 * Нужна, чтобы ответы Redis шли через тот же конвейер, что и JDBC:
 * XML/JSON-форматтеры, запись в MSSQL/OB, Prometheus.
 * Все колонки — VARCHAR.
 */
public final class RowSetTable {

    private final String[] columns;
    private final List<String[]> rows = new ArrayList<>();

    public RowSetTable(String... columns) {
        this.columns = columns;
    }

    public RowSetTable add(String... values) {
        String[] r = new String[columns.length];
        for (int i = 0; i < r.length && i < values.length; i++) r[i] = values[i];
        rows.add(r);
        return this;
    }

    public int size() { return rows.size(); }

    public ResultSet toResultSet() throws SQLException {
        CachedRowSet rs = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl md = new RowSetMetaDataImpl();
        md.setColumnCount(columns.length);
        for (int i = 0; i < columns.length; i++) {
            md.setColumnName(i + 1, columns[i]);
            md.setColumnLabel(i + 1, columns[i]);
            md.setColumnType(i + 1, Types.VARCHAR);
            md.setColumnTypeName(i + 1, "VARCHAR");
            md.setNullable(i + 1, ResultSetMetaData_NULLABLE);
        }
        rs.setMetaData(md);
        for (String[] r : rows) {
            rs.last();                 // без этого CachedRowSet вставляет строки в обратном порядке
            rs.moveToInsertRow();
            for (int i = 0; i < r.length; i++) {
                if (r[i] == null) rs.updateNull(i + 1);
                else rs.updateString(i + 1, r[i]);
            }
            rs.insertRow();
            rs.moveToCurrentRow();
        }
        rs.beforeFirst();
        return rs;
    }

    private static final int ResultSetMetaData_NULLABLE = java.sql.ResultSetMetaData.columnNullable;
}
