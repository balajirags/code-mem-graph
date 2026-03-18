#!/usr/bin/env node
/**
 * JavaScript/React/TypeScript parser using Babel.
 * Parses source code and writes a CodeGraph to KuzuDB, Neo4j, or JSON stdout.
 * 
 * Usage: node parse.js <directory> [options]
 *   --backend kuzu|neo4j|json   Graph backend (default: kuzu)
 *   --db-path <path>            KuzuDB database path (default: ./kuzu_db/<repo-name>-db)
 *   --clear                     Clear existing graph before writing
 *   --extensions .js,.jsx,...   File extensions to include
 *   --neo4j-uri <uri>           Neo4j URI (default: bolt://localhost:7687)
 *   --neo4j-user <user>         Neo4j username (default: neo4j)
 *   --neo4j-password <pass>     Neo4j password
 *   --neo4j-database <db>       Neo4j database (default: neo4j)
 */

const fs = require('fs');
const path = require('path');
const parser = require('@babel/parser');
const traverse = require('@babel/traverse').default;

const DEFAULT_EXTENSIONS = ['.js', '.jsx', '.ts', '.tsx', '.mjs'];

class CodeGraphBuilder {
    constructor() {
        this.nodes = {};
        this.relationships = [];
        this.internalModules = new Set();
    }

    addNode(id, type, name, qualifiedName, properties = {}) {
        if (!this.nodes[id]) {
            this.nodes[id] = { id, type, name, qualifiedName, properties };
        }
    }

    addRelationship(sourceId, targetId, type, properties = {}) {
        this.relationships.push({ sourceId, targetId, type, properties });
    }

    toJSON() {
        return {
            nodes: Object.values(this.nodes),
            relationships: this.relationships
        };
    }
}

function collectFiles(dir, extensions) {
    const files = [];
    
    function walk(currentDir) {
        const entries = fs.readdirSync(currentDir, { withFileTypes: true });
        for (const entry of entries) {
            const fullPath = path.join(currentDir, entry.name);
            if (entry.isDirectory()) {
                // Skip node_modules, build directories, etc.
                if (!['node_modules', 'dist', 'build', '.git', 'coverage'].includes(entry.name)) {
                    walk(fullPath);
                }
            } else if (entry.isFile() && extensions.some(ext => entry.name.endsWith(ext))) {
                files.push(fullPath);
            }
        }
    }
    
    walk(dir);
    return files;
}

function parseFile(filePath, rootDir, graph) {
    const code = fs.readFileSync(filePath, 'utf-8');
    const relativePath = path.relative(rootDir, filePath);
    const moduleName = relativePath.replace(/\.[^.]+$/, '').replace(/\//g, '.');
    
    // Add file node
    const fileId = `file:${relativePath}`;
    graph.addNode(fileId, 'FILE', path.basename(filePath), relativePath, {
        path: relativePath,
        language: getLanguage(filePath)
    });
    
    // Add module node
    const moduleId = `module:${moduleName}`;
    graph.addNode(moduleId, 'MODULE', moduleName, moduleName);
    graph.addRelationship(moduleId, fileId, 'SOURCE_FILE');
    graph.internalModules.add(moduleName);

    let ast;
    try {
        ast = parser.parse(code, {
            sourceType: 'module',
            plugins: [
                'jsx',
                'typescript',
                'decorators-legacy',
                'classProperties',
                'classPrivateProperties',
                'classPrivateMethods',
                'exportDefaultFrom',
                'exportNamespaceFrom',
                'dynamicImport',
                'nullishCoalescingOperator',
                'optionalChaining',
                'asyncGenerators',
                'objectRestSpread'
            ]
        });
    } catch (e) {
        console.error(`Parse error in ${filePath}: ${e.message}`);
        return;
    }

    const state = {
        graph,
        moduleId,
        moduleName,
        filePath: relativePath,
        currentClass: null,
        currentFunction: null,
        exports: new Set(),
        imports: new Map() // localName -> { source, importedName }
    };

    traverse(ast, {
        // Track imports
        ImportDeclaration(nodePath) {
            const source = nodePath.node.source.value;
            const isRelative = source.startsWith('.') || source.startsWith('/');
            
            for (const spec of nodePath.node.specifiers) {
                let localName, importedName;
                if (spec.type === 'ImportDefaultSpecifier') {
                    localName = spec.local.name;
                    importedName = 'default';
                } else if (spec.type === 'ImportNamespaceSpecifier') {
                    localName = spec.local.name;
                    importedName = '*';
                } else {
                    localName = spec.local.name;
                    importedName = spec.imported?.name || spec.local.name;
                }
                
                state.imports.set(localName, { source, importedName, isRelative });
                
                // Add import relationship for internal modules
                if (isRelative) {
                    const targetModule = resolveModulePath(source, state.filePath);
                    graph.addRelationship(state.moduleId, `module:${targetModule}`, 'IMPORTS', {
                        importedName,
                        localName
                    });
                }
            }
        },

        // Track exports
        ExportNamedDeclaration(nodePath) {
            if (nodePath.node.declaration) {
                const decl = nodePath.node.declaration;
                if (decl.type === 'FunctionDeclaration' && decl.id) {
                    state.exports.add(decl.id.name);
                } else if (decl.type === 'ClassDeclaration' && decl.id) {
                    state.exports.add(decl.id.name);
                } else if (decl.type === 'VariableDeclaration') {
                    for (const varDecl of decl.declarations) {
                        if (varDecl.id.type === 'Identifier') {
                            state.exports.add(varDecl.id.name);
                        }
                    }
                }
            }
        },

        ExportDefaultDeclaration(nodePath) {
            state.exports.add('default');
        },

        // Class declarations (including React class components)
        ClassDeclaration: {
            enter(nodePath) {
                const node = nodePath.node;
                const className = node.id?.name || 'AnonymousClass';
                const classId = `class:${state.moduleName}.${className}`;
                
                const isComponent = node.superClass && 
                    (node.superClass.name === 'Component' || 
                     node.superClass.name === 'PureComponent' ||
                     (node.superClass.type === 'MemberExpression' && 
                      node.superClass.property?.name === 'Component'));
                
                graph.addNode(classId, isComponent ? 'COMPONENT' : 'CLASS', className, 
                    `${state.moduleName}.${className}`, {
                        lineNumber: node.loc?.start?.line,
                        isReactComponent: isComponent
                    });
                
                graph.addRelationship(state.moduleId, classId, 'CONTAINS');
                
                if (node.superClass) {
                    const superName = node.superClass.name || 
                        (node.superClass.type === 'MemberExpression' ? 
                            `${node.superClass.object?.name}.${node.superClass.property?.name}` : 'Unknown');
                    const superId = `class:${superName}`;
                    graph.addNode(superId, 'CLASS', superName, superName);
                    graph.addRelationship(classId, superId, 'EXTENDS');
                }
                
                state.currentClass = classId;
            },
            exit() {
                state.currentClass = null;
            }
        },

        // Method definitions within classes
        ClassMethod: {
            enter(nodePath) {
                const node = nodePath.node;
                const methodName = node.key?.name || 'anonymous';
                const parentClass = state.currentClass;
                
                if (!parentClass) return;
                
                const methodId = `method:${parentClass.replace('class:', '')}.${methodName}`;
                const nodeType = node.kind === 'constructor' ? 'CONSTRUCTOR' : 'METHOD';
                
                graph.addNode(methodId, nodeType, methodName, methodId.replace('method:', ''), {
                    lineNumber: node.loc?.start?.line,
                    isAsync: node.async,
                    isStatic: node.static,
                    isGetter: node.kind === 'get',
                    isSetter: node.kind === 'set'
                });
                
                graph.addRelationship(parentClass, methodId, 'CONTAINS');
                state.currentFunction = methodId;
            },
            exit() {
                state.currentFunction = null;
            }
        },

        // Function declarations
        FunctionDeclaration: {
            enter(nodePath) {
                const node = nodePath.node;
                const funcName = node.id?.name || 'anonymous';
                const funcId = `function:${state.moduleName}.${funcName}`;
                
                // Check if it's a React component (PascalCase + returns JSX)
                const isComponent = /^[A-Z]/.test(funcName);
                // Check if it's a hook (starts with 'use')
                const isHook = /^use[A-Z]/.test(funcName);
                
                let nodeType = 'FUNCTION';
                if (isHook) nodeType = 'HOOK';
                else if (isComponent) nodeType = 'COMPONENT';
                else if (node.async) nodeType = 'ASYNC_FUNCTION';
                else if (node.generator) nodeType = 'GENERATOR';
                
                graph.addNode(funcId, nodeType, funcName, `${state.moduleName}.${funcName}`, {
                    lineNumber: node.loc?.start?.line,
                    isAsync: node.async,
                    isGenerator: node.generator,
                    paramCount: node.params.length
                });
                
                graph.addRelationship(state.moduleId, funcId, 'CONTAINS');
                
                if (state.exports.has(funcName)) {
                    graph.addRelationship(state.moduleId, funcId, 'EXPORTS');
                }
                
                state.currentFunction = funcId;
            },
            exit() {
                state.currentFunction = null;
            }
        },

        // Arrow functions assigned to variables (common in React)
        VariableDeclarator(nodePath) {
            const node = nodePath.node;
            if (node.init?.type !== 'ArrowFunctionExpression' && 
                node.init?.type !== 'FunctionExpression') return;
            if (node.id?.type !== 'Identifier') return;
            
            const funcName = node.id.name;
            const funcId = `function:${state.moduleName}.${funcName}`;
            
            const isComponent = /^[A-Z]/.test(funcName);
            const isHook = /^use[A-Z]/.test(funcName);
            
            let nodeType = 'ARROW_FUNCTION';
            if (isHook) nodeType = 'HOOK';
            else if (isComponent) nodeType = 'COMPONENT';
            else if (node.init.async) nodeType = 'ASYNC_FUNCTION';
            else if (node.init.generator) nodeType = 'GENERATOR';
            
            graph.addNode(funcId, nodeType, funcName, `${state.moduleName}.${funcName}`, {
                lineNumber: node.loc?.start?.line,
                isAsync: node.init.async,
                isArrow: node.init.type === 'ArrowFunctionExpression'
            });
            
            graph.addRelationship(state.moduleId, funcId, 'CONTAINS');
            
            if (state.exports.has(funcName)) {
                graph.addRelationship(state.moduleId, funcId, 'EXPORTS');
            }
        },

        // Call expressions (function calls)
        CallExpression(nodePath) {
            const node = nodePath.node;
            const caller = state.currentFunction || state.currentClass || state.moduleId;
            
            let calleeName;
            if (node.callee.type === 'Identifier') {
                calleeName = node.callee.name;
            } else if (node.callee.type === 'MemberExpression') {
                const obj = node.callee.object?.name || '';
                const prop = node.callee.property?.name || '';
                calleeName = obj ? `${obj}.${prop}` : prop;
            } else {
                return;
            }

            // Check if calling a hook
            if (/^use[A-Z]/.test(calleeName)) {
                const hookId = `hook:${calleeName}`;
                graph.addNode(hookId, 'HOOK', calleeName, calleeName);
                graph.addRelationship(caller, hookId, 'USES_HOOK', {
                    lineNumber: node.loc?.start?.line
                });
                return;
            }

            // Check if it's an imported function
            const importInfo = state.imports.get(calleeName);
            if (importInfo && importInfo.isRelative) {
                const targetModule = resolveModulePath(importInfo.source, state.filePath);
                const targetFuncId = `function:${targetModule}.${importInfo.importedName === 'default' ? calleeName : importInfo.importedName}`;
                graph.addRelationship(caller, targetFuncId, 'CALLS', {
                    lineNumber: node.loc?.start?.line,
                    resolved: true
                });
            } else if (!importInfo) {
                // Local function call
                const targetFuncId = `function:${state.moduleName}.${calleeName}`;
                graph.addRelationship(caller, targetFuncId, 'CALLS', {
                    lineNumber: node.loc?.start?.line,
                    resolved: true
                });
            }
        },

        // JSX elements (React component usage)
        JSXOpeningElement(nodePath) {
            const node = nodePath.node;
            let componentName;
            
            if (node.name.type === 'JSXIdentifier') {
                componentName = node.name.name;
            } else if (node.name.type === 'JSXMemberExpression') {
                componentName = `${node.name.object?.name}.${node.name.property?.name}`;
            } else {
                return;
            }

            // Only track PascalCase components (user-defined, not HTML elements)
            if (!/^[A-Z]/.test(componentName)) return;
            
            const caller = state.currentFunction || state.currentClass || state.moduleId;
            
            // Check if imported
            const importInfo = state.imports.get(componentName);
            let targetId;
            
            if (importInfo && importInfo.isRelative) {
                const targetModule = resolveModulePath(importInfo.source, state.filePath);
                targetId = `component:${targetModule}.${componentName}`;
            } else {
                targetId = `component:${componentName}`;
            }
            
            graph.addNode(targetId, 'COMPONENT', componentName, componentName);
            graph.addRelationship(caller, targetId, 'RENDERS', {
                lineNumber: node.loc?.start?.line
            });
        }
    });
}

function resolveModulePath(importSource, currentFilePath) {
    // Simple resolution: convert relative imports to module names
    const dir = path.dirname(currentFilePath);
    const resolved = path.normalize(path.join(dir, importSource));
    return resolved.replace(/\.[^.]+$/, '').replace(/\//g, '.').replace(/^\./, '');
}

function getLanguage(filePath) {
    if (filePath.endsWith('.ts') || filePath.endsWith('.tsx')) return 'TypeScript';
    if (filePath.endsWith('.jsx')) return 'JSX';
    return 'JavaScript';
}

function main() {
    const args = process.argv.slice(2);
    if (args.length < 1) {
        console.error('Usage: node parse.js <directory> [--backend kuzu|neo4j|json] [--db-path <path>] [--clear] [--extensions .js,.jsx,.ts,.tsx]');
        process.exit(1);
    }

    const rootDir = path.resolve(args[0]);
    const repoName = path.basename(rootDir);
    let extensions = DEFAULT_EXTENSIONS;
    let backend = 'kuzu';
    let dbPath = null;
    let clearGraph = false;
    let neo4jUri = 'bolt://localhost:7687';
    let neo4jUser = 'neo4j';
    let neo4jPassword = '';
    let neo4jDatabase = 'neo4j';

    for (let i = 1; i < args.length; i++) {
        switch (args[i]) {
            case '--backend': backend = args[++i]; break;
            case '--db-path': dbPath = path.resolve(args[++i]); break;
            case '--clear': clearGraph = true; break;
            case '--extensions': extensions = args[++i].split(',').map(e => e.trim()); break;
            case '--neo4j-uri': neo4jUri = args[++i]; break;
            case '--neo4j-user': neo4jUser = args[++i]; break;
            case '--neo4j-password': neo4jPassword = args[++i]; break;
            case '--neo4j-database': neo4jDatabase = args[++i]; break;
        }
    }

    // Default db path: ./kuzu_db/<repo-name>-db
    if (!dbPath) {
        dbPath = path.resolve('kuzu_db', `${repoName}-db`);
    }

    if (!fs.existsSync(rootDir)) {
        console.error(`Directory not found: ${rootDir}`);
        process.exit(1);
    }

    const graph = new CodeGraphBuilder();
    const files = collectFiles(rootDir, extensions);
    
    console.error(`Found ${files.length} JavaScript/TypeScript files to parse`);

    for (const file of files) {
        parseFile(file, rootDir, graph);
    }

    // Filter relationships to only include internal targets
    const filteredRelationships = graph.relationships.filter(rel => {
        if (rel.type === 'CALLS' || rel.type === 'RENDERS') {
            return graph.nodes[rel.targetId] !== undefined;
        }
        return true;
    });
    graph.relationships = filteredRelationships;

    const graphJson = graph.toJSON();

    if (backend === 'json') {
        console.log(JSON.stringify(graphJson, null, 2));
        return;
    }

    // Write to graph database
    const { createStore } = require('./store');
    (async () => {
        const store = createStore({
            backend, dbPath, neo4jUri, neo4jUser, neo4jPassword, neo4jDatabase
        });
        try {
            await store.initSchema();
            if (clearGraph) await store.clear();
            await store.save(graphJson);
            const summary = await store.summary();
            console.error(summary);
        } finally {
            await store.close();
        }
    })().catch(err => {
        console.error('Error writing to graph database:', err.message);
        process.exit(1);
    });
}

main();
