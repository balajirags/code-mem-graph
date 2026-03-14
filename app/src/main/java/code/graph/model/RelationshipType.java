package code.graph.model;

public enum RelationshipType {
    CONTAINS,           // Package -> Class, Class -> Method/Field
    EXTENDS,            // Class -> Class
    IMPLEMENTS,         // Class -> Interface
    CALLS,              // Method -> Method
    RETURNS,            // Method -> Type
    HAS_PARAMETER,      // Method -> Parameter
    OF_TYPE,            // Field/Parameter -> Type
    HAS_ANNOTATION,     // Any -> Annotation
    OVERRIDES,          // Method -> Method
    THROWS,             // Method -> Exception type
    // CPG-specific
    SOURCE_FILE,        // Type -> File it is defined in
    AST_CHILD,          // Parent -> Child in syntax tree
    CFG_NEXT,           // Statement -> Next statement (control flow)
    DATA_FLOW           // Variable definition -> use
}
