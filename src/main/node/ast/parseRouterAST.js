const babel = require("@babel/parser");
const traverse = require("@babel/traverse").default;


function isHttp(url) {
    return url.startsWith("http://") || url.startsWith("https://");
}

function joinPath(parent, child) {
    if (!child) return parent || "";
    if (child.startsWith("/")) return child;
    if (!parent || parent === "/") return "/" + child;
    return parent.replace(/\/$/, "") + "/" + child;
}

function extractImport(node, importMap) {
    try {
        if (node.type === "ArrowFunctionExpression") {
            const body = node.body;
            if (body.type === "CallExpression" && body.callee.type === "Import") {
                const arg = body.arguments[0];
                if (arg && arg.type === "StringLiteral") {
                    return arg.value.replace("@/", "src/");
                }
            }
        }

        if (node.type === "Identifier" && importMap) {
            const imported = importMap[node.name];
            if (imported) {
                return imported.replace("@/", "src/");
            }
        }
    } catch (_) {}
    return null;
}


function walkRouteObject(objNode, parentPath, result, importMap) {
    let pathVal = "";
    let componentPath = null;
    let redirectTarget = null;
    let children = [];

    for (const prop of objNode.properties) {
        const keyName = prop.key && prop.key.name;

        if (keyName === "path" && prop.value.type === "StringLiteral") {
            pathVal = joinPath(parentPath, prop.value.value);
        }

        if (keyName === "component") {
            componentPath = extractImport(prop.value, importMap);
        }

        if (keyName === "redirect") {
            if (prop.value.type === "StringLiteral") {
                redirectTarget = prop.value.value;
            }
        }

        if (keyName === "children" && prop.value.type === "ArrayExpression") {
            children = prop.value.elements.filter(n => n && n.type === "ObjectExpression");
        }
    }

    if (pathVal && componentPath && !isHttp(pathVal)) {
        result.routes[pathVal] = componentPath;
    }

    if (pathVal && redirectTarget) {
        result.redirects.push({ from: pathVal, to: redirectTarget });
    }

    if (pathVal && children.length > 0) {
        if (!result.children) {
            result.children = {};
        }
        if (!result.children[pathVal]) {
            result.children[pathVal] = [];
        }

        if (!result.pathDefinitionCount[pathVal]) {
            result.pathDefinitionCount[pathVal] = 0;
        }
        result.pathDefinitionCount[pathVal]++;

        children.forEach(child => {
            for (const prop of child.properties) {
                if (prop.key && prop.key.name === "path" && prop.value.type === "StringLiteral") {
                    const childPath = joinPath(pathVal, prop.value.value);
                    result.children[pathVal].push(childPath);
                    break;
                }
            }
        });
    }

    children.forEach(child => {
        let childPathVal = "";
        for (const prop of child.properties) {
            if (prop.key && prop.key.name === "path" && prop.value.type === "StringLiteral") {
                childPathVal = prop.value.value;
                break;
            }
        }

        walkRouteObject(child, pathVal, result, importMap);

        if (childPathVal === "" && componentPath) {
            const fullChildPath = joinPath(pathVal, childPathVal);
            if (fullChildPath === pathVal) {
                result.routes[pathVal] = componentPath;
            }
        }
    });
}


function parseRouter(content) {
    const ast = babel.parse(content, {
        sourceType: "module",
        plugins: ["typescript", "jsx", "dynamicImport", "importMeta"]
    });

    const result = { routes: {}, redirects: [], pathDefinitionCount: {} };

    const importMap = {};

    traverse(ast, {
        ImportDeclaration(path) {
            const source = path.node.source && path.node.source.value;
            if (!source) return;
            path.node.specifiers.forEach(spec => {
                if (spec.type === "ImportDefaultSpecifier" || spec.type === "ImportSpecifier") {
                    const localName = spec.local && spec.local.name;
                    if (localName) {
                        importMap[localName] = source;
                    }
                }
            });
        },
        NewExpression(path) {
            const callee = path.node.callee;
            if (callee.name === "Router") {
                const opts = path.node.arguments[0];
                extractRoutesFromObject(opts, result, importMap);
            }
        },

        CallExpression(path) {
            const callee = path.node.callee;
            if (
                callee.type === "Identifier" &&
                callee.name === "createRouter"
            ) {
                const opts = path.node.arguments[0];
                extractRoutesFromObject(opts, result, importMap);
            }
        },

        VariableDeclarator(path) {
            const init = path.node.init;
            if (init && init.type === "ArrayExpression") {
                init.elements.forEach(el => {
                    if (el && el.type === "ObjectExpression") {
                        walkRouteObject(el, "", result, importMap);
                    }
                });
            }
        }
    });

    return result;
}

function extractRoutesFromObject(objNode, result, importMap) {
    if (!objNode || objNode.type !== "ObjectExpression") return;
    const prop = objNode.properties.find(p => p.key.name === "routes");
    if (!prop) return;
    if (prop.value.type !== "ArrayExpression") return;

    prop.value.elements.forEach(el => {
        if (el && el.type === "ObjectExpression") {
            walkRouteObject(el, "", result, importMap);
        }
    });
}


async function readStdin() {
    return new Promise(resolve => {
        let data = "";
        process.stdin.on("data", c => (data += c));
        process.stdin.on("end", () => resolve(data));
    });
}

(async () => {
    try {
        const input = await readStdin();
        const result = parseRouter(input);
        process.stdout.write(JSON.stringify(result));
    } catch (err) {
        console.error(err);
        process.exit(1);
    }
})();
