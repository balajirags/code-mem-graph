# code-mem-graph

A CLI tool that parses source code and builds a Code Property Graph (CPG) in [Neo4j](https://neo4j.com/) or [KuzuDB](https://kuzudb.com/). Auto-detects the project language.

## Supported languages

| Language | Parser | CPG (control flow + data flow) |
|----------|--------|-------------------------------|
| Java | [JavaParser](https://javaparser.org/) | Yes |
| Kotlin | Kotlin Compiler PSI | Yes |
| JavaScript/React/TypeScript | Babel (via Node.js) + custom CFG/DFG walker | Yes |
| Python | Python `ast` module + custom CFG/DFG walker | Yes |

## What it extracts

- **Nodes**: packages, classes, interfaces, enums, records, methods, constructors, fields, parameters, annotation types, files, statements
- **Relationships**: CONTAINS, EXTENDS, IMPLEMENTS, CALLS, RETURNS, HAS_PARAMETER, OF_TYPE, HAS_ANNOTATION, OVERRIDES, THROWS, SOURCE_FILE, AST_CHILD, CFG_NEXT, DATA_FLOW
- **JS/React extras**: IMPORTS, EXPORTS, RENDERS, USES_HOOK, PROP_DEPENDENCY
- **Python extras**: DECORATES, YIELDS
- **Kotlin extras**: EXTENSION_OF, DELEGATES_TO, SEALED_SUBTYPE, COMPANION_OF, SUSPENDS

## Prerequisites

- **Java/Kotlin projects**: Java 21+
- **JavaScript/TypeScript projects**: Node.js
- **Python projects**: Python 3
- Graph backend (pick one):
  - **KuzuDB** — embedded, no setup needed (default)
  - **Neo4j** — requires a running Neo4j instance

## Install

Run the install script to build all parsers and create ready-to-run executables in `bin/`:

```bash
./install.sh
```

This will:
- Build the Java fat JAR (`cmg-java`)
- Install Node.js dependencies (`cmg-js`)
- Install Python dependencies (`cmg-python`)

Optionally add to your PATH:

```bash
export PATH="$(pwd)/bin:$PATH"
```

Then run directly:

```bash
cmg-java   /path/to/java/project
cmg-js     /path/to/react/app
cmg-python /path/to/flask/app
```

### Manual build

```bash
# Java/Kotlin only
./gradlew build

# Standalone fat JAR
./gradlew shadowJar
# produces app/build/libs/code-mem-graph.jar
```

## Run

Each language has its own runner. All runners default to **KuzuDB** and create the database at `<base-dir>/kuzu_db/<project-name>-db`.

By default `<base-dir>` is the current working directory. Set the `CMG_BIN_DIR` environment variable to override the base directory — all parsers will then create the database under `$CMG_BIN_DIR/kuzu_db/` instead:

```bash
export CMG_BIN_DIR="$(pwd)/bin"
cmg-java /path/to/project   # → bin/kuzu_db/<project-name>-db
```

### Java / Kotlin

```bash
# Via install script executable
cmg-java /path/to/java/project
cmg-java /path/to/kotlin/project

# Or via Gradle
./gradlew run --args="/path/to/java/project"

# Or via fat JAR
java -jar app/build/libs/code-mem-graph.jar /path/to/project

# Force a specific language
cmg-java /path/to/project -l kotlin
```

The tool automatically detects the source root by looking for `src/main/java`, then `src`, and falls back to the given directory.

### JavaScript / React / TypeScript

```bash
# Via install script executable
cmg-js /path/to/react/app

# Output JSON to stdout instead
cmg-js /path/to/react/app --backend json

# Custom database path
cmg-js /path/to/react/app --db-path ./kuzu_db/my-react-db

# Clear existing graph before writing
cmg-js /path/to/react/app --clear
```

### Python

```bash
# Via install script executable
cmg-python /path/to/flask/app

# Output JSON to stdout instead
cmg-python /path/to/flask/app --backend json

# Custom database path
cmg-python /path/to/flask/app --db-path ./kuzu_db/my-flask-db

# Clear existing graph before writing
cmg-python /path/to/flask/app --clear
```

### Using Neo4j (any language)

Start Neo4j first:

```bash
docker run -d --name neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/changeme \
  neo4j:5
```

Then pass `--backend neo4j` (or `-b neo4j` for Gradle):

```bash
cmg-java   /path/to/project    -b neo4j --neo4j-password changeme
cmg-js     /path/to/react/app  --backend neo4j --neo4j-password changeme
cmg-python /path/to/flask/app  --backend neo4j --neo4j-password changeme
```

### Incremental parsing (Java/Kotlin only)

Use `--incremental` to only re-parse files that have changed since the last run. File hashes (SHA-256) are stored in `.code-mem-graph-hashes` inside the project root:

```bash
cmg-java /path/to/java/project --incremental
```

### Exporting to file (no database required)

Use the `export` subcommand (Java/Kotlin) or `--backend json` (JS/Python) to export without a database:

```bash
# Java/Kotlin — DOT, GraphML, or JSON via export subcommand
cmg-java export /path/to/java/project -f dot -o graph.dot
cmg-java export /path/to/java/project -f graphml -o graph.graphml
cmg-java export /path/to/project -f json -o graph.json

# JavaScript/Python — JSON to stdout
cmg-js     /path/to/react/app  --backend json > graph.json
cmg-python /path/to/flask/app  --backend json > graph.json
```

Supported formats (export subcommand):
| Format | Flag | Description |
|--------|------|-------------|
| JSON | `json` | Default. Nodes and relationships as JSON arrays. |
| DOT | `dot` | Graphviz DOT format. Render with `dot -Tpng graph.dot -o graph.png`. |
| GraphML | `graphml` | XML-based graph format. Import into yEd, Gephi, Cytoscape, etc. |

### CLI options

**Java/Kotlin (Gradle / fat JAR):**

```
Usage: code-mem-graph [-hV] [--incremental] [-b=<backend>] [-l=<language>]
                  [-c=<configPath>] [--cp=<classpath>]
                  [--kuzu-db=<kuzuDbPath>] [--neo4j-database=<neo4jDatabase>]
                  [--neo4j-password=<neo4jPassword>] [--neo4j-uri=<neo4jUri>]
                  [--neo4j-user=<neo4jUser>] <sourcePath> [COMMAND]

      <sourcePath>             Path to the project root directory
  -l, --language=<language>    Source language: java, javascript, python, kotlin
                               (default: auto-detect)
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
  export  Export graph to DOT, GraphML, or JSON (no database needed)
```

**JavaScript / Python standalone:**

| Flag | Default | Description |
|------|---------|-------------|
| `--backend` | `kuzu` | `kuzu`, `neo4j`, or `json` |
| `--db-path` | `./kuzu_db/<repo-name>-db` | KuzuDB database directory |
| `--clear` | off | Clear existing graph before writing |
| `--extensions` | varies | Comma-separated file extensions (JS only) |
| `--neo4j-uri` | `bolt://localhost:7687` | Neo4j connection URI |
| `--neo4j-user` | `neo4j` | Neo4j username |
| `--neo4j-password` | — | Neo4j password |
| `--neo4j-database` | `neo4j` | Neo4j database name |

By default, each run **replaces** the existing graph data. Use `--incremental` (Java/Kotlin) or `--clear` (JS/Python) to control this behavior.

The database name is derived from the source directory (e.g., `inventory-service` → `./kuzu_db/inventory-service-db`). Override with `--kuzu-db` or `--db-path`.

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
  db-path: ./code-mem-graph-db
```

CLI flags override values from the config file.

## Viewing the graph

### KuzuDB Explorer (Docker)

```bash
docker run -p 8000:8000 \
  -v $(pwd)/code-mem-graph-db:/database \
  --rm kuzudb/explorer:latest
```

Open http://localhost:8000 and run Cypher queries.

### KuzuDB CLI

```bash
brew install kuzu
kuzu ./code-mem-graph-db
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

-- Method call graph (all internal calls)
MATCH (a:Method)-[:CALLS]->(b:Method) RETURN a.qualifiedName, b.qualifiedName

-- Controller → Service → Repository call chains
MATCH (c:Method)-[:CALLS]->(s:Method)-[:CALLS]->(r:Method)
WHERE c.qualifiedName CONTAINS 'Controller' 
  AND s.qualifiedName CONTAINS 'Service'
RETURN c.name AS controller, s.name AS service, r.name AS repository

-- Find all callers of a specific method
MATCH (caller:Method)-[:CALLS]->(target:Method)
WHERE target.name = 'save'
RETURN caller.qualifiedName, target.qualifiedName

-- Methods with most outgoing calls
MATCH (m:Method)-[c:CALLS]->()
RETURN m.qualifiedName, COUNT(c) AS callCount
ORDER BY callCount DESC LIMIT 10

-- Methods that are never called (dead code candidates)
MATCH (m:Method)
WHERE NOT ()-[:CALLS]->(m)
RETURN m.qualifiedName

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
install.sh                      # Build & install script — creates bin/ executables
bin/
├── cmg-java                    # Java/Kotlin runner (wraps fat JAR)
├── cmg-js                      # JavaScript/TypeScript runner (wraps Node.js parser)
└── cmg-python                  # Python runner (wraps Python parser)

app/src/main/java/code/graph/
├── App.java                    # CLI entry point — auto-detects language, builds CPG for Java
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
│   ├── CpgParser.java              # Code Property Graph parser (Java: AST + CFG + data flow)
│   ├── FileHashCache.java          # Incremental parsing hash tracker
│   ├── JavaSourceParser.java       # Java AST visitor (JavaParser)
│   ├── JavaScriptSourceParser.java # JS/React/TS parser (Babel via Node.js)
│   ├── KotlinSourceParser.java     # Kotlin parser (Kotlin Compiler PSI)
│   ├── PythonSourceParser.java     # Python parser (ast module)
│   ├── SourceParser.java           # Parser strategy interface
│   └── SourceParserFactory.java    # Language detection & parser factory
└── store/
    ├── GraphStore.java         # Graph store interface
    ├── KuzuGraphStore.java     # KuzuDB implementation
    └── Neo4jGraphStore.java    # Neo4j implementation

parsers/
├── javascript/
│   ├── parse.js                # Babel-based JS/React/TS parser (standalone)
│   ├── store.js                # KuzuDB + Neo4j graph store
│   └── package.json
└── python/
    ├── parse.py                # ast-based Python parser (standalone)
    ├── store.py                # KuzuDB + Neo4j graph store
    └── requirements.txt
```

## Tech stack

| Component | Library |
|-----------|---------|
| AST parsing (Java) | JavaParser 3.26.4 (with symbol solver) |
| AST parsing (Kotlin) | Kotlin Compiler |
| AST parsing (JS/TS) | Babel (via Node.js) |
| AST parsing (Python) | Python 3 ast module |
| CLI | picocli 4.7.6 |
| Neo4j | Neo4j Java Driver 5.28.4 |
| KuzuDB | KuzuDB 0.11.3 (Java, Node.js, Python) |
| Build | Gradle 9.2 / Java 21 |
| Fat JAR | Shadow 9.0.0-beta12 |
