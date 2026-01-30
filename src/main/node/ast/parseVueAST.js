const fs = require("fs");
const babel = require("@babel/parser");
const traverse = require("@babel/traverse").default;

let vue2Compiler;
try {
    vue2Compiler = require("vue-template-compiler");
} catch (_) {}

let vue3Compiler;
try {
    vue3Compiler = require("@vue/compiler-sfc");
} catch (_) {}

async function readInput() {
    return new Promise(resolve => {
        let data = "";
        process.stdin.on("data", chunk => data += chunk);
        process.stdin.on("end", () => resolve(data));
    });
}

function parseVue3Template(descriptor) {
    if (!descriptor || !descriptor.template) {
        return { domNodes: [], componentConditions: {} };
    }

    const root = descriptor.template.ast;
    if (!root) return { domNodes: [], componentConditions: {} };

    const domNodes = [];
    const componentConditions = {};  // 记录组件的渲染条件

    function walk(node, parentPath = null, parentCondition = null) {
        if (!node) return;

        if (node.type === 1) {
            const domNode = {
                tag: node.tag || null,
                attrs: {},
                events: {},
                text: null,
                parentPath: parentPath,
                condition: null
            };

            let currentCondition = parentCondition;

            if (Array.isArray(node.props)) {
                for (const p of node.props) {
                    if (p.type === 7 && p.name === "on") {
                        const eventName = p.arg?.content || "click";
                        const expression = p.exp?.content || null;
                        if (expression) {
                            domNode.events[eventName] = expression;
                        }
                    }

                    if (p.type === 7 && (p.name === "if" || p.name === "show")) {
                        const condition = p.exp?.content || null;
                        if (condition) {
                            currentCondition = condition;
                        }
                    }

                    if (p.type === 7 && p.name === "else") {
                        currentCondition = "v-else";
                    }

                    if (p.type === 6) {
                        domNode.attrs[p.name] = p.value?.content || "";
                    }

                    if (p.type === 7 && p.name === "bind") {
                        const attrName = p.arg?.content || "";
                        const attrValue = p.exp?.content || "";
                        if (attrName) {
                            domNode.attrs[attrName] = attrValue;
                        }
                    }
                }
            }

            if (node.tag && /^[A-Z]/.test(node.tag)) {
                if (currentCondition) {
                    componentConditions[node.tag] = currentCondition;
                }
            }

            domNode.condition = currentCondition;

            if (Array.isArray(node.children)) {
                const extractAllText = (children, isSubMenu = false) => {
                    let texts = [];
                    for (const child of children) {
                        if (child.type === 2) {
                            let text = child.content?.trim();
                            if (text && !text.startsWith("{{")) {
                                text = text.replace(/[【\[\(（]+$/, '');
                                if (text) {
                                    texts.push(text);
                                }
                            }
                        } else if (child.type === 1 && Array.isArray(child.children)) {
                            if (isSubMenu && child.tag && child.tag === 'el-menu-item') {
                                continue;
                            }
                            texts = texts.concat(extractAllText(child.children, false));
                        }
                    }
                    return texts;
                };

                if (node.tag === 'el-sub-menu' || node.tag === 'el-submenu') {
                    for (const child of node.children) {
                        if (child.type === 1 && child.tag === 'template') {
                            let isTitleTemplate = false;
                            if (Array.isArray(child.props)) {
                                for (const prop of child.props) {
                                    if (prop.type === 7 && prop.name === 'slot' && prop.arg && prop.arg.content === 'title') {
                                        isTitleTemplate = true;
                                        break;
                                    }
                                    if (prop.type === 6 && prop.name === 'slot' && prop.value && prop.value.content === 'title') {
                                        isTitleTemplate = true;
                                        break;
                                    }
                                }
                            }

                            if (isTitleTemplate && Array.isArray(child.children)) {
                                const titleTexts = extractAllText(child.children, false);
                                if (titleTexts.length > 0) {
                                    domNode.text = titleTexts[0];
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    const allTexts = extractAllText(node.children, false);
                    if (allTexts.length > 0) {
                        domNode.text = allTexts[0];
                    }
                }
            }

            const hasNavigation = domNode.attrs && (
                domNode.attrs.index ||
                domNode.attrs.to ||
                domNode.attrs[':to'] ||
                domNode.attrs['v-bind:to']
            );

            const isNavigationComponent = node.tag && (
                node.tag === 'el-menu-item' ||
                node.tag === 'el-dropdown-item' ||
                node.tag === 'el-avatar' ||
                node.tag === 'button' ||
                node.tag === 'el-button' ||
                node.tag === 'a'
            );

            if (hasNavigation || isNavigationComponent) {
                domNodes.push(domNode);
            }

            let currentPath = null;
            if (node.tag) {
                if (node.tag.startsWith("el-")) {
                    currentPath = "." + node.tag;
                } else {
                    currentPath = node.tag;
                }
            }

            const childParentPath = parentPath && currentPath
                ? parentPath + " " + currentPath
                : currentPath || parentPath;

            if (Array.isArray(node.children)) {
                node.children.forEach(child => walk(child, childParentPath, currentCondition));
            }
        }

        if (node.type === 0 && Array.isArray(node.children)) {
            node.children.forEach(child => walk(child, parentPath, parentCondition));
        }

        if (node.content) {
            walk(node.content, parentPath, parentCondition);
        }

        if (Array.isArray(node.branches)) {
            node.branches.forEach(branch => walk(branch, parentPath, parentCondition));
        }
    }

    walk(root);

    return { domNodes, componentConditions };
}

function parseVue2Template(content) {
    if (!vue2Compiler) return null;

    const parsed = vue2Compiler.parseComponent(content);
    if (!parsed.template || !parsed.template.content) return null;

    const compiled = vue2Compiler.compile(parsed.template.content);
    const ast = compiled.ast;
    if (!ast) return null;

    const domNodes = [];

    const walk = (node, parentPath = null, parentCondition = null) => {
        if (!node) return;

        const domNode = {
            tag: node.tag || null,
            attrs: {},
            events: {},
            text: null,
            parentPath: parentPath,
            condition: null
        };

        let currentCondition = parentCondition;
        if (node.attrsMap) {
            for (const key of Object.keys(node.attrsMap)) {
                const value = node.attrsMap[key];

                if (key.startsWith("@") || key.startsWith("v-on:")) {
                    const eventName = key.replace("@", "").replace("v-on:", "").replace(".native", "");
                    domNode.events[eventName] = value;
                }
                else if (key === "v-if" || key === "v-show") {
                    currentCondition = value;  // 当前节点有自己的 condition，覆盖父元素的
                }
                else {
                    domNode.attrs[key] = value;
                }
            }
        }

        domNode.condition = currentCondition;

        let currentPath = null;
        if (node.tag) {
            if (node.tag.startsWith("el-")) {
                currentPath = "." + node.tag;
            } else {
                currentPath = node.tag;
            }
        }

        if (domNode.attrs && domNode.attrs.slot && parentPath) {
            const slotName = domNode.attrs.slot;
            if (parentPath.includes(".el-dialog")) {
                currentPath = ".el-dialog__" + slotName;
            }
        }

        const fullPath = parentPath && currentPath
            ? parentPath + " " + currentPath
            : currentPath || parentPath;

        if (node.children && node.children.length > 0) {
            const extractAllText = (children, isSubMenu = false) => {
                let texts = [];
                for (const child of children) {
                    if (child.type === 3 && child.text) {
                        const text = child.text.trim();
                        if (text && !text.startsWith("{{")) {
                            texts.push(text);
                        }
                    } else if (child.type === 1 && child.children) {
                        if (isSubMenu && child.tag && child.tag === 'el-menu-item') {
                            continue;
                        }
                        texts = texts.concat(extractAllText(child.children, false));
                    }
                }
                return texts;
            };

            if (node.tag === 'el-sub-menu' || node.tag === 'el-submenu') {
                for (const child of node.children) {
                    if (child.type === 1 && child.attrsMap && child.attrsMap.slot === 'title') {
                        if (child.children) {
                            const titleTexts = extractAllText(child.children, false);
                            if (titleTexts.length > 0) {
                                domNode.text = titleTexts[0];
                                break;
                            }
                        }
                    }
                }
            } else {
                const allTexts = extractAllText(node.children, false);
                if (allTexts.length > 0) {
                    domNode.text = allTexts[0];
                }
            }
        }

        const hasNavigation = domNode.attrs && (
            domNode.attrs.index ||
            domNode.attrs.to ||
            domNode.attrs[':to'] ||
            domNode.attrs['v-bind:to']
        );

        const isNavigationComponent = node.tag && (
            node.tag === 'el-menu-item' ||
            node.tag === 'el-dropdown-item' ||
            node.tag === 'el-avatar' ||
            node.tag === 'button' ||
            node.tag === 'el-button' ||
            node.tag === 'a'
        );

        if (hasNavigation || isNavigationComponent) {
            domNodes.push(domNode);
        }

        if (node.children) {
            node.children.forEach(child => walk(child, fullPath, currentCondition));
        }
    };

    walk(ast);
    return { domNodes };
}

function parseScript(descriptor) {
    const scriptContent =
        descriptor?.script?.content ??
        descriptor?.scriptSetup?.content ??
        "";

    if (!scriptContent) {
        return { routerCalls: [], usesCompositionAPI: false, importedComponents: [] };
    }

    let ast;
    try {
        ast = babel.parse(scriptContent, {
            sourceType: "module",
            plugins: ["typescript", "jsx"]
        });
    } catch (err) {
        console.error("[parseScript ERROR]", err.message);
        return { routerCalls: [], usesCompositionAPI: false, importedComponents: [] };
    }

    const routerCalls = [];
    const importedComponents = [];
    let usesCompositionAPI = false;

    traverse(ast, {
        ImportDeclaration(path) {
            const source = path.node.source.value;

            if (source === "vue") {
                for (const spec of path.node.specifiers) {
                    if (spec.imported?.name === "ref") {
                        usesCompositionAPI = true;
                    }
                }
            }
            else if (source.startsWith(".") || source.startsWith("@/") || source.startsWith("~/")) {
                for (const spec of path.node.specifiers) {
                    if (spec.type === "ImportDefaultSpecifier") {
                        const componentName = spec.local.name;
                        importedComponents.push({
                            name: componentName,
                            path: source
                        });
                    }
                }
            }
        },
        CallExpression(path) {
            const callee = path.node.callee;
            let methodName = null;
            let isRouterCall = false;

            if (callee?.type === "MemberExpression" &&
                callee.property?.name &&
                (callee.property.name === "push" || callee.property.name === "replace" || callee.property.name === "resolve")) {

                if (callee.object?.type === "MemberExpression" &&
                    callee.object.property?.name === "$router") {
                    methodName = callee.property.name;
                    isRouterCall = true;
                }
                else if (callee.object?.type === "Identifier" &&
                         callee.object.name === "router") {
                    methodName = callee.property.name;
                    isRouterCall = true;
                }
            }

            if (!isRouterCall && callee?.type === "Identifier") {
                const funcName = callee.name;
                if (funcName === "routerManager" || funcName === "navigate" || funcName === "goTo" ||
                    funcName === "navigateTo" || funcName === "pushRoute" || funcName === "replaceRoute") {
                    methodName = "push";
                    isRouterCall = true;
                }
            }

            if (isRouterCall && methodName) {
                let arg = path.node.arguments[0];

                if (callee?.type === "Identifier" && path.node.arguments.length >= 2) {
                    const secondArg = path.node.arguments[1];
                    if (secondArg?.type === "ObjectExpression") {
                        arg = secondArg;  // 使用第二个参数作为路由配置
                    }
                }

                let routePath = null;

                if (arg?.type === "StringLiteral") {
                    routePath = arg.value;
                }
                else if (arg?.type === "ObjectExpression") {
                    for (const prop of arg.properties) {
                        if (prop.key?.name === "path") {
                            if (prop.value?.type === "StringLiteral") {
                                routePath = prop.value.value;
                                break;
                            }
                            else if (prop.value?.type === "MemberExpression") {
                                const enumProp = prop.value.property?.name;
                                if (enumProp) {
                                    if (enumProp === "SignIn" || enumProp === "Login" || enumProp === "Index") {
                                        routePath = "/";
                                    } else {
                                        routePath = "/" + enumProp;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                else if (arg?.type === "BinaryExpression" && arg.operator === "+") {
                    if (arg.left?.type === "StringLiteral") {
                        routePath = arg.left.value;
                        if (routePath.endsWith("/")) {
                            routePath = routePath.slice(0, -1);
                        }
                        const queryIndex = routePath.indexOf("?");
                        if (queryIndex !== -1) {
                            routePath = routePath.substring(0, queryIndex);
                        }
                    }
                }
                else if (arg?.type === "TemplateLiteral" && arg.quasis && arg.quasis.length > 0) {
                    routePath = arg.quasis[0].value.cooked || arg.quasis[0].value.raw;
                    if (routePath && routePath.endsWith("/")) {
                        routePath = routePath.slice(0, -1);
                    }
                }

                if (routePath) {
                    let handler = null;
                    let currentPath = path;

                    while (currentPath) {
                        const parent = currentPath.parent;

                        if (parent?.type === "ObjectMethod" && parent.key) {
                            handler = parent.key.name || parent.key.value;
                            break;
                        }
                        if (parent?.type === "Property" && parent.key &&
                            (currentPath.node.type === "FunctionExpression" || currentPath.node.type === "ArrowFunctionExpression")) {
                            handler = parent.key.name || parent.key.value;
                            break;
                        }
                        if (parent?.type === "ObjectProperty" && parent.key &&
                            (currentPath.node.type === "FunctionExpression" || currentPath.node.type === "ArrowFunctionExpression")) {
                            handler = parent.key.name || parent.key.value;
                            break;
                        }
                        if (parent?.type === "FunctionDeclaration" && parent.id) {
                            handler = parent.id.name;
                            break;
                        }
                        if (parent?.type === "VariableDeclarator" && parent.id &&
                            (currentPath.node.type === "FunctionExpression" || currentPath.node.type === "ArrowFunctionExpression")) {
                            handler = parent.id.name;
                            break;
                        }

                        currentPath = currentPath.parentPath;
                    }

                    routerCalls.push({
                        type: methodName,
                        argument: routePath,
                        handler: handler
                    });
                }
            }
        }
    });

    return { routerCalls, usesCompositionAPI, importedComponents };
}

(async () => {
    const content = await readInput();

    let templateFacts = null;
    let scriptFacts = { routerCalls: [], usesCompositionAPI: false };

    if (vue3Compiler) {
        try {
            const descriptor = vue3Compiler.parse(content).descriptor;
            templateFacts = parseVue3Template(descriptor);
            scriptFacts = parseScript(descriptor);
        } catch (err) {
        }
    }

    if ((!templateFacts || !templateFacts.domNodes || templateFacts.domNodes.length === 0) && vue2Compiler) {
        const vue2Facts = parseVue2Template(content);
        if (vue2Facts && vue2Facts.domNodes && vue2Facts.domNodes.length > 0) {
            templateFacts = vue2Facts;
            const parsed = vue2Compiler.parseComponent(content);
            if (parsed.script && parsed.script.content) {
                scriptFacts = parseScript({ script: { content: parsed.script.content } });
            }
        }
    }

    if (!templateFacts) {
        templateFacts = { domNodes: [], componentConditions: {} };
    }

    const result = {
        domNodes: templateFacts.domNodes || [],
        routerCalls: scriptFacts.routerCalls || [],
        routeComponents: [],
        usesCompositionAPI: scriptFacts.usesCompositionAPI || false,
        importedComponents: scriptFacts.importedComponents || [],
        componentConditions: templateFacts.componentConditions || {}
    };

    process.stdout.write(JSON.stringify(result));
})();
