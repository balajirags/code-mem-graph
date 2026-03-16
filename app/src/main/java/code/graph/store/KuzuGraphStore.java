package code.graph.store;

import code.graph.model.*;
import com.kuzudb.*;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Persists a CodeGraph to an embedded KuzuDB database.
 */
public class KuzuGraphStore implements GraphStore {

    private final Database database;
    private final Connection connection;

    public KuzuGraphStore(String dbPath) {
        System.out.printf("KuzuDB: opening database at %s%n", java.nio.file.Path.of(dbPath).toAbsolutePath().normalize());
        this.database = new Database(dbPath);
        this.connection = new Connection(database);
    }

    @Override
    public void initSchema() {
        // Create node tables for each type
        for (NodeType type : NodeType.values()) {
            String tableName = nodeTypeToTable(type);
            try {
                connection.query(String.format(
                        "CREATE NODE TABLE IF NOT EXISTS %s (id STRING, name STRING, qualifiedName STRING, " +
                                "visibility STRING, isAbstract BOOLEAN, isStatic BOOLEAN, isFinal BOOLEAN, " +
                                "returnType STRING, lineNumber INT64, type STRING, external BOOLEAN, " +
                                "PRIMARY KEY (id))",
                        tableName));
            } catch (Exception e) {
                System.err.printf("Warning: Could not create table %s: %s%n", tableName, e.getMessage());
            }
        }

        // Create relationship tables
        String[] relDefs = {
                "CREATE REL TABLE IF NOT EXISTS CONTAINS (FROM Package TO Class, FROM Package TO Interface, FROM Package TO Enum, FROM Package TO Record, FROM Class TO Method, FROM Class TO Constructor, FROM Class TO Field, FROM Interface TO Method, FROM Interface TO Field)",
                "CREATE REL TABLE IF NOT EXISTS EXTENDS (FROM Class TO Class, FROM Interface TO Interface)",
                "CREATE REL TABLE IF NOT EXISTS IMPLEMENTS (FROM Class TO Interface)",
                "CREATE REL TABLE IF NOT EXISTS CALLS (FROM Method TO Method, FROM Constructor TO Method, lineNumber INT64, resolved BOOLEAN)",
                "CREATE REL TABLE IF NOT EXISTS RETURNS (FROM Method TO Class, FROM Method TO Interface, FROM Method TO Enum, FROM Method TO Record)",
                "CREATE REL TABLE IF NOT EXISTS HAS_PARAMETER (FROM Method TO Parameter, FROM Constructor TO Parameter)",
                "CREATE REL TABLE IF NOT EXISTS OF_TYPE (FROM Field TO Class, FROM Field TO Interface, FROM Field TO Enum, FROM Field TO Record, FROM Parameter TO Class, FROM Parameter TO Interface, FROM Parameter TO Enum, FROM Parameter TO Record)",
                "CREATE REL TABLE IF NOT EXISTS HAS_ANNOTATION (FROM Class TO AnnotationType, FROM Interface TO AnnotationType, FROM Method TO AnnotationType, FROM Constructor TO AnnotationType, FROM Field TO AnnotationType)",
                "CREATE REL TABLE IF NOT EXISTS OVERRIDES (FROM Method TO Method)",
                "CREATE REL TABLE IF NOT EXISTS THROWS (FROM Method TO Class)",
                // CPG-specific relationship tables
                "CREATE REL TABLE IF NOT EXISTS SOURCE_FILE (FROM Class TO File, FROM Interface TO File, FROM Enum TO File, FROM Record TO File, FROM AnnotationType TO File)",
                "CREATE REL TABLE IF NOT EXISTS AST_CHILD (FROM Method TO Statement, FROM Constructor TO Statement, FROM Statement TO Statement, order INT64)",
                "CREATE REL TABLE IF NOT EXISTS CFG_NEXT (FROM Method TO Statement, FROM Constructor TO Statement, FROM Statement TO Statement, backEdge BOOLEAN)",
                "CREATE REL TABLE IF NOT EXISTS DATA_FLOW (FROM Statement TO Statement, variable STRING)"
        };

        for (String relDef : relDefs) {
            try {
                connection.query(relDef);
            } catch (Exception e) {
                System.err.printf("Warning: Could not create relationship: %s%n", e.getMessage());
            }
        }
    }

    @Override
    public void clear() {
        // Drop and recreate is the simplest approach for KuzuDB
        for (String relType : new String[]{"CONTAINS", "EXTENDS", "IMPLEMENTS", "CALLS", "RETURNS",
                "HAS_PARAMETER", "OF_TYPE", "HAS_ANNOTATION", "OVERRIDES", "THROWS",
                "SOURCE_FILE", "AST_CHILD", "CFG_NEXT", "DATA_FLOW"}) {
            try {
                connection.query("DROP TABLE " + relType);
            } catch (Exception ignored) {
            }
        }
        for (NodeType type : NodeType.values()) {
            try {
                connection.query("DROP TABLE " + nodeTypeToTable(type));
            } catch (Exception ignored) {
            }
        }
        initSchema();
    }

    @Override
    public void save(CodeGraph graph) {
        // Insert nodes
        for (CodeNode node : graph.getNodes().values()) {
            String table = nodeTypeToTable(node.type());
            try {
                String cypher = String.format(
                        "CREATE (n:%s {id: '%s', name: '%s', qualifiedName: '%s'",
                        table,
                        escapeString(node.id()),
                        escapeString(node.name()),
                        escapeString(node.qualifiedName()));

                StringBuilder props = new StringBuilder();
                for (Map.Entry<String, Object> entry : node.properties().entrySet()) {
                    props.append(", ").append(entry.getKey()).append(": ");
                    props.append(formatValue(entry.getValue()));
                }

                cypher += props + "})";
                connection.query(cypher);
            } catch (Exception e) {
                System.err.printf("Warning: Could not insert node %s: %s%n", node.id(), e.getMessage());
            }
        }

        // Insert relationships
        for (CodeRelationship rel : graph.getRelationships()) {
            try {
                String sourceTable = getTableForNodeId(rel.sourceId(), graph);
                String targetTable = getTableForNodeId(rel.targetId(), graph);
                if (sourceTable == null || targetTable == null) continue;

                String relProps = "";
                if (!rel.properties().isEmpty()) {
                    relProps = " {" + rel.properties().entrySet().stream()
                            .map(e -> e.getKey() + ": " + formatValue(e.getValue()))
                            .collect(Collectors.joining(", ")) + "}";
                }

                String cypher = String.format(
                        "MATCH (a:%s {id: '%s'}), (b:%s {id: '%s'}) CREATE (a)-[:%s%s]->(b)",
                        sourceTable, escapeString(rel.sourceId()),
                        targetTable, escapeString(rel.targetId()),
                        rel.type().name(), relProps);

                connection.query(cypher);
            } catch (Exception e) {
                // Some relationships may fail if the schema doesn't support that combination
            }
        }
    }

    @Override
    public String summary() {
        long totalNodes = 0;

        for (NodeType type : NodeType.values()) {
            try {
                var result = connection.query(
                        String.format("MATCH (n:%s) RETURN count(n) AS c", nodeTypeToTable(type)));
                if (result.hasNext()) {
                    FlatTuple tuple = result.getNext();
                    totalNodes += tuple.getValue(0).<Long>getValue();
                }
                result.close();
            } catch (Exception ignored) {
            }
        }

        // Count CALLS relationships
        long callsCount = 0;
        try {
            var result = connection.query("MATCH ()-[r:CALLS]->() RETURN count(r) AS c");
            if (result.hasNext()) {
                FlatTuple tuple = result.getNext();
                callsCount = tuple.getValue(0).<Long>getValue();
            }
            result.close();
        } catch (Exception ignored) {
        }

        return String.format("KuzuDB graph: %d nodes, %d CALLS edges", totalNodes, callsCount);
    }

    @Override
    public void close() throws Exception {
        connection.close();
        database.close();
    }

    private String getTableForNodeId(String nodeId, CodeGraph graph) {
        CodeNode node = graph.getNode(nodeId);
        if (node == null) return null;
        return nodeTypeToTable(node.type());
    }

    private static String nodeTypeToTable(NodeType type) {
        return switch (type) {
            case PACKAGE -> "Package";
            case CLASS -> "Class";
            case INTERFACE -> "Interface";
            case ENUM -> "Enum";
            case RECORD -> "Record";
            case ANNOTATION_TYPE -> "AnnotationType";
            case METHOD -> "Method";
            case CONSTRUCTOR -> "Constructor";
            case FIELD -> "Field";
            case PARAMETER -> "Parameter";
            case FILE -> "File";
            case STATEMENT -> "Statement";
        };
    }

    private static String escapeString(String value) {
        if (value == null) return "";
        // Escape backslashes first, then single quotes
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String formatValue(Object value) {
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return value.toString();
        return "'" + escapeString(String.valueOf(value)) + "'";
    }
}
