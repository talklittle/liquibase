package liquibase.change.core;

import liquibase.change.*;
import liquibase.database.Database;
import liquibase.database.PreparedStatementFactory;
import liquibase.exception.DatabaseException;
import liquibase.statement.ExecutablePreparedStatement;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.InsertStatement;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts data into an existing table.
 */
@ChangeClass(name="insert", description = "Insert Row", priority = ChangeMetaData.PRIORITY_DEFAULT, appliesTo = "table")
public class InsertDataChange extends AbstractChange implements ChangeWithColumns<ColumnConfig> {

    private String catalogName;
    private String schemaName;
    private String tableName;
    private List<ColumnConfig> columns;

    public InsertDataChange() {
        columns = new ArrayList<ColumnConfig>();
    }

    @ChangeProperty(mustApplyTo ="table.catalog")
    public String getCatalogName() {
        return catalogName;
    }

    public void setCatalogName(String catalogName) {
        this.catalogName = catalogName;
    }

    @ChangeProperty(mustApplyTo ="table.schema")
    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    @ChangeProperty(requiredForDatabase = "all", mustApplyTo = "table")
    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    @ChangeProperty(requiredForDatabase = "all", mustApplyTo = "table.column")
    public List<ColumnConfig> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnConfig> columns) {
        this.columns = columns;
    }

    public void addColumn(ColumnConfig column) {
        columns.add(column);
    }

    public void removeColumn(ColumnConfig column) {
        columns.remove(column);
    }

    public SqlStatement[] generateStatements(Database database) {

        boolean needsStoredProc = false;
        for (ColumnConfig column : columns) {
            if (column.getValueBlob() != null) {
                needsStoredProc = true;
            }
        }

        if (needsStoredProc) {
            return new SqlStatement[]{ new InsertExecutablePreparedStatement(database) };
        }


        InsertStatement statement = new InsertStatement(getCatalogName(), getSchemaName(), getTableName());

        for (ColumnConfig column : columns) {

        	if (database.supportsAutoIncrement()
        			&& column.isAutoIncrement() != null && column.isAutoIncrement()) {
            	// skip auto increment columns as they will be generated by the database
            	continue;
            }

            statement.addColumnValue(column.getName(), column.getValueObject());
        }
        return new SqlStatement[]{
                statement
        };
    }

    /**
     * @see liquibase.change.Change#getConfirmationMessage()
     */
    public String getConfirmationMessage() {
        return "New row inserted into " + getTableName();
    }


    /**
     * Handles INSERT Execution
     */
    public class InsertExecutablePreparedStatement implements ExecutablePreparedStatement {

        private Database database;

        InsertExecutablePreparedStatement(Database database) {
            this.database = database;
        }

        public void execute(PreparedStatementFactory factory) throws DatabaseException {
            // build the sql statement
            StringBuilder sql = new StringBuilder("INSERT INTO ");
            StringBuilder params = new StringBuilder("VALUES(");
            sql.append(database.escapeTableName(getCatalogName(), getSchemaName(), getTableName()));
            sql.append("(");
            List<ColumnConfig> cols = new ArrayList<ColumnConfig>(getColumns().size());
            for(ColumnConfig column : getColumns()) {
                if(database.supportsAutoIncrement()
                    && Boolean.TRUE.equals(column.isAutoIncrement())) {
                    continue;
                }
                sql.append(database.escapeColumnName(getCatalogName(), getSchemaName(), getTableName(), column.getName()));
                sql.append(", ");
                params.append("?, ");
                cols.add(column);
            }
            sql.deleteCharAt(sql.lastIndexOf(" "));
            sql.deleteCharAt(sql.lastIndexOf(","));
            params.deleteCharAt(params.lastIndexOf(" "));
            params.deleteCharAt(params.lastIndexOf(","));
            params.append(")");
            sql.append(") ");
            sql.append(params);

            // create prepared statement
            PreparedStatement stmt = factory.create(sql.toString());

            try {
                // attach params
                int i = 1;  // index starts from 1
                for(ColumnConfig col : cols) {
                    if(col.getValue() != null) {
                        stmt.setString(i, col.getValue());
                    } else if(col.getValueBoolean() != null) {
                        stmt.setBoolean(i, col.getValueBoolean());
                    } else if(col.getValueNumeric() != null) {
                        Number number = col.getValueNumeric();
                        if(number instanceof Long) {
                            stmt.setLong(i, number.longValue());
                        } else if(number instanceof Integer) {
                            stmt.setInt(i, number.intValue());
                        } else if(number instanceof Double) {
                            stmt.setDouble(i, number.doubleValue());
                        } else if(number instanceof Float) {
                            stmt.setFloat(i, number.floatValue());
                        } else if(number instanceof BigDecimal) {
                            stmt.setBigDecimal(i, (BigDecimal)number);
                        } else if(number instanceof BigInteger) {
                            stmt.setInt(i, number.intValue());
                        }
                    } else if(col.getValueDate() != null) {
                        stmt.setDate(i, new java.sql.Date(col.getValueDate().getTime()));
                    } else if(col.getValueBlob() != null) {
                        try {
                            stmt.setBlob(i, new BufferedInputStream(new FileInputStream(col.getValueBlob())));
                        } catch (FileNotFoundException e) {
                            throw new DatabaseException(e.getMessage(), e); // wrap
                        }
                    } else if(col.getValueClob() != null) {
                        try {
                            stmt.setClob(i, new BufferedReader(new FileReader(col.getValueClob())));
                        } catch(FileNotFoundException e) {
                            throw new DatabaseException(e.getMessage(), e); // wrap
                        }
                    }
                    i++;
                }

                // trigger execution
                stmt.execute();
            } catch(SQLException e) {
                throw new DatabaseException(e);
            }
        }

        public boolean skipOnUnsupported() {
            return false;
        }
    }
}