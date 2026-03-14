package code.graph.parser;

import code.graph.model.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Parses Java source files using JavaParser and extracts a CodeGraph.
 */
public class JavaSourceParser {

    private final JavaParser parser;
    private int resolvedCalls = 0;
    private int unresolvedCalls = 0;
    private FileHashCache hashCache;

    public JavaSourceParser(Path sourceRoot) {
        this(sourceRoot, List.of());
    }

    public JavaSourceParser(Path sourceRoot, List<Path> classpathEntries) {
        this(sourceRoot, classpathEntries, null);
    }

    public JavaSourceParser(Path sourceRoot, List<Path> classpathEntries, FileHashCache hashCache) {
        this.hashCache = hashCache;
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(sourceRoot));

        // Add dependency JARs to the type solver
        int jarCount = 0;
        for (Path entry : classpathEntries) {
            try {
                if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                    typeSolver.add(new JarTypeSolver(entry));
                    jarCount++;
                } else if (Files.isDirectory(entry)) {
                    // Scan directory for JARs
                    try (var jars = Files.list(entry)) {
                        for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                            typeSolver.add(new JarTypeSolver(jar));
                            jarCount++;
                        }
                    }
                }
            } catch (IOException e) {
                System.err.printf("Warning: Could not add classpath entry %s: %s%n", entry, e.getMessage());
            }
        }
        if (jarCount > 0) {
            System.out.printf("Added %d JARs to type solver%n", jarCount);
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(config);
    }

    /**
     * Parse all .java files under the given root directory.
     */
    public CodeGraph parseDirectory(Path root) throws IOException {
        CodeGraph graph = new CodeGraph();
        List<Path> javaFiles = collectJavaFiles(root);

        System.out.printf("Found %d Java files to parse%n", javaFiles.size());

        int skipped = 0;
        for (Path file : javaFiles) {
            if (hashCache != null && !hashCache.hasChanged(file)) {
                skipped++;
                continue;
            }
            parseFile(file, graph);
        }

        if (skipped > 0) {
            System.out.printf("Incremental: skipped %d unchanged files, parsed %d%n",
                    skipped, javaFiles.size() - skipped);
        }

        System.out.printf("Method calls: %d resolved, %d best-effort (unresolved)%n",
                resolvedCalls, unresolvedCalls);

        return graph;
    }

    private List<Path> collectJavaFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private void parseFile(Path file, CodeGraph graph) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();
                cu.accept(new CodeGraphVisitor(graph), null);
            } else {
                System.err.printf("Failed to parse %s: %s%n", file,
                        result.getProblems().stream()
                                .map(Object::toString)
                                .collect(Collectors.joining(", ")));
            }
        } catch (IOException e) {
            System.err.printf("Error reading %s: %s%n", file, e.getMessage());
        }
    }

    /**
     * AST visitor that extracts nodes and relationships into a CodeGraph.
     */
    private class CodeGraphVisitor extends VoidVisitorAdapter<Void> {

        private final CodeGraph graph;

        CodeGraphVisitor(CodeGraph graph) {
            this.graph = graph;
        }

        @Override
        public void visit(CompilationUnit cu, Void arg) {
            // Create package node
            cu.getPackageDeclaration().ifPresent(pkg -> {
                String pkgName = pkg.getNameAsString();
                String pkgId = "pkg:" + pkgName;
                if (!graph.hasNode(pkgId)) {
                    graph.addNode(new CodeNode(pkgId, NodeType.PACKAGE, pkgName, pkgName));
                }
            });
            super.visit(cu, arg);
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration decl, Void arg) {
            NodeType nodeType = decl.isInterface() ? NodeType.INTERFACE : NodeType.CLASS;
            String qualifiedName = resolveQualifiedName(decl);
            String nodeId = "type:" + qualifiedName;

            CodeNode node = new CodeNode(nodeId, nodeType, decl.getNameAsString(), qualifiedName)
                    .withProperty("isAbstract", decl.isAbstract())
                    .withProperty("isStatic", decl.isStatic())
                    .withProperty("visibility", decl.getAccessSpecifier().asString());
            graph.addNode(node);

            // Link to package
            decl.findCompilationUnit().flatMap(cu -> cu.getPackageDeclaration()).ifPresent(pkg -> {
                String pkgId = "pkg:" + pkg.getNameAsString();
                graph.addRelationship(new CodeRelationship(pkgId, nodeId, RelationshipType.CONTAINS));
            });

            // Extends
            for (ClassOrInterfaceType extended : decl.getExtendedTypes()) {
                String parentName = resolveTypeName(extended);
                String parentId = "type:" + parentName;
                ensureTypeNode(parentId, parentName);
                graph.addRelationship(new CodeRelationship(nodeId, parentId, RelationshipType.EXTENDS));
            }

            // Implements
            for (ClassOrInterfaceType implemented : decl.getImplementedTypes()) {
                String ifaceName = resolveTypeName(implemented);
                String ifaceId = "type:" + ifaceName;
                ensureTypeNode(ifaceId, ifaceName);
                graph.addRelationship(new CodeRelationship(nodeId, ifaceId, RelationshipType.IMPLEMENTS));
            }

            // Annotations
            visitAnnotations(decl.getAnnotations(), nodeId);

            super.visit(decl, arg);
        }

        @Override
        public void visit(EnumDeclaration decl, Void arg) {
            String qualifiedName = resolveQualifiedName(decl);
            String nodeId = "type:" + qualifiedName;

            CodeNode node = new CodeNode(nodeId, NodeType.ENUM, decl.getNameAsString(), qualifiedName)
                    .withProperty("visibility", decl.getAccessSpecifier().asString());
            graph.addNode(node);

            // Link to package
            decl.findCompilationUnit().flatMap(cu -> cu.getPackageDeclaration()).ifPresent(pkg -> {
                String pkgId = "pkg:" + pkg.getNameAsString();
                graph.addRelationship(new CodeRelationship(pkgId, nodeId, RelationshipType.CONTAINS));
            });

            visitAnnotations(decl.getAnnotations(), nodeId);
            super.visit(decl, arg);
        }

        @Override
        public void visit(RecordDeclaration decl, Void arg) {
            String qualifiedName = resolveQualifiedName(decl);
            String nodeId = "type:" + qualifiedName;

            CodeNode node = new CodeNode(nodeId, NodeType.RECORD, decl.getNameAsString(), qualifiedName)
                    .withProperty("visibility", decl.getAccessSpecifier().asString());
            graph.addNode(node);

            // Link to package
            decl.findCompilationUnit().flatMap(cu -> cu.getPackageDeclaration()).ifPresent(pkg -> {
                String pkgId = "pkg:" + pkg.getNameAsString();
                graph.addRelationship(new CodeRelationship(pkgId, nodeId, RelationshipType.CONTAINS));
            });

            // Record components → FIELD nodes
            for (Parameter param : decl.getParameters()) {
                String fieldId = "field:" + qualifiedName + "." + param.getNameAsString();
                CodeNode fieldNode = new CodeNode(fieldId, NodeType.FIELD, param.getNameAsString(),
                        qualifiedName + "." + param.getNameAsString())
                        .withProperty("type", param.getTypeAsString())
                        .withProperty("visibility", "public")
                        .withProperty("isFinal", true)
                        .withProperty("isRecordComponent", true);
                graph.addNode(fieldNode);
                graph.addRelationship(new CodeRelationship(nodeId, fieldId, RelationshipType.CONTAINS));

                // Field type reference
                String fieldTypeName = param.getTypeAsString();
                String fieldTypeId = "type:" + fieldTypeName;
                ensureTypeNode(fieldTypeId, fieldTypeName);
                graph.addRelationship(new CodeRelationship(fieldId, fieldTypeId, RelationshipType.OF_TYPE));
            }

            visitAnnotations(decl.getAnnotations(), nodeId);
            super.visit(decl, arg);
        }

        @Override
        public void visit(MethodDeclaration decl, Void arg) {
            String methodSig = buildMethodSignature(decl);
            String containingType = findContainingType(decl);
            String methodId = "method:" + containingType + "." + methodSig;
            String typeId = "type:" + containingType;

            CodeNode node = new CodeNode(methodId, NodeType.METHOD, decl.getNameAsString(), containingType + "." + methodSig)
                    .withProperty("visibility", decl.getAccessSpecifier().asString())
                    .withProperty("isAbstract", decl.isAbstract())
                    .withProperty("isStatic", decl.isStatic())
                    .withProperty("returnType", decl.getTypeAsString())
                    .withProperty("lineNumber", decl.getBegin().map(p -> p.line).orElse(-1));
            graph.addNode(node);

            // CONTAINS: Type -> Method
            graph.addRelationship(new CodeRelationship(typeId, methodId, RelationshipType.CONTAINS));

            // Return type
            String returnTypeName = decl.getTypeAsString();
            if (!"void".equals(returnTypeName)) {
                String returnTypeId = "type:" + returnTypeName;
                ensureTypeNode(returnTypeId, returnTypeName);
                graph.addRelationship(new CodeRelationship(methodId, returnTypeId, RelationshipType.RETURNS));
            }

            // Parameters
            for (Parameter param : decl.getParameters()) {
                String paramId = methodId + ".param:" + param.getNameAsString();
                CodeNode paramNode = new CodeNode(paramId, NodeType.PARAMETER, param.getNameAsString(),
                        containingType + "." + methodSig + "." + param.getNameAsString())
                        .withProperty("type", param.getTypeAsString());
                graph.addNode(paramNode);
                graph.addRelationship(new CodeRelationship(methodId, paramId, RelationshipType.HAS_PARAMETER));

                // Parameter type reference
                String paramTypeName = param.getTypeAsString();
                String paramTypeId = "type:" + paramTypeName;
                ensureTypeNode(paramTypeId, paramTypeName);
                graph.addRelationship(new CodeRelationship(paramId, paramTypeId, RelationshipType.OF_TYPE));
            }

            // Thrown exceptions
            decl.getThrownExceptions().forEach(ex -> {
                String exName = ex.asString();
                String exId = "type:" + exName;
                ensureTypeNode(exId, exName);
                graph.addRelationship(new CodeRelationship(methodId, exId, RelationshipType.THROWS));
            });

            // Annotations
            visitAnnotations(decl.getAnnotations(), methodId);

            // Detect OVERRIDES — check if this method overrides a parent method
            detectOverrides(decl, methodId, containingType);

            super.visit(decl, arg);
        }

        @Override
        public void visit(ConstructorDeclaration decl, Void arg) {
            String containingType = findContainingType(decl);
            String ctorSig = decl.getNameAsString() + "(" +
                    decl.getParameters().stream()
                            .map(p -> p.getTypeAsString())
                            .collect(Collectors.joining(",")) + ")";
            String ctorId = "ctor:" + containingType + "." + ctorSig;
            String typeId = "type:" + containingType;

            CodeNode node = new CodeNode(ctorId, NodeType.CONSTRUCTOR, decl.getNameAsString(),
                    containingType + "." + ctorSig)
                    .withProperty("visibility", decl.getAccessSpecifier().asString())
                    .withProperty("lineNumber", decl.getBegin().map(p -> p.line).orElse(-1));
            graph.addNode(node);

            graph.addRelationship(new CodeRelationship(typeId, ctorId, RelationshipType.CONTAINS));

            visitAnnotations(decl.getAnnotations(), ctorId);
            super.visit(decl, arg);
        }

        @Override
        public void visit(FieldDeclaration decl, Void arg) {
            String containingType = findContainingType(decl);
            for (VariableDeclarator var : decl.getVariables()) {
                String fieldId = "field:" + containingType + "." + var.getNameAsString();
                String typeId = "type:" + containingType;

                CodeNode node = new CodeNode(fieldId, NodeType.FIELD, var.getNameAsString(),
                        containingType + "." + var.getNameAsString())
                        .withProperty("type", var.getTypeAsString())
                        .withProperty("visibility", decl.getAccessSpecifier().asString())
                        .withProperty("isStatic", decl.isStatic())
                        .withProperty("isFinal", decl.isFinal());
                graph.addNode(node);

                graph.addRelationship(new CodeRelationship(typeId, fieldId, RelationshipType.CONTAINS));

                // Field type reference
                String fieldTypeName = var.getTypeAsString();
                String fieldTypeId = "type:" + fieldTypeName;
                ensureTypeNode(fieldTypeId, fieldTypeName);
                graph.addRelationship(new CodeRelationship(fieldId, fieldTypeId, RelationshipType.OF_TYPE));
            }

            visitAnnotations(decl.getAnnotations(), "field:" + containingType);
            super.visit(decl, arg);
        }

        @Override
        public void visit(MethodCallExpr call, Void arg) {
            // Find the enclosing method — skip calls outside methods (e.g. field initializers)
            var enclosingMethod = call.findAncestor(MethodDeclaration.class);
            if (enclosingMethod.isEmpty()) {
                super.visit(call, arg);
                return;
            }

            String enclosingType = findContainingType(enclosingMethod.get());
            String enclosingSig = buildMethodSignature(enclosingMethod.get());
            String callerId = "method:" + enclosingType + "." + enclosingSig;
            int line = call.getBegin().map(p -> p.line).orElse(-1);

            // Try full symbol resolution first
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                String targetQualified = resolved.getQualifiedName();
                String targetSig = resolved.getName() + "(" +
                        java.util.stream.IntStream.range(0, resolved.getNumberOfParams())
                                .mapToObj(i -> resolved.getParam(i).describeType())
                                .collect(Collectors.joining(",")) + ")";
                String targetId = "method:" + targetQualified.substring(0, targetQualified.lastIndexOf('.')) + "." + targetSig;

                graph.addRelationship(new CodeRelationship(callerId, targetId, RelationshipType.CALLS)
                        .withProperty("lineNumber", line)
                        .withProperty("resolved", true));
                resolvedCalls++;
            } catch (Exception e) {
                // Best-effort: infer target from scope expression and method name
                bestEffortCall(call, callerId, line);
            }

            super.visit(call, arg);
        }

        /**
         * When full resolution fails, infer the target method from the scope's declared type.
         * For example: inventoryService.findAll() → look up the field "inventoryService",
         * get its declared type, and create an unresolved CALLS edge.
         */
        private void bestEffortCall(MethodCallExpr call, String callerId, int line) {
            String methodName = call.getNameAsString();
            String scopeType = null;

            if (call.getScope().isPresent()) {
                var scope = call.getScope().get();

                // Try to resolve the scope expression's type
                try {
                    var resolvedType = scope.calculateResolvedType();
                    if (resolvedType.isReferenceType()) {
                        scopeType = resolvedType.asReferenceType().getQualifiedName();
                    }
                } catch (Exception ignored) {
                }

                // Fallback: if scope is a simple name like "inventoryService", look up the field type
                if (scopeType == null && scope instanceof NameExpr nameExpr) {
                    scopeType = resolveFieldType(nameExpr.getNameAsString(), call);
                }

                // Last resort: use the scope text as-is (e.g. "inventoryService")
                if (scopeType == null) {
                    scopeType = scope.toString();
                }
            } else {
                // No scope means it's a call to a method in the same class
                var enclosingType = call.findAncestor(TypeDeclaration.class);
                if (enclosingType.isPresent()) {
                    try {
                        scopeType = ((TypeDeclaration<?>) enclosingType.get())
                                .getFullyQualifiedName()
                                .orElse(((TypeDeclaration<?>) enclosingType.get()).getNameAsString());
                    } catch (Exception ex) {
                        scopeType = ((TypeDeclaration<?>) enclosingType.get()).getNameAsString();
                    }
                }
            }

            if (scopeType != null) {
                String args = call.getArguments().stream()
                        .map(a -> "?")
                        .collect(Collectors.joining(","));
                String targetId = "method:" + scopeType + "." + methodName + "(" + args + ")";

                ensureTypeNode("type:" + scopeType, scopeType);
                if (!graph.hasNode(targetId)) {
                    graph.addNode(new CodeNode(targetId, NodeType.METHOD, methodName,
                            scopeType + "." + methodName)
                            .withProperty("external", true));
                    graph.addRelationship(new CodeRelationship(
                            "type:" + scopeType, targetId, RelationshipType.CONTAINS));
                }

                graph.addRelationship(new CodeRelationship(callerId, targetId, RelationshipType.CALLS)
                        .withProperty("lineNumber", line)
                        .withProperty("resolved", false));
                unresolvedCalls++;
            }
        }

        /**
         * Try to find the declared type of a field by name in the enclosing class.
         */
        private String resolveFieldType(String fieldName, MethodCallExpr call) {
            return call.findAncestor(TypeDeclaration.class)
                    .flatMap(td -> ((TypeDeclaration<?>) td).getFields().stream()
                            .flatMap(f -> f.getVariables().stream())
                            .filter(v -> v.getNameAsString().equals(fieldName))
                            .findFirst()
                            .map(v -> {
                                // Try to resolve the field's type to a qualified name
                                try {
                                    var resolved = v.getType().resolve();
                                    if (resolved.isReferenceType()) {
                                        return resolved.asReferenceType().getQualifiedName();
                                    }
                                } catch (Exception ignored) {
                                }
                                return v.getTypeAsString();
                            }))
                    .orElse(null);
        }

        /**
         * Detect if a method overrides a parent class or interface method.
         * Walks the type hierarchy (extends + implements) to find a matching method signature.
         */
        private void detectOverrides(MethodDeclaration decl, String methodId, String containingType) {
            try {
                // Find the class/interface this method belongs to
                var enclosingType = decl.findAncestor(ClassOrInterfaceDeclaration.class);
                if (enclosingType.isEmpty()) return;

                var resolved = enclosingType.get().resolve();
                String sig = decl.getNameAsString();
                int paramCount = decl.getParameters().size();

                // Check all ancestor types (superclass + interfaces)
                for (ResolvedReferenceType ancestor : resolved.getAllAncestors()) {
                    try {
                        ResolvedReferenceTypeDeclaration ancestorDecl = ancestor.getTypeDeclaration()
                                .orElse(null);
                        if (ancestorDecl == null) continue;
                        String ancestorName = ancestorDecl.getQualifiedName();
                        // Skip java.lang.Object
                        if ("java.lang.Object".equals(ancestorName)) continue;

                        for (ResolvedMethodDeclaration ancestorMethod : ancestorDecl.getDeclaredMethods()) {
                            if (ancestorMethod.getName().equals(sig)
                                    && ancestorMethod.getNumberOfParams() == paramCount) {
                                // Build the parent method ID
                                String parentSig = ancestorMethod.getName() + "(" +
                                        java.util.stream.IntStream.range(0, ancestorMethod.getNumberOfParams())
                                                .mapToObj(i -> ancestorMethod.getParam(i).describeType())
                                                .collect(Collectors.joining(",")) + ")";
                                String parentMethodId = "method:" + ancestorName + "." + parentSig;

                                // Ensure the parent method node exists
                                if (!graph.hasNode(parentMethodId)) {
                                    ensureTypeNode("type:" + ancestorName, ancestorName);
                                    graph.addNode(new CodeNode(parentMethodId, NodeType.METHOD,
                                            ancestorMethod.getName(), ancestorName + "." + parentSig)
                                            .withProperty("external", true));
                                    graph.addRelationship(new CodeRelationship(
                                            "type:" + ancestorName, parentMethodId, RelationshipType.CONTAINS));
                                }

                                graph.addRelationship(new CodeRelationship(
                                        methodId, parentMethodId, RelationshipType.OVERRIDES)
                                        .withProperty("parentType", ancestorName));
                                return; // Only link to the closest ancestor
                            }
                        }
                    } catch (Exception ignored) {
                        // Ancestor type might not be resolvable
                    }
                }
            } catch (Exception ignored) {
                // Type resolution may fail for some classes
            }
        }

        // --- Helper methods ---

        private void visitAnnotations(List<AnnotationExpr> annotations, String targetNodeId) {
            for (AnnotationExpr ann : annotations) {
                String annName = ann.getNameAsString();
                String annId = "annotation:" + annName;
                if (!graph.hasNode(annId)) {
                    graph.addNode(new CodeNode(annId, NodeType.ANNOTATION_TYPE, annName, annName));
                }
                graph.addRelationship(new CodeRelationship(targetNodeId, annId, RelationshipType.HAS_ANNOTATION));
            }
        }

        private void ensureTypeNode(String typeId, String typeName) {
            if (!graph.hasNode(typeId)) {
                // External/unresolved type — create a placeholder node
                graph.addNode(new CodeNode(typeId, NodeType.CLASS, typeName, typeName)
                        .withProperty("external", true));
            }
        }

        private String resolveQualifiedName(TypeDeclaration<?> decl) {
            try {
                return decl.getFullyQualifiedName().orElse(decl.getNameAsString());
            } catch (Exception e) {
                return decl.getNameAsString();
            }
        }

        private String resolveTypeName(ClassOrInterfaceType type) {
            try {
                var resolved = type.resolve();
                if (resolved.isReferenceType()) {
                    return resolved.asReferenceType().getQualifiedName();
                }
                return resolved.describe();
            } catch (Exception e) {
                return type.getNameAsString();
            }
        }

        private String findContainingType(BodyDeclaration<?> decl) {
            return decl.findAncestor(TypeDeclaration.class)
                    .map(td -> {
                        try {
                            return ((TypeDeclaration<?>) td).getFullyQualifiedName()
                                    .orElse(((TypeDeclaration<?>) td).getNameAsString());
                        } catch (Exception e) {
                            return ((TypeDeclaration<?>) td).getNameAsString();
                        }
                    })
                    .orElse("unknown");
        }

        private String buildMethodSignature(MethodDeclaration decl) {
            return decl.getNameAsString() + "(" +
                    decl.getParameters().stream()
                            .map(p -> p.getTypeAsString())
                            .collect(Collectors.joining(",")) + ")";
        }

        private void visitAnnotations(com.github.javaparser.ast.NodeList<AnnotationExpr> annotations, String nodeId) {
            for (AnnotationExpr ann : annotations) {
                String annName = ann.getNameAsString();
                String annId = "annotation:" + annName;
                if (!graph.hasNode(annId)) {
                    graph.addNode(new CodeNode(annId, NodeType.ANNOTATION_TYPE, annName, annName));
                }
                graph.addRelationship(new CodeRelationship(nodeId, annId, RelationshipType.HAS_ANNOTATION));
            }
        }
    }
}
