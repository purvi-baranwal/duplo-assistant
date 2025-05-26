package chatbot.service;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class SchemaService {

    private final DataSource dataSource;
    private String cachedSchema; // Cache the schema for performance

    public SchemaService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String getDatabaseSchemaAsPrompt() {
        if (cachedSchema == null) {
            cachedSchema = fetchAndFormatSchema();
        }
        return cachedSchema;
    }

    private String fetchAndFormatSchema() {
        StringBuilder schemaBuilder = new StringBuilder();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            // Get Tables
            ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"});
            List<String> tableNames = new ArrayList<>();
            while (tables.next()) {
                tableNames.add(tables.getString("TABLE_NAME"));
            }
            tables.close();

            for (String tableName : tableNames) {
                schemaBuilder.append("-- Table: ").append(tableName).append("\n");
                schemaBuilder.append("CREATE TABLE ").append(tableName).append(" (\n");

                // Get Columns
                ResultSet columns = metaData.getColumns(null, null, tableName, "%");
                while (columns.next()) {
                    String columnName = columns.getString("COLUMN_NAME");
                    String dataType = columns.getString("TYPE_NAME");
                    int columnSize = columns.getInt("COLUMN_SIZE");
                    String isNullable = columns.getString("IS_NULLABLE").equals("YES") ? "" : " NOT NULL";

                    schemaBuilder.append("    ").append(columnName).append(" ").append(dataType);
                    if (dataType.equalsIgnoreCase("varchar") || dataType.equalsIgnoreCase("character varying")) {
                        schemaBuilder.append("(").append(columnSize).append(")");
                    }
                    schemaBuilder.append(isNullable).append(",\n");
                }
                columns.close();

                // Get Primary Keys
                ResultSet primaryKeys = metaData.getPrimaryKeys(null, null, tableName);
                while (primaryKeys.next()) {
                    String pkColumnName = primaryKeys.getString("COLUMN_NAME");
                    schemaBuilder.append("    PRIMARY KEY (").append(pkColumnName).append("),\n");
                    break; // Assuming single column PK for simplicity in this example
                }
                primaryKeys.close();

                // Get Foreign Keys
                ResultSet foreignKeys = metaData.getImportedKeys(null, null, tableName);
                while (foreignKeys.next()) {
                    String fkColumnName = foreignKeys.getString("FKCOLUMN_NAME");
                    String pkTableName = foreignKeys.getString("PKTABLE_NAME");
                    String pkColumnName = foreignKeys.getString("PKCOLUMN_NAME");
                    schemaBuilder.append("    FOREIGN KEY (").append(fkColumnName).append(") REFERENCES ")
                            .append(pkTableName).append("(").append(pkColumnName).append("),\n");
                }
                foreignKeys.close();

                // Remove trailing comma and newline, add closing parenthesis
                if (schemaBuilder.charAt(schemaBuilder.length() - 2) == ',') {
                    schemaBuilder.delete(schemaBuilder.length() - 2, schemaBuilder.length());
                }
                schemaBuilder.append(");\n\n");
            }

            return schemaBuilder.toString();
        } catch (SQLException e) {
            System.err.println("Error fetching database schema: " + e.getMessage());
            return ""; // Return empty or throw specific exception
        }
    }
}
