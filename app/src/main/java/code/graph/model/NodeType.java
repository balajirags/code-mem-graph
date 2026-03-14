package code.graph.model;

public enum NodeType {
    PACKAGE,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION_TYPE,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    PARAMETER,
    // CPG-specific
    FILE,
    STATEMENT
}
