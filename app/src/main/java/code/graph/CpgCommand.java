package code.graph;

import code.graph.config.AppConfig;
import code.graph.model.CodeGraph;
import code.graph.parser.CpgParser;
import code.graph.parser.FileHashCache;
import code.graph.store.GraphStore;
import code.graph.store.KuzuGraphStore;
import code.graph.store.Neo4jGraphStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Subcommand: build a Code Property Graph (AST + CFG + data flow)
 * and store it in Neo4j or KuzuDB.
 */
@Command(
        name = "cpg",
        description = "Parse Java source code and build a Code Property Graph (AST + CFG + data flow) in Neo4j or KuzuDB."
)
public class CpgCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to Java project root directory.")
    private Path sourcePath;

    @Option(names = {"-c", "--config"}, description = "Path to config file (default: ./application.yaml)")
    private Path configPath;

    @Option(names = {"-b", "--backend"}, description = "Graph backend: neo4j or kuzu")
    private String backend;

    @Option(names = {"--neo4j-uri"}, description = "Neo4j connection URI")
    private String neo4jUri;

    @Option(names = {"--neo4j-user"}, description = "Neo4j username")
    private String neo4jUser;

    @Option(names = {"--neo4j-password"}, description = "Neo4j password")
    private String neo4jPassword;

    @Option(names = {"--neo4j-database"}, description = "Neo4j database name")
    private String neo4jDatabase;

    @Option(names = {"--kuzu-db"}, description = "KuzuDB database directory")
    private String kuzuDbPath;

    @Option(names = {"--classpath", "--cp"}, description = "Additional classpath entries (comma-separated)")
    private String classpath;

    @Option(names = {"--incremental"}, description = "Only re-parse changed files; keep existing graph data.")
    private boolean incremental;

    @Override
    public Integer call() throws Exception {
        // Load config
        AppConfig config = AppConfig.load(configPath);
        if (backend == null) backend = config.getBackend();
        if (neo4jUri == null) neo4jUri = config.getNeo4jUri();
        if (neo4jUser == null) neo4jUser = config.getNeo4jUsername();
        if (neo4jPassword == null) neo4jPassword = config.getNeo4jPassword();
        if (neo4jDatabase == null) neo4jDatabase = config.getNeo4jDatabase();
        if (kuzuDbPath == null) kuzuDbPath = config.getKuzuDbPath();

        if (!Files.isDirectory(sourcePath)) {
            System.err.println("Error: Not a directory: " + sourcePath);
            return 1;
        }

        // Derive database name from project directory
        String projectName = sourcePath.toAbsolutePath().normalize().getFileName().toString();
        if (neo4jDatabase == null || neo4jDatabase.equals(config.getNeo4jDatabase())) {
            if ("neo4j".equals(config.getNeo4jDatabase())) {
                neo4jDatabase = projectName;
            }
        }
        if (kuzuDbPath == null || kuzuDbPath.equals(config.getKuzuDbPath())) {
            if ("./code-graph-db".equals(config.getKuzuDbPath())) {
                kuzuDbPath = "./" + projectName + "-cpg-db";
            }
        }

        // Detect modules and classpath
        List<Path> modules = App.detectModules(sourcePath);
        List<Path> classpathEntries = buildClasspath(sourcePath);

        // Incremental hash cache
        FileHashCache hashCache = null;
        if (incremental) {
            Path cacheFile = sourcePath.resolve(".code-graph-hashes");
            hashCache = new FileHashCache(cacheFile);
            System.out.println("Incremental mode: tracking file hashes in " + cacheFile);
        }

        // Parse all modules into a single CPG
        CodeGraph graph = new CodeGraph();
        for (Path module : modules) {
            Path sourceRoot = App.detectSourceRoot(module);
            System.out.println("Building CPG from: " + sourceRoot.toAbsolutePath());

            List<Path> moduleCp = new ArrayList<>(classpathEntries);
            if (!module.equals(sourcePath)) {
                moduleCp.addAll(App.buildModuleClasspath(module));
            }

            CpgParser parser = new CpgParser(sourceRoot, moduleCp, hashCache);
            CodeGraph moduleGraph = parser.parseDirectory(sourceRoot);
            graph.merge(moduleGraph);
        }

        if (hashCache != null) {
            hashCache.save();
        }

        System.out.printf("Code Property Graph: %d nodes, %d relationships%n",
                graph.nodeCount(), graph.relationshipCount());

        // Store in graph database
        try (GraphStore store = createStore()) {
            store.initSchema();

            if (!incremental) {
                System.out.println("Clearing existing graph data (use --incremental to keep)...");
                store.clear();
            }

            System.out.println("Saving CPG to " + backend + "...");
            store.save(graph);
            System.out.println(store.summary());
            System.out.println("Done.");
        }

        return 0;
    }

    private GraphStore createStore() {
        return switch (backend.toLowerCase()) {
            case "neo4j" -> new Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPassword, neo4jDatabase);
            case "kuzu" -> new KuzuGraphStore(kuzuDbPath);
            default -> throw new IllegalArgumentException("Unknown backend: " + backend + ". Use 'neo4j' or 'kuzu'.");
        };
    }

    private List<Path> buildClasspath(Path projectRoot) {
        List<Path> entries = new ArrayList<>();
        if (classpath != null && !classpath.isBlank()) {
            for (String part : classpath.split(",")) {
                Path p = Path.of(part.trim());
                if (Files.exists(p)) {
                    entries.add(p);
                } else {
                    System.err.println("Warning: Classpath entry not found: " + p);
                }
            }
        }
        entries.addAll(App.autoResolveClasspath(projectRoot));
        if (!entries.isEmpty()) {
            System.out.printf("Found %d classpath entries for symbol resolution%n", entries.size());
        }
        return entries;
    }
}
