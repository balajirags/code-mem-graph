# code-graph

A CLI tool that parses Java source code into an AST using [JavaParser](https://javaparser.org/) and builds a knowledge graph in [Neo4j](https://neo4j.com/) or [KuzuDB](https://kuzudb.com/).

## What it extracts

### Knowledge graph (default command)

- **Nodes**: packages, classes, interfaces, enums, records, methods, constructors, fields (including record components), parameters, annotation types
- **Relationships**: CONTAINS, EXTENDS, IMPLEMENTS, CALLS, RETURNS, HAS_PARAMETER, OF_TYPE, HAS_ANNOTATION, OVERRIDES, THROWS

### Code Property Graph (`cpg` subcommand)

Everything above, plus:

- **Nodes**: files, statements (IF, FOR, FOREACH, WHILE, DO_WHILE, RETURN, THROW, TRY, SWITCH, EXPRESSION, etc.)
- **Relationships**: SOURCE_FILE (type → file), AST_CHILD (method → statements), CFG_NEXT (control flow between statements), DATA_FLOW (variable definition → use)

## Prerequisites

- **Java 21+**
- One of:
  - **KuzuDB** — embedded, no setup needed (default backend)
  - **Neo4j** — requires a running Neo4j instance

## Build

```bash
./gradlew build
```

### Standalone fat JAR

Build a self-contained JAR with all dependencies bundled:

```bash
./gradlew shadowJar
```

This produces `app/build/libs/code-graph.jar` (~110 MB) that can be run anywhere Java 21+ is installed:

```bash
java -jar app/build/libs/code-graph.jar /path/to/java/project
```

## Run

### Using KuzuDB (default — no server required)

```bash
./gradlew run --args="/path/to/java/project"
```

The path should point to the **Java project root directory**. The tool automatically detects the source root by looking for `src/main/java`, then `src`, and falls back to the given directory.

This creates a `./code-graph-db` directory with the embedded KuzuDB database.

### Using Neo4j

Start Neo4j first (e.g. via Docker):

```bash
docker run -d --name neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/changeme \
  neo4j:5
```

Then run:

```bash
./gradlew run --args="/path/to/java/project -b neo4j --neo4j-password changeme"
```

### Incremental parsing

Use `--incremental` to only re-parse files that have changed since the last run. File hashes (SHA-256) are stored in `.code-graph-hashes` inside the project root:

```bash
./gradlew run --args="/path/to/java/project --incremental"
```

On subsequent runs, unchanged files are skipped, making re-analysis of large codebases significantly faster.

### Exporting to file (no database required)

Use the `export` subcommand to export the parsed graph directly to DOT (Graphviz), GraphML, or JSON format:

```bash
# Export as JSON to stdout
./gradlew run --args="export /path/to/java/project"

# Export as DOT to a file
./gradlew run --args="export /path/to/java/project -f dot -o graph.dot"

# Export as GraphML
./gradlew run --args="export /path/to/java/project -f graphml -o graph.graphml"

# Using the fat JAR
java -jar app/build/libs/code-graph.jar export /path/to/project -f json -o graph.json
```

Supported formats:
| Format | Flag | Description |
|--------|------|-------------|
| JSON | `json` | Default. Nodes and relationships as JSON arrays. |
| DOT | `dot` | Graphviz DOT format. Render with `dot -Tpng graph.dot -o graph.png`. |
| GraphML | `graphml` | XML-based graph format. Import into yEd, Gephi, Cytoscape, etc. |


### Code Property Graph

Use the `cpg` subcommand to build a Code Property Graph that includes AST structure, control flow, and data flow edges in addition to the structural knowledge graph:

```bash
# CPG with KuzuDB (default)
./gradlew run --args="cpg /path/to/java/project"

# CPG with Neo4j
./gradlew run --args="cpg /path/to/java/project -b neo4j --neo4j-password changeme"

# Incremental CPG
./gradlew run --args="cpg /path/to/java/project --incremental"

# Using the fat JAR
java -jar app/build/libs/code-graph.jar cpg /path/to/project
```

The CPG database path uses a `-cpg-db` suffix by default (e.g., `./inventory-service-cpg-db`).

### MCP Server (REST API)

Use the `mcp-server` subcommand to start a lightweight REST server that exposes the knowledge graph stored in KuzuDB or Neo4j. This allows remote tools to query the graph via HTTP.

```bash
# Start MCP server with KuzuDB (default)
java -jar app/build/libs/code-graph.jar mcp-server

# Start MCP server with Neo4j
java -jar app/build/libs/code-graph.jar mcp-server -b neo4j --neo4j-password changeme

# Specify port (default: 7070)
java -jar app/build/libs/code-graph.jar mcp-server --port 8080
```

Endpoints:

- `GET  /health` — Health check (returns OK)
- `POST /query`  — (WIP) Accepts a Cypher query in the request body and returns results (to be implemented)

The server connects to the same database as the CLI, using the same backend and connection options.

### CLI options

```
Usage: code-graph [-hV] [--incremental] [-b=<backend>]
                  [-c=<configPath>] [--cp=<classpath>]
                  [--kuzu-db=<kuzuDbPath>] [--neo4j-database=<neo4jDatabase>]
                  [--neo4j-password=<neo4jPassword>] [--neo4j-uri=<neo4jUri>]
                  [--neo4j-user=<neo4jUser>] <sourcePath> [COMMAND]

      <sourcePath>             Path to the Java project root directory
                               (auto-detects src/main/java or src)
  -b, --backend=<backend>      Graph backend: neo4j or kuzu
  -c, --config=<configPath>    Path to config file (default: ./application.yaml)
      --incremental            Only re-parse changed files; keep existing graph data
      --cp, --classpath=...    Additional classpath entries (comma-separated)
      --kuzu-db=<kuzuDbPath>   KuzuDB database directory
      --neo4j-uri=<neo4jUri>   Neo4j connection URI
      --neo4j-user=<neo4jUser> Neo4j username
      --neo4j-password=...     Neo4j password
      --neo4j-database=...     Neo4j database name
  -h, --help                   Show help
  -V, --version                Print version

Commands:
  cpg     Build a Code Property Graph (AST + CFG + data flow)
  export  Export graph to DOT, GraphML, or JSON (no database needed)
```

By default, each run **replaces** the existing graph data. Use `--incremental` to keep existing data and only add/update changed files.

The database name is derived from the input project directory (e.g., `inventory-service` → Neo4j database `inventory-service`, KuzuDB path `./inventory-service-db`). Override with `--neo4j-database` or `--kuzu-db`.

## Configuration file

Create an `application.yaml` file (loaded automatically from the current directory, or specify with `-c`):

```yaml
backend: kuzu

neo4j:
  uri: bolt://localhost:7687
  username: neo4j
  password: changeme
  database: neo4j

kuzu:
  db-path: ./code-graph-db
```

CLI flags override values from the config file.

## Viewing the graph

### KuzuDB Explorer (Docker)

```bash
docker run -p 8000:8000 \
  -v $(pwd)/code-graph-db:/database \
  --rm kuzudb/explorer:latest
```

Open http://localhost:8000 and run Cypher queries.

### KuzuDB CLI

```bash
brew install kuzu
kuzu ./code-graph-db
```

### Neo4j Browser

Open http://localhost:7474 when using the Neo4j backend.

## Example queries

```cypher
-- show full graph snapshot
MATCH (n)-[r]->(m) RETURN n, r, m

-- All classes and their methods
MATCH (c:Class)-[:CONTAINS]->(m:Method) RETURN c.name, m.name

-- Inheritance hierarchy
MATCH (c:Class)-[:EXTENDS]->(p:Class) RETURN c.name, p.name

-- Method call graph
MATCH (a:Method)-[:CALLS]->(b:Method) RETURN a.qualifiedName, b.qualifiedName

-- Classes implementing an interface
MATCH (c:Class)-[:IMPLEMENTS]->(i:Interface) RETURN c.name, i.name

-- Methods with annotations
MATCH (m:Method)-[:HAS_ANNOTATION]->(a:AnnotationType) RETURN m.name, a.name

-- View all nodes and edges --
MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 300

-- CPG: Control flow graph for a method
MATCH (m:Method {name: 'compute'})-[:AST_CHILD]->(s:Statement)
RETURN s.statementType, s.code, s.lineNumber ORDER BY s.lineNumber

-- CPG: Control flow path
MATCH (s1:Statement)-[:CFG_NEXT]->(s2:Statement)
RETURN s1.code, s2.code, s1.lineNumber, s2.lineNumber

-- CPG: Data flow for a variable
MATCH (s1:Statement)-[d:DATA_FLOW]->(s2:Statement)
WHERE d.variable = 'result'
RETURN s1.code, s2.code

-- CPG: Which file defines a class
MATCH (c:Class)-[:SOURCE_FILE]->(f:File) RETURN c.name, f.name
```

## Project structure

```
app/src/main/java/code/graph/
├── App.java                    # CLI entry point (picocli)
├── CpgCommand.java             # CPG subcommand (AST + CFG + data flow)
├── ExportCommand.java          # Export subcommand (DOT/GraphML/JSON)
├── config/
│   └── AppConfig.java          # Properties file loader
├── export/
│   ├── GraphExporter.java      # Exporter interface
│   ├── DotExporter.java        # Graphviz DOT exporter
│   ├── GraphMlExporter.java    # GraphML XML exporter
│   └── JsonExporter.java       # JSON exporter
├── model/
│   ├── CodeGraph.java          # In-memory graph container
│   ├── CodeNode.java           # Graph node record
│   ├── CodeRelationship.java   # Graph edge record
│   ├── NodeType.java           # Node type enum
│   └── RelationshipType.java   # Relationship type enum
├── parser/
│   ├── CpgParser.java          # Code Property Graph parser (AST + CFG + data flow)
│   ├── FileHashCache.java      # Incremental parsing hash tracker
│   └── JavaSourceParser.java   # JavaParser AST visitor
└── store/
    ├── GraphStore.java         # Graph store interface
    ├── KuzuGraphStore.java     # KuzuDB implementation
    └── Neo4jGraphStore.java    # Neo4j implementation
```

## Tech stack

| Component | Library |
|-----------|---------|
| AST parsing | JavaParser 3.26.4 (with symbol solver) |
| CLI | picocli 4.7.6 |
| Neo4j | Neo4j Java Driver 5.28.4 |
| KuzuDB | KuzuDB Java 0.10.0 |
| Build | Gradle 9.2 / Java 21 |
| Fat JAR | Shadow 9.0.0-beta12 |
