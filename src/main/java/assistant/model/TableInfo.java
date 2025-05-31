package assistant.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class TableInfo {
    private String tableName;
    private List<String> columns = new ArrayList<>();
    private List<String> primaryKeys = new ArrayList<>();
    // Map<FK column, Pair<referenced table, referenced column>>
    private Map<String, ForeignKeyReference> foreignKeys = new HashMap<>();

    @Data
    public static class ForeignKeyReference {
        private String referencedTable;
        private String referencedColumn;

        public ForeignKeyReference(String referencedTable, String referencedColumn) {
            this.referencedTable = referencedTable;
            this.referencedColumn = referencedColumn;
        }
    }
}
