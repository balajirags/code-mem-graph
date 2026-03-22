# code-mem-graph

A CLI tool that parses source code into a **Code Property Graph (CPG)** and stores it in [KuzuDB](https://kuzudb.com/) or [Neo4j](https://neo4j.com/). It comes with a built-in **MCP server** so AI assistants can query your codebase graph directly.

## What is a Code Property Graph?

A Code Property Graph combines three representations of your source code into a single queryable graph:

1. **AST (Abstract Syntax Tree)** — the structural hierarchy of your code: packages → classes → methods → statements. Every syntactic element becomes a graph node connected by `AST_CHILD` edges.

2. **CFG (Control Flow Graph)** — the execution order within each method/function. Statements are linked by `CFG_NEXT` edges, including loop back-edges, so you can trace every possible execution path.

3. **DFG (Data Flow Graph)** — how values propagate through variables. `DATA_FLOW` edges track where a variable is defined and where it is used, enabling taint analysis and dependency tracking.

Together, **AST + CFG + DFG = CPG**. This is the same foundation used by commercial static analysis tools, but code-mem-graph gives you the raw graph in an open database you can query with Cypher.

### What makes this tool unique

- **Multi-language** — one tool, same graph schema across Java, Kotlin, JavaScript/TypeScript/React, and Python.
- **Full CPG** — not just an AST dump. Every method gets statement-level control flow and data flow edges.
- **Embedded by default** — uses KuzuDB (no server setup). Just parse and query.
- **MCP-ready** — ships with an MCP server so Copilot, Claude, or any MCP client can explore your code graph out of the box.
- **Cypher queries** — the graph is stored in a real graph database. Write Cypher to find call chains, dead code, inheritance trees, or data flow paths.

### What it extracts

| Category | Details |
|----------|---------|
| **Nodes** | Package, Class, Interface, Enum, Record, Method, Constructor, Field, Parameter, File, Statement, Module, Function, Component, and more |
| **Structural edges** | CONTAINS, EXTENDS, IMPLEMENTS, OVERRIDES, HAS_PARAMETER, OF_TYPE, HAS_ANNOTATION, THROWS, SOURCE_FILE |
| **Call graph** | CALLS (with line numbers and resolution status) |
| **CPG edges** | AST_CHILD, CFG_NEXT (with back-edge flag), DATA_FLOW (with variable name) |
| **JS/React extras** | IMPORTS, EXPORTS, RENDERS, USES_HOOK, PROP_DEPENDENCY |
| **Python extras** | DECORATES, YIELDS |
| **Kotlin extras** | EXTENSION_OF, DELEGATES_TO, SEALED_SUBTYPE, COMPANION_OF, SUSPENDS |

## Supported languages

| Language | Parser | Full CPG |
|----------|--------|----------|
| Java | [JavaParser](https://javaparser.org/) (with symbol solver) | Yes |
| Kotlin | Kotlin Compiler PSI | Yes |
| JavaScript / React / TypeScript | Babel + custom CFG/DFG walker | Yes |
| Python | Python `ast` module + custom CFG/DFG walker | Yes |

## Prerequisites

| To parse… | You need… |
|-----------|-----------|
| Java / Kotlin | Java 21+ |
| JavaScript / TypeScript | Node.js |
| Python | Python 3 |

Graph backend (pick one):
- **KuzuDB** — embedded, zero setup (default)
- **Neo4j** — requires a running instance

## Install

```bash
./install.sh
```

This builds all parsers and creates executables in `bin/`:

| Executable | Purpose |
|------------|---------|
| `cmg-java` | Parse Java / Kotlin projects |
| `cmg-js` | Parse JavaScript / React / TypeScript projects |
| `cmg-python` | Parse Python projects |
| `cmg-mcp` | MCP server over the code graph |

Add to your PATH (optional):

```bash
export PATH="$(pwd)/bin:$PATH"
```

### Manual build (Java/Kotlin only)

```bash
./gradlew shadowJar
# produces app/build/libs/code-mem-graph.jar
```

## Run

Parse your project with a single command. The graph database is created automatically at `./kuzu_db/<project-name>-db`.

### Java / Kotlin

```bash
cmg-java /path/to/java/project
cmg-java /path/to/kotlin/project

# Force a specific language
cmg-java /path/to/project -l kotlin

# Incremental — only re-parse changed files
cmg-java /path/to/java/project --incremental
```

The tool auto-detects the source root by looking for `src/main/java`, then `src`, and falls back to the given directory.

### JavaScript / React / TypeScript

```bash
cmg-js /path/to/react/app

# Clear existing graph before writing
cmg-js /path/to/react/app --clear
```

### Python

```bash
cmg-python /path/to/flask/app

# Clear existing graph before writing
cmg-python /path/to/flask/app --clear
```

### Using Neo4j instead of KuzuDB

Start Neo4j:

```bash
docker run -d --name neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/changeme \
  neo4j:5
```

Then pass `--backend neo4j`:

```bash
cmg-java   /path/to/project    -b neo4j --neo4j-password changeme
cmg-js     /path/to/react/app  --backend neo4j --neo4j-password changeme
cmg-python /path/to/flask/app  --backend neo4j --neo4j-password changeme
```

## MCP Server

The built-in MCP server lets AI assistants (VS Code Copilot, Claude Desktop, etc.) query your code graph over the Model Context Protocol.

### Start

```bash
# KuzuDB (default)
cmg-mcp --db-path ./kuzu_db/my-project-db

# Neo4j
cmg-mcp --backend neo4j --neo4j-uri bolt://localhost:7687 --neo4j-password changeme
```

### Available tools

| Tool | Description |
|------|-------------|
| `query` | Run a read-only Cypher query against the code graph |
| `get_schema` | List all node types, relationship types, and properties |
| `summary` | Node and relationship counts |
| `search_nodes` | Find nodes by name pattern and optional type (e.g. `*Service*`) |
| `get_callers` | Find all callers of a method/function |
| `get_callees` | Find all methods/functions called by a given method |
| `get_class_hierarchy` | EXTENDS/IMPLEMENTS tree for a class |
| `get_control_flow` | CFG edges for a method |
| `get_data_flow` | DATA_FLOW edges for a method/variable |

### VS Code (Copilot)

Add to `.vscode/mcp.json`:

```json
{
  "servers": {
    "code-graph": {
      "command": "cmg-mcp",
      "args": ["--db-path", "./kuzu_db/my-project-db"]
    }
  }
}
```

### Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "code-graph": {
      "command": "/path/to/bin/cmg-mcp",
      "args": ["--db-path", "/path/to/kuzu_db/my-project-db"]
    }
  }
}
```

## Viewing the graph

### KuzuDB Explorer (Docker)

```bash
docker run -p 8000:8000 \
  -v $(pwd)/kuzu_db/my-project-db:/database \
  --rm kuzudb/explorer:latest
```

Open http://localhost:8000 and run Cypher queries.

### KuzuDB CLI

```bash
brew install kuzu
kuzu ./kuzu_db/my-project-db
```

### Neo4j Browser

Open http://localhost:7474 when using the Neo4j backend.

## Example queries

```cypher
-- Full graph snapshot
MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 300

-- All classes and their methods
MATCH (c:Class)-[:CONTAINS]->(m:Method) RETURN c.name, m.name

-- Inheritance hierarchy
MATCH (c:Class)-[:EXTENDS]->(p:Class) RETURN c.name, p.name

-- Method call graph
MATCH (a:Method)-[:CALLS]->(b:Method) RETURN a.qualifiedName, b.qualifiedName

-- Controller → Service → Repository call chains
MATCH (c:Method)-[:CALLS]->(s:Method)-[:CALLS]->(r:Method)
WHERE c.qualifiedName CONTAINS 'Controller' 
  AND s.qualifiedName CONTAINS 'Service'
RETURN c.name AS controller, s.name AS service, r.name AS repository

-- Find all callers of a method
MATCH (caller:Method)-[:CALLS]->(target:Method)
WHERE target.name = 'save'
RETURN caller.qualifiedName, target.qualifiedName

-- Methods with most outgoing calls
MATCH (m:Method)-[c:CALLS]->()
RETURN m.qualifiedName, COUNT(c) AS callCount
ORDER BY callCount DESC LIMIT 10

-- Dead code candidates (never called)
MATCH (m:Method)
WHERE NOT ()-[:CALLS]->(m)
RETURN m.qualifiedName

-- Classes implementing an interface
MATCH (c:Class)-[:IMPLEMENTS]->(i:Interface) RETURN c.name, i.name

-- Methods with annotations
MATCH (m:Method)-[:HAS_ANNOTATION]->(a:AnnotationType) RETURN m.name, a.name

-- CPG: Statements in a method
MATCH (m:Method {name: 'compute'})-[:AST_CHILD]->(s:Statement)
RETURN s.statementType, s.code, s.lineNumber ORDER BY s.lineNumber

-- CPG: Control flow path
MATCH (s1:Statement)-[:CFG_NEXT]->(s2:Statement)
RETURN s1.code, s2.code, s1.lineNumber, s2.lineNumber

-- CPG: Data flow for a variable
MATCH (s1:Statement)-[d:DATA_FLOW]->(s2:Statement)
WHERE d.variable = 'result'
RETURN s1.code, s2.code

-- Which file defines a class
MATCH (c:Class)-[:SOURCE_FILE]->(f:File) RETURN c.name, f.name
```

## Exporting to file

Export without a database using `--backend json` or the `export` subcommand (Java/Kotlin):

```bash
# Java/Kotlin — DOT, GraphML, or JSON
cmg-java export /path/to/project -f dot -o graph.dot
cmg-java export /path/to/project -f graphml -o graph.graphml
cmg-java export /path/to/project -f json -o graph.json

# JavaScript / Python — JSON to stdout
cmg-js     /path/to/react/app  --backend json > graph.json
cmg-python /path/to/flask/app  --backend json > graph.json
```

| Format | Flag | Description |
|--------|------|-------------|
| JSON | `json` | Nodes and relationships as JSON arrays |
| DOT | `dot` | Graphviz DOT. Render with `dot -Tpng graph.dot -o graph.png` |
| GraphML | `graphml` | XML graph format for yEd, Gephi, Cytoscape, etc. |

## CLI reference

### Java / Kotlin

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
      --incremental            Only re-parse changed files
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

### JavaScript / Python

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

### MCP server

| Flag | Default | Description |
|------|---------|-------------|
| `--backend` | `kuzu` | `kuzu` or `neo4j` |
| `--db-path` | — | KuzuDB database path (required for kuzu) |
| `--neo4j-uri` | `bolt://localhost:7687` | Neo4j connection URI |
| `--neo4j-user` | `neo4j` | Neo4j username |
| `--neo4j-password` | — | Neo4j password |
| `--neo4j-database` | `neo4j` | Neo4j database name |

## Configuration file

Create `application.yaml` (loaded automatically, or specify with `-c`):

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

CLI flags override config file values.

## Project structure

```
install.sh                      # Build & install script — creates bin/ executables
bin/
├── cmg-java                    # Java/Kotlin runner (wraps fat JAR)
├── cmg-js                      # JavaScript/TypeScript runner (wraps Node.js parser)
├── cmg-mcp                     # MCP server (wraps mcp/server.py)
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
│   ├── CpgParser.java              # Code Property Graph parser (AST + CFG + data flow)
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

mcp/
├── server.py                   # MCP server — exposes code graph to AI assistants
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
| MCP | Python MCP SDK |
| Build | Gradle 9.2 / Java 21 |
| Fat JAR | Shadow 9.0.0-beta12 |

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
