# context.md

## Recommendation: Language for the Java Code Knowledge Graph Utility

### Final recommendation
Build the utility in **Java**.

This is the strongest choice because the target source language is Java, and the best AST and semantic-analysis libraries for this problem are Java-native.

---

## Why Java is the right choice

### 1. Best ecosystem fit
For AST-based code analysis of Java codebases, Java gives you direct access to the strongest tooling:
- **JavaParser** for AST parsing and straightforward traversal
- **Eclipse JDT** for stronger compiler-style bindings and symbol resolution
- **Spoon** for rich source-code analysis and transformation

### 2. Better symbol resolution
A useful knowledge graph should not stop at raw AST extraction.  
It should also resolve:
- method call targets
- field references
- inheritance
- interface implementations
- overrides
- annotations
- type usage

Java-native tools handle this much better than trying to do the same from a non-JVM stack.

### 3. Easier build-system integration
Most real Java repositories use:
- Maven
- Gradle
- multi-module project structures
- external dependencies
- generated sources

A Java-based utility can integrate with these much more naturally.

### 4. Better for enterprise Java codebases
If the target repositories include Spring Boot, layered services, repositories, controllers, annotations, and large multi-module setups, Java is the safest and most maintainable language for the extractor core.

---

## What not to do

Do **not** build the AST extraction core in Python unless:
- the parsing work is delegated to a Java service, or
- Python is only being used for orchestration / AI / analytics

Python is great for:
- embeddings
- RAG
- LLM workflows
- experimentation
- downstream analytics

But it is **not** the best choice for the parser + symbol-resolution core of a Java code knowledge graph.

---

## Preferred implementation approach

### Core utility
Use **Java** for:
- repository scanning
- AST parsing
- symbol resolution
- graph extraction
- graph writing

### Optional secondary layer
Use **Python** only later if needed for:
- LLM enrichment
- embeddings
- semantic search
- graph-assisted RAG
- analysis dashboards

---

## Recommended technical stack

### Language
- **Java**

### Parser for v1
- **JavaParser**

### Upgrade path for tougher repositories
- **Eclipse JDT**

### Alternative model-oriented option
- **Spoon**

### Graph store
- **Neo4j** for graph traversal and exploration

### Optional metadata store
- **Postgres**

### Optional AI layer
- **Python**, only as a secondary layer

---

## Suggested decision

If the goal is to build a serious AST-based knowledge graph for any Java codebase, the best path is:

**Java for the core extractor, parser, symbol resolver, and graph generation.**

Use Python only later for AI-assisted features, not for the extraction core.

---

## One-line summary
**Build the utility in Java first; keep Python optional for downstream AI and analytics.**
