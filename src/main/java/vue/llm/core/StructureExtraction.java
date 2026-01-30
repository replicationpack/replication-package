package vue.llm.core;

import com.alibaba.fastjson2.JSON;
import vue.llm.ast.ASTAnalyzer;
import vue.llm.ast.DomNode;
import vue.llm.ast.RouterCall;
import vue.llm.ast.StaticFacts;
import vue.llm.graph.Edge;
import vue.llm.graph.PageNode;
import vue.llm.graph.StructureGraph;
import vue.llm.io.VueFile;
import vue.llm.router.RouteTable;
import vue.llm.util.LlmClient;
import vue.llm.util.ProgressBar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class StructureExtraction {

    private final LlmClient llm;
    private final RouteTable routeTable;
    private final String routerFilePath;
    private final StructureGraph globalGraph = new StructureGraph();
    private List<VueFile> vueFiles;
    private String projectRoot;
    private boolean isElementUI2 = false;

    private StructureGraph stage1Snapshot = null;
    private StructureGraph stage2Snapshot = null;

    public StructureExtraction(LlmClient llm, RouteTable routeTable, String routerFilePath) {
        this.llm = llm;
        this.routeTable = routeTable;
        this.routerFilePath = routerFilePath;
        this.projectRoot = extractProjectRoot(routerFilePath);
        this.isElementUI2 = detectElementUI2();
    }

    public StructureGraph getStage1Snapshot() {
        return stage1Snapshot;
    }

    public StructureGraph getStage2Snapshot() {
        return stage2Snapshot;
    }

    public RouteTable getRouteTable() {
        return routeTable;
    }

    private String extractProjectRoot(String routerFilePath) {
        if (routerFilePath == null) return null;
        Path path = Paths.get(routerFilePath);
        while (path != null && path.getParent() != null) {
            Path packageJson = path.resolve("package.json");
            if (Files.exists(packageJson)) {
                return path.toString();
            }
            path = path.getParent();
        }
        return null;
    }

    private boolean detectElementUI2() {
        if (projectRoot == null) return false;
        Path packageJsonPath = Paths.get(projectRoot, "package.json");
        if (!Files.exists(packageJsonPath)) return false;

        try {
            String content = Files.readString(packageJsonPath);
            if (content.contains("\"element-ui\"")) {
                return true;
            }
            if (content.contains("\"element-plus\"")) {
                return false;
            }
        } catch (Exception e) {
            System.err.println("Failed to read package.json: " + e.getMessage());
        }
        return false;
    }


    private boolean isRouterFile(String filePath) {
        if (filePath == null) {
            return false;
        }
        if (filePath.equals("router-config")) {
            return true;
        }
        if (routerFilePath == null) {
            return false;
        }
        return routerFilePath.endsWith(filePath) || filePath.equals(routerFilePath);
    }

    private String getRouterFileName() {
        if (routerFilePath == null) {
            return "router.js";
        }
        int lastSlash = Math.max(routerFilePath.lastIndexOf('/'), routerFilePath.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < routerFilePath.length() - 1) {
            return routerFilePath.substring(lastSlash + 1);
        }
        return routerFilePath;
    }


    public StructureGraph run(String projectName, List<VueFile> files) {
        this.vueFiles = files;
        Map<String, Set<String>> componentUsageIndex = buildComponentUsageIndex(files);
        globalGraph.getNodes().clear();
        globalGraph.getEdges().clear();
        extractStaticStructure(files, componentUsageIndex);
        stage1Snapshot = deepCopyGraph(globalGraph);
        validateAndEnhanceWithLLM(files);
        stage2Snapshot = deepCopyGraph(globalGraph);
        canonicalizeEdges(globalGraph);
        return globalGraph;
    }


    private void extractStaticStructure(List<VueFile> files, Map<String, Set<String>> componentUsageIndex) {
        extractRouterEdges();

        int totalTasks = 0;
        for (VueFile file : files) {
            String componentPath = file.getPath();
            Set<String> associatedRoutes = new LinkedHashSet<>();
            String directRoute = routeTable.getRoutePathByComponent(componentPath);
            if (!"UNKNOWN".equals(directRoute)) {
                associatedRoutes.add(directRoute);
            }
            Set<String> usedByRoutes = componentUsageIndex.getOrDefault(componentPath, Set.of());
            associatedRoutes.addAll(usedByRoutes);
            totalTasks += associatedRoutes.size();
        }

        ProgressBar progressBar = new ProgressBar(totalTasks);

        for (VueFile file : files) {
        System.out.println("\n[Stage 1] Processing: " + file.getPath());
        StaticFacts facts = extractFacts(file);
        String componentPath = file.getPath();
        Set<String> associatedRoutes = new LinkedHashSet<>();
        String directRoute = routeTable.getRoutePathByComponent(componentPath);
        if (!"UNKNOWN".equals(directRoute)) {
            associatedRoutes.add(directRoute);
        }

        Set<String> usedByRoutes = componentUsageIndex.getOrDefault(componentPath, Set.of());
        associatedRoutes.addAll(usedByRoutes);
        System.out.println("[Stage 1]   Component: " + componentPath + " (routes: " + associatedRoutes.size() + ")");
        if (associatedRoutes.isEmpty()) {
            System.out.println("[Stage 1]   Skipping: " + file.getPath() + " (not a page component)");
            continue;
        }

        Map<String, String> redirects = routeTable.getRedirects();
        String redirectTargetRoute = null;
        boolean isLayoutComponent = false;
        if (redirects != null && directRoute != null && redirects.containsKey(directRoute)) {
            redirectTargetRoute = redirects.get(directRoute);
            Set<String> directChildren = routeTable.getDirectChildren(directRoute);
            isLayoutComponent = !directChildren.isEmpty();

            if (isLayoutComponent && directChildren != null) {
                associatedRoutes.addAll(directChildren);
            }
        }

        for (String routePath : associatedRoutes) {
            if (redirects != null && redirects.containsKey(routePath)) {
                progressBar.step(file.getPath(), routePath);
                continue;
            }

            if (redirectTargetRoute != null && directRoute != null) {
                if (!isLayoutComponent) {
                    if (!directRoute.equals("/") && !routePath.equals(redirectTargetRoute)) {
                        progressBar.step(file.getPath(), routePath);
                        continue;
                    }
                } else {
                    if (!isRouteInMenu(file, routePath)) {
                        progressBar.step(file.getPath(), routePath);
                        continue;
                    }
                }
            }

            StructureGraph staticGraph = emptyGraph(routePath);
            addStaticEdgesFromFacts(staticGraph, routePath, facts, file.getPath(), file);
            mergeGraph(globalGraph, staticGraph);
            progressBar.step(file.getPath(), routePath);
        }
    }

    addImplicitChildEdges(globalGraph, routeTable);
    }


    private boolean hasConditionalMenuRendering(VueFile file) {
        if (file == null || file.getContent() == null) return false;

        String content = file.getContent();

        Pattern conditionalAssignment = Pattern.compile(
            "if\\s*\\([^)]+\\)\\s*\\{[^}]*(this\\.(items|menuList|menus|menuItems))\\s*=\\s*\\[",
            Pattern.DOTALL
        );
        if (conditionalAssignment.matcher(content).find()) {
            return true;
        }

        if (content.contains("computed(") &&
            (content.contains("menuItems") || content.contains("menuList") || content.contains("menus")) &&
            (content.contains("role") || content.contains("Role"))) {
            return true;
        }

        Pattern methodConditional = Pattern.compile(
            "methods\\s*:\\s*\\{[^}]*if\\s*\\([^)]*role[^)]*\\)[^}]*(this\\.(items|menuList|menus))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        if (methodConditional.matcher(content).find()) {
            return true;
        }

        return false;
    }

    private boolean isRouteInMenu(VueFile layoutFile, String routePath) {
        if (layoutFile == null || routePath == null) return false;

        String content = layoutFile.getContent();
        if (content == null) return false;

        if (content.matches("(?s).*index\\s*=\\s*[\"']" + Pattern.quote(routePath) + "[\"'].*")) {
            return true;
        }

        Map<String, String> menuTexts = extractMenuTextsFromVueFile(layoutFile);
        if (menuTexts.containsKey(routePath)) {
            return true;
        }

        if (content.matches("(?s).*to\\s*=\\s*[\"']" + Pattern.quote(routePath) + "[\"'].*")) {
            return true;
        }

        return false;
    }


    private String findParentRouteWithChildren(String routePath) {
        Map<String, List<String>> children = routeTable.getChildren();
        if (children == null) return null;

        for (Map.Entry<String, List<String>> entry : children.entrySet()) {
            if (entry.getValue().contains(routePath)) {
                return entry.getKey();
            }
        }
        return null;
    }


    private static class MenuItemInfo {
        String path;
        String text;
        boolean isInSubmenu;
        String className;

        MenuItemInfo(String path, String text, boolean isInSubmenu) {
            this.path = path;
            this.text = text;
            this.isInSubmenu = isInSubmenu;
            this.className = null;
        }
    }


    private String extractRouterLinkClass(String content) {
        Pattern pattern = Pattern.compile("<router-link[^>]*?\\sclass=\"([^\"]+)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String classAttr = matcher.group(1);
            String[] classes = classAttr.split("\\s+");
            if (classes.length > 0 && !classes[0].isBlank()) {
                return classes[0];
            }
        }
        return null;
    }


    private List<MenuItemInfo> extractMenuItemsWithContext(VueFile vueFile) {
        List<MenuItemInfo> menuItems = new ArrayList<>();
        if (vueFile == null || vueFile.getContent() == null) {
            return menuItems;
        }

        String content = vueFile.getContent();

        String routerLinkClass = extractRouterLinkClass(content);

        int pos = 0;
        while (pos < content.length()) {
            int returnPos = content.indexOf("return", pos);
            int itemsPos = content.indexOf("items", pos);
            int tabsPos = content.indexOf("tabs", pos);
            int menusPos = content.indexOf("menus", pos);
            int menuListPos = content.indexOf("menuList", pos);

            int searchPos = -1;
            int[] positions = {returnPos, itemsPos, tabsPos, menusPos, menuListPos};
            for (int p : positions) {
                if (p != -1 && (searchPos == -1 || p < searchPos)) {
                    searchPos = p;
                }
            }

            if (searchPos == -1) {
                break;
            }

            int arrayStart = content.indexOf('[', searchPos);
            if (arrayStart == -1 || arrayStart - searchPos > 30) {
                pos = searchPos + 6;
                continue;
            }

            int arrayEnd = findMatchingBracket(content, arrayStart);
            if (arrayEnd == -1) {
                pos = searchPos + 6;
                continue;
            }

            String menuArrayContent = content.substring(arrayStart + 1, arrayEnd);

            if (menuArrayContent.contains("path") && (menuArrayContent.contains("label") || menuArrayContent.contains("name"))) {
                parseMenuArray(menuArrayContent, menuItems, false);
            }

            pos = arrayEnd + 1;
        }

        if (routerLinkClass != null) {
            for (MenuItemInfo item : menuItems) {
                item.className = routerLinkClass;
            }
        }

        return menuItems;
    }


    private void parseMenuArray(String arrayContent, List<MenuItemInfo> result, boolean isInSubmenu) {
        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', pos);
            if (objStart == -1) break;

            int objEnd = findMatchingBrace(arrayContent, objStart);
            if (objEnd == -1) break;

            String objContent = arrayContent.substring(objStart + 1, objEnd);

            Map<String, String> props = new HashMap<>();
            Pattern propPattern = Pattern.compile("(\\w+)\\s*:\\s*['\"]([^'\"]+)['\"]");
            Matcher propMatcher = propPattern.matcher(objContent);
            while (propMatcher.find()) {
                props.put(propMatcher.group(1), propMatcher.group(2));
            }

            boolean isSubmenu = "submenu".equals(props.get("type"));
            boolean hasChildren = objContent.contains("children");

            if (isSubmenu || hasChildren) {
                int childrenStart = objContent.indexOf("children");
                if (childrenStart != -1) {
                    int arrayStart = objContent.indexOf('[', childrenStart);
                    if (arrayStart != -1) {
                        int arrayEnd = findMatchingBracket(objContent, arrayStart);
                        if (arrayEnd != -1) {
                            String childrenContent = objContent.substring(arrayStart + 1, arrayEnd);
                            parseMenuArray(childrenContent, result, true);
                        }
                    }
                }
            } else {
                String path = props.get("path");
                String text = props.get("label");
                if (text == null) text = props.get("name");
                if (text == null) text = props.get("title");

                if (path != null && text != null && !path.isBlank() && !text.isBlank()) {
                    String fullPath = path.startsWith("/") ? path : "/" + path;
                    result.add(new MenuItemInfo(fullPath, text, isInSubmenu));
                }
            }

            pos = objEnd + 1;
        }
    }


    private int findMatchingBracket(String content, int start) {
        int depth = 1;
        for (int i = start + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }


    private Map<String, String> extractMenuTextsFromVueFile(VueFile vueFile) {
        Map<String, String> routeTexts = new HashMap<>();
        if (vueFile == null || vueFile.getContent() == null) {
            return routeTexts;
        }

        String content = vueFile.getContent();


        Pattern pattern1 = Pattern.compile(
            "\\{\\s*path\\s*:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name\\s*:\\s*['\"]([^'\"]+)['\"]\\s*\\}|" +
            "\\{\\s*name\\s*:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*path\\s*:\\s*['\"]([^'\"]+)['\"]\\s*\\}"
        );
        Matcher matcher1 = pattern1.matcher(content);
        while (matcher1.find()) {
            String path = matcher1.group(1) != null ? matcher1.group(1) : matcher1.group(4);
            String name = matcher1.group(2) != null ? matcher1.group(2) : matcher1.group(3);
            if (name != null && path != null && !name.isBlank() && !path.isBlank()) {
                String fullPath = path.startsWith("/") ? path : "/" + path;
                routeTexts.put(fullPath, name);
            }
        }

        Pattern pattern2 = Pattern.compile(
            "\\{[^}]*label\\s*:\\s*['\"]([^'\"]+)['\"][^}]*path\\s*:\\s*['\"]([^'\"]+)['\"][^}]*\\}|" +
            "\\{[^}]*path\\s*:\\s*['\"]([^'\"]+)['\"][^}]*label\\s*:\\s*['\"]([^'\"]+)['\"][^}]*\\}"
        );
        Matcher matcher2 = pattern2.matcher(content);
        while (matcher2.find()) {
            String label = matcher2.group(1) != null ? matcher2.group(1) : matcher2.group(4);
            String path = matcher2.group(2) != null ? matcher2.group(2) : matcher2.group(3);
            if (label != null && path != null && !label.isBlank() && !path.isBlank()) {
                String fullPath = path.startsWith("/") ? path : "/" + path;
                routeTexts.put(fullPath, label);
            }
        }

        Pattern pattern3 = Pattern.compile(
            "\\{[^}]*title\\s*:\\s*['\"]([^'\"]+)['\"][^}]*path\\s*:\\s*['\"]([^'\"]+)['\"][^}]*\\}|" +
            "\\{[^}]*path\\s*:\\s*['\"]([^'\"]+)['\"][^}]*title\\s*:\\s*['\"]([^'\"]+)['\"][^}]*\\}"
        );
        Matcher matcher3 = pattern3.matcher(content);
        while (matcher3.find()) {
            String title = matcher3.group(1) != null ? matcher3.group(1) : matcher3.group(4);
            String path = matcher3.group(2) != null ? matcher3.group(2) : matcher3.group(3);
            if (title != null && path != null && !title.isBlank() && !path.isBlank()) {
                String fullPath = path.startsWith("/") ? path : "/" + path;
                routeTexts.put(fullPath, title);
            }
        }

        Pattern arrayPattern = Pattern.compile(
            "(children|items|menus|menuList|tabs)\\s*:\\s*\\[([^\\]]+)\\]",
            Pattern.DOTALL
        );
        Matcher arrayMatcher = arrayPattern.matcher(content);
        while (arrayMatcher.find()) {
            String arrayBlock = arrayMatcher.group(2);

            Pattern objectPattern = Pattern.compile(
                "\\{([^}]+)\\}",
                Pattern.DOTALL
            );
            Matcher objectMatcher = objectPattern.matcher(arrayBlock);
            while (objectMatcher.find()) {
                String objectContent = objectMatcher.group(1);

                Map<String, String> properties = new HashMap<>();
                Pattern propertyPattern = Pattern.compile(
                    "(\\w+)\\s*:\\s*['\"]([^'\"]+)['\"]"
                );
                Matcher propertyMatcher = propertyPattern.matcher(objectContent);
                while (propertyMatcher.find()) {
                    String key = propertyMatcher.group(1);
                    String value = propertyMatcher.group(2);
                    properties.put(key, value);
                }

                String pathValue = null;
                String textValue = null;

                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    String key = entry.getKey().toLowerCase();
                    String value = entry.getValue();

                    if (pathValue == null && (key.contains("path") || key.contains("route") || key.contains("url"))) {
                        pathValue = value;
                    }
                    if (textValue == null && (key.contains("name") || key.contains("label") ||
                                             key.contains("title") || key.contains("text"))) {
                        textValue = value;
                    }
                }

                if (pathValue != null && textValue != null && !pathValue.isBlank() && !textValue.isBlank()) {
                    String fullPath = pathValue.startsWith("/") ? pathValue : "/" + pathValue;
                    routeTexts.put(fullPath, textValue);
                }
            }
        }

        return routeTexts;
    }


    private void extractRouterEdges() {
        Map<String, String> redirects = routeTable.getRedirects();
        if (redirects != null) {
            for (Map.Entry<String, String> entry : redirects.entrySet()) {
                Edge e = new Edge();
                e.setFrom(entry.getKey());
                e.setTo(entry.getValue());
                e.setSelector("-");
                e.setSelectorKind("ROUTER");
                e.setEvent("redirect");
                e.setSourceFile(getRouterFileName());
                e.setExtractionMethod("AST");
                globalGraph.addEdge(e);
            }
        }
    }


    private Set<Integer> getRouteRoles(String routePath) {
        Set<Integer> roles = new HashSet<>();
        if (routePath == null) return roles;

        try {
            for (VueFile file : vueFiles) {
                String filePath = file.getPath();
                if (isRouterFile(filePath)) {
                    String content = file.getContent();
                    if (content != null) {
                        String escapedPath = Pattern.quote(routePath);

                        Pattern pattern = Pattern.compile(
                            "['\"]?path['\"]?\\s*:\\s*['\"]" + escapedPath + "['\"].*?['\"]?meta['\"]?\\s*:\\s*\\{[^}]*['\"]?roles['\"]?\\s*:\\s*\\[([^\\]]+)\\]",
                            Pattern.DOTALL
                        );
                        Matcher matcher = pattern.matcher(content);
                        if (matcher.find()) {
                            String rolesStr = matcher.group(1);
                            String[] roleArr = rolesStr.split(",");
                            for (String role : roleArr) {
                                try {
                                    roles.add(Integer.parseInt(role.trim()));
                                } catch (NumberFormatException e) {
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[getRouteRoles] Error: " + e.getMessage());
        }
        return roles;
    }


    public static void addImplicitChildEdges(StructureGraph graph, RouteTable routeTable) {
        Set<String> allPaths = routeTable.getAllPaths();
        if (allPaths == null || allPaths.isEmpty()) return;

        List<String> sorted = new ArrayList<>(allPaths);
        sorted.sort(Comparator.comparingInt(String::length));

        Set<String> existingNodes = new HashSet<>();
        for (PageNode n : graph.getNodes()) {
            existingNodes.add(n.getName());
        }

        Set<String> existingEdges = new HashSet<>();
        for (Edge e : graph.getEdges()) {
            String key = e.getFrom() + "→" + e.getTo() + "→" + (e.getEvent() == null ? "" : e.getEvent());
            existingEdges.add(key);
        }

        for (String path : sorted) {
            if (!existingNodes.contains(path)) {
                graph.addNode(new PageNode(path));
                existingNodes.add(path);
            }
        }

        Map<String, List<String>> children = routeTable.getChildren();
        if (children != null) {
            for (Map.Entry<String, List<String>> entry : children.entrySet()) {
                String parent = entry.getKey();
                List<String> childList = entry.getValue();

                if (childList == null) continue;

                for (String child : childList) {
                    String edgeKey = parent + "→" + child + "→route";
                    if (existingEdges.contains(edgeKey)) continue;

                    Edge e = new Edge();
                    e.setFrom(parent);
                    e.setTo(child);
                    e.setSelector("-");
                    e.setEvent("route");
                    e.setSelectorKind("ROUTER");
                    e.setSourceFile("router-config");
                    e.setExtractionMethod("AST");
                    graph.addEdge(e);

                    existingEdges.add(edgeKey);
                }
            }
        }


        Map<String, String> redirects = routeTable.getRedirects();
        if (redirects != null) {
            for (Map.Entry<String, String> entry : redirects.entrySet()) {
                String from = entry.getKey();
                String to = entry.getValue();
                if (from == null || to == null) continue;

                String edgeKey = from + "→" + to + "→redirect";
                if (existingEdges.contains(edgeKey)) continue;

                Edge e = new Edge();
                e.setFrom(from);
                e.setTo(to);
                e.setSelector("-");
                e.setEvent("redirect");
                e.setSelectorKind("ROUTER");
                graph.addEdge(e);
                existingEdges.add(edgeKey);
            }
        }
    }


    private void validateAndEnhanceWithLLM(List<VueFile> files) {
        // Group edges by source file
        Map<String, List<Edge>> edgesByFile = new HashMap<>();
        for (Edge edge : globalGraph.getEdges()) {
            String sourceFile = edge.getSourceFile();
            if (sourceFile == null || sourceFile.isBlank()) {
                continue;
            }
            edgesByFile.computeIfAbsent(sourceFile, k -> new ArrayList<>()).add(edge);
        }

        System.out.println("[Stage 2] Grouped " + globalGraph.getEdges().size() + " edges into " + edgesByFile.size() + " files.");

        Map<String, VueFile> fileMap = new HashMap<>();
        for (VueFile file : files) {
            fileMap.put(file.getPath(), file);
        }

        List<Edge> enhancedEdges = new ArrayList<>();
        int processedFiles = 0;

        for (Map.Entry<String, List<Edge>> entry : edgesByFile.entrySet()) {
            String filePath = entry.getKey();
            List<Edge> fileEdges = entry.getValue();

            processedFiles++;
            System.out.println("\n[Stage 2] Processing file " + processedFiles + "/" + edgesByFile.size() + ": " + filePath);
            System.out.println("[Stage 2] Validating " + fileEdges.size() + " edges...");

            if (isRouterFile(filePath)) {
                System.out.println("[Stage 2] ✓ Skipping LLM for router config file edges (keep as-is)");
                for (Edge edge : fileEdges) {
                    edge.setExtractionMethod("AST");
                    edge.setIsValid(true);
                }
                enhancedEdges.addAll(fileEdges);
                System.out.println("[Stage 2] ✓ Kept " + fileEdges.size() + "/" + fileEdges.size() + " edges");
                continue;
            }

            VueFile file = fileMap.get(filePath);
            if (file == null) {
                System.err.println("[Stage 2] WARNING: File not found: " + filePath + ", skipping.");
                continue;
            }

            final int BATCH_SIZE = 30;

            if (fileEdges.size() > BATCH_SIZE) {
                System.out.println("[Stage 2] Large file detected (" + fileEdges.size() + " edges), processing in batches of " + BATCH_SIZE);

                List<Edge> allValidatedEdges = new ArrayList<>();
                int batchCount = (int) Math.ceil((double) fileEdges.size() / BATCH_SIZE);

                for (int i = 0; i < batchCount; i++) {
                    int start = i * BATCH_SIZE;
                    int end = Math.min(start + BATCH_SIZE, fileEdges.size());
                    List<Edge> batchEdges = fileEdges.subList(start, end);

                    System.out.println("[Stage 2]   Batch " + (i + 1) + "/" + batchCount + ": Processing edges " + start + "-" + (end - 1));
                    List<Edge> validatedBatch = validateEdgesWithLLM(batchEdges, file);
                    allValidatedEdges.addAll(validatedBatch);
                }

                enhancedEdges.addAll(allValidatedEdges);
                System.out.println("[Stage 2] ✓ Validated " + allValidatedEdges.size() + "/" + fileEdges.size() + " edges (in " + batchCount + " batches)");
            } else {
                List<Edge> validatedEdges = validateEdgesWithLLM(fileEdges, file);
                enhancedEdges.addAll(validatedEdges);
                System.out.println("[Stage 2] ✓ Validated " + validatedEdges.size() + "/" + fileEdges.size() + " edges");
            }
        }

        globalGraph.setEdges(enhancedEdges);

        System.out.println("\n[Stage 2] Enhanced " + enhancedEdges.size() + " edges total.");
    }


    private List<Edge> validateEdgesWithLLM(List<Edge> edges, VueFile file) {
        List<Edge> validatedEdges = new ArrayList<>();

        String elementUIInfo = isElementUI2 ?
            "Element UI 2.x (Vue2): Use .el-submenu (no hyphen)" :
            "Element Plus (Vue3): Use .el-sub-menu (with hyphen)";

        String systemPrompt = String.format("""
                You are a Vue.js navigation graph expert. Your task is to ENHANCE navigation edges, NOT to filter them.
                
                PROJECT INFO:
                - UI Framework: %s
                
                SELECTOR RULES:
                1. Button: <el-button> renders as <button> tag
                   - Use: button:has-text('Login')
                   - Not: .el-button:has-text('Login')
                
                2. Submenu: Convert .el-sub-menu based on framework
                   - Element UI 2.x: .el-submenu (no hyphen)
                   - Element Plus: .el-sub-menu (with hyphen)
                
                3. Dropdown item: <el-dropdown-item> renders as <li class="el-dropdown-menu__item">
                   - Use: .el-dropdown-menu__item:has-text('Logout')
                   - Not: .el-dropdown-item:has-text('Logout')
                
                YOUR TASKS:
                1. **Enhance selector**: Convert generic selectors to specific Playwright selectors with text
                2. **Fix selector format**: Convert .el-sub-menu to correct format based on UI framework
                3. **Extract conditions**: Find v-if/v-show conditions from source code
                4. **Mark ALL edges as valid**: Set isValid=true for ALL edges
                
                CRITICAL RULES:
                - You MUST return ALL input edges with isValid=true
                - Do NOT filter out or remove any edges
                - Your role is ENHANCEMENT ONLY, not validation or filtering
                - Downstream processing will handle normalization, deduplication, and filtering
                - Even if an edge seems incorrect, include it with isValid=true
                
                SPECIAL HANDLING FOR ROUTER CONFIG FILE EDGES:
                - Edges from router configuration files (redirect, beforeEach) have selector="-"
                - Keep these edges EXACTLY as-is without any modifications
                - Do NOT attempt to enhance or change selector="-" for router config edges
                - These represent programmatic navigation, not UI-based navigation
                
                SELECTOR ENHANCEMENT REQUIREMENTS:
                - **ALWAYS** enhance generic selectors by adding text content from source code
                - Generic selectors include: "button", ".el-button", "a", "span"
                - Target format: selector:has-text('text_content')
                - **EXCEPTION**: Never modify selector="-" (router config edges)
                - If text cannot be extracted, keep the original selector unchanged
                
                TEXT EXTRACTION RULES:
                1. Preserve selector type - only append text information
                   - "button" remains "button" (becomes "button:has-text('Login')")
                   - ".el-menu-item" remains ".el-menu-item" (becomes ".el-menu-item:has-text('Home')")
                   - Never change selector type (e.g., "button" to ".el-dropdown-item")
                
                2. Skip selectors that already contain text:
                   - ".el-menu-item:has-text('Home')" → no change needed
                
                3. Enhance generic selectors by appending :has-text():
                   - "button" → "button:has-text('Login')"
                   - ".el-dialog__body button" → ".el-dialog__body button:has-text('Submit')"
                
                4. When text extraction fails:
                   - Return the original selector without modification
                   - Do NOT invent or guess text content
                
                CONDITION EXTRACTION:
                1. Template-level conditions (v-if, v-show):
                   - Search for v-if or v-show directives on the element or parent elements
                   - Extract the exact condition expression as written in the template
                   - Examples:
                     * v-if="user.role === 1" → condition: "user.role === 1"
                     * v-show="isLoggedIn" → condition: "isLoggedIn"
                
                2. Script-level conditions (conditional menu data):
                   - When the file contains conditional menu rendering logic
                   - Analyze the script section to determine condition branches for menu items
                   - For cross-branch edges (from-page and to-page in different condition branches):
                     * Use format: "CROSS_ROLE: <from_condition> -> <to_condition>"
                   - Example:
                     * if (roles == 'admin') { items = [{path: 'houselist'}] }
                       else { items = [{path: 'personalHouseList'}] }
                     * Edge: /houselist → /personalHouseList
                       → condition: "CROSS_ROLE: roles=='admin' -> roles!='admin'"
                
                3. No condition found:
                   - Set condition: null (not empty string, use null)
                
                OUTPUT FORMAT:
                {
                  "edges": [
                    {
                      "from": "/page",
                      "to": "/page2",
                      "selector": "button:has-text('Login')",
                      "event": "click",
                      "condition": null,
                      "isValid": true,
                      "llmReasoning": "Enhanced selector from button text, clear navigation intent"
                    }
                  ]
                }
                
                Return ONLY valid JSON without markdown code blocks.
                """, elementUIInfo);

        String userPrompt = buildValidationPrompt(edges, file);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                System.out.println("[Stage 2]   Calling LLM (attempt " + attempt + "/3)...");
                String raw = llm.ask(systemPrompt, userPrompt);

                List<Edge> parsedEdges = parseValidationResponse(raw, edges);

                if (validateLLMResponse(parsedEdges, edges)) {
                    validatedEdges = parsedEdges;
                    System.out.println("[Stage 2]   ✓ LLM validation successful");
                    break;
                } else {
                    System.err.println("[Stage 2]   ✗ Response validation failed, retrying...");
                    userPrompt = buildReflectionPromptForValidation(userPrompt, raw, parsedEdges);
                }
            } catch (Exception e) {
                System.err.println("[Stage 2]   ✗ LLM request failed (attempt " + attempt + "): " + e.getMessage());
            }
        }

        if (validatedEdges.isEmpty()) {
            System.err.println("[Stage 2]   ⚠ All attempts failed, keeping original edges");
            for (Edge edge : edges) {
                edge.setExtractionMethod("AST");
                validatedEdges.add(edge);
            }
        }

        return validatedEdges;
    }


    private String buildValidationPrompt(List<Edge> edges, VueFile file) {
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(file.getPath()).append("\n\n");

        boolean hasConditionalMenu = hasConditionalMenuRendering(file);

        if (hasConditionalMenu) {
            sb.append("Source code (with conditional menu rendering):\n```vue\n");
            sb.append(file.getContent());
            sb.append("\n```\n\n");
            sb.append("⚠️ IMPORTANT: This file contains conditional menu rendering based on user roles.\n");
            sb.append("Please analyze the script section to identify cross-role edges and mark them with condition=\"CROSS_ROLE: <from_condition> -> <to_condition>\".\n\n");
        } else {
            String content = file.getContent();
            String template = extractTemplateSection(content);
            if (template != null && !template.isBlank()) {
                sb.append("Source code (template only):\n```vue\n");
                sb.append("<template>\n");
                sb.append(template);
                sb.append("\n</template>\n");
                sb.append("```\n\n");
            } else {
                sb.append("Source code:\n```vue\n");
                sb.append(content);
                sb.append("\n```\n\n");
            }
        }

        sb.append("Edges to validate (").append(edges.size()).append(" total):\n");
        sb.append(JSON.toJSONString(edges));
        sb.append("\n\n");

        sb.append("Please validate each edge and return JSON in this format:\n");
        sb.append("{\n");
        sb.append("  \"edges\": [\n");
        sb.append("    {\n");
        sb.append("      \"from\": \"/dashboard\",\n");
        sb.append("      \"to\": \"/user\",\n");
        sb.append("      \"selector\": \".el-menu-item:has-text('Reader Management')\",\n");
        sb.append("      \"event\": \"click\",\n");
        sb.append("      \"condition\": null,\n");
        sb.append("      \"isValid\": true,\n");
        sb.append("      \"llmReasoning\": \"Clear navigation menu item\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }


    private String extractTemplateSection(String content) {
        if (content == null) return null;

        Pattern pattern = Pattern.compile("<template[^>]*>(.+?)</template>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private List<Edge> parseValidationResponse(String raw, List<Edge> originalEdges) {
        List<Edge> result = new ArrayList<>();

        try {
            Object contentObj = com.jayway.jsonpath.JsonPath.read(raw, "$.choices[0].message.content");
            String json = (contentObj instanceof String)
                    ? (String) contentObj
                    : vue.llm.util.JsonUtil.toJson(contentObj);

            json = json.replace("\uFEFF", "").trim();
            json = vue.llm.util.SelfCorrector.extractJson(json);

            System.out.println("[Stage2] Extracted JSON: " + json);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = (Map<String, Object>) JSON.parseObject(json, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edgesData = (List<Map<String, Object>>) response.get("edges");

            if (edgesData == null) {
                System.err.println("[Stage2] No 'edges' field in LLM response.");
                return result;
            }

            for (int i = 0; i < edgesData.size(); i++) {
                Map<String, Object> edgeData = edgesData.get(i);
                Edge original = i < originalEdges.size() ? originalEdges.get(i) : null;

                Edge edge = new Edge();
                edge.setFrom((String) edgeData.get("from"));
                edge.setTo((String) edgeData.get("to"));
                edge.setSelector((String) edgeData.get("selector"));
                edge.setEvent((String) edgeData.get("event"));
                edge.setCondition((String) edgeData.get("condition"));

                Object validObj = edgeData.get("isValid");
                if (validObj instanceof Boolean) {
                    edge.setIsValid((Boolean) validObj);
                } else {
                    edge.setIsValid(true);
                }

                edge.setLlmReasoning((String) edgeData.get("llmReasoning"));
                edge.setExtractionMethod("AST+LLM");

                if (original != null) {
                    edge.setSourceFile(original.getSourceFile());
                    edge.setRawAstSelector(original.getRawAstSelector());
                }

                result.add(edge);
            }

        } catch (Exception e) {
            System.err.println("[Stage2] Failed to parse LLM response: " + e.getMessage());
        }

        return result;
    }


    private boolean validateLLMResponse(List<Edge> parsedEdges, List<Edge> originalEdges) {
        if (parsedEdges.isEmpty()) {
            System.err.println("[Stage2] Validation failed: No edges parsed.");
            return false;
        }

        if (parsedEdges.size() != originalEdges.size()) {
            System.err.println("[Stage2] Validation failed: Edge count mismatch. Expected " +
                originalEdges.size() + ", got " + parsedEdges.size() +
                ". LLM must return ALL edges (filtering is done in Stage 3).");
            return false;
        }

        for (Edge edge : parsedEdges) {
            if (edge.getFrom() == null || edge.getFrom().isBlank()) {
                System.err.println("[Stage2] Validation failed: Edge missing 'from' field.");
                return false;
            }
            if (edge.getTo() == null || edge.getTo().isBlank()) {
                System.err.println("[Stage2] Validation failed: Edge missing 'to' field.");
                return false;
            }
            if (edge.getSelector() == null) {
                System.err.println("[Stage2] Validation failed: Edge missing 'selector' field.");
                return false;
            }
            if (!routeTable.exists(edge.getTo())) {
                System.err.println("[Stage2] Validation failed: Invalid route: " + edge.getTo());
                return false;
            }
        }

        return true;
    }


    private String buildReflectionPromptForValidation(String originalPrompt, String llmResponse, List<Edge> parsedEdges) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous response had issues. Please fix them and try again.\n\n");
        sb.append("Previous response:\n");
        sb.append(llmResponse);
        sb.append("\n\nIssues:\n");

        if (parsedEdges.isEmpty()) {
            sb.append("- No edges were parsed from your response\n");
        } else {
            sb.append("- Some edges have missing or invalid fields\n");
        }

        sb.append("\nPlease return valid JSON with all required fields.\n\n");
        sb.append("Original task:\n");
        sb.append(originalPrompt);

        return sb.toString();
    }

    private Map<String, Set<String>> buildComponentUsageIndex(List<VueFile> files) {
        Map<String, Set<String>> result = new HashMap<>();

        System.out.println("\n[Stage 1] Building component dependency graph...");

        Map<String, VueFile> fileMap = new HashMap<>();
        for (VueFile file : files) {
            fileMap.put(file.getPath(), file);
        }

        Map<String, List<String>> componentImports = new HashMap<>();
        Map<String, Map<String, String>> componentConditionsMap = new HashMap<>();

        for (VueFile file : files) {
            StaticFacts facts = extractFacts(file);
            if (facts.importedComponents != null && !facts.importedComponents.isEmpty()) {
                List<String> importedPaths = new ArrayList<>();
                for (StaticFacts.ImportedComponent comp : facts.importedComponents) {
                    String resolvedPath = resolveComponentPath(file.getPath(), comp.path);
                    if (resolvedPath != null) {
                        importedPaths.add(resolvedPath);
                        System.out.println("[Stage 1]   " + file.getPath() + " imports " + resolvedPath);
                    } else {
                        System.out.println("[Stage 1]   WARNING: Failed to resolve " + comp.path + " from " + file.getPath());
                    }
                }
                if (!importedPaths.isEmpty()) {
                    componentImports.put(file.getPath(), importedPaths);
                }
            }

            if (facts.componentConditions != null && !facts.componentConditions.isEmpty()) {
                componentConditionsMap.put(file.getPath(), facts.componentConditions);
            }
        }

        Map<String, String> componentToRoute = new HashMap<>();
        Map<String, List<String>> componentToAllDirectRoutes = new HashMap<>();

        for (VueFile file : files) {
            List<String> allRoutePaths = routeTable.getAllRoutePathsByComponent(file.getPath());
            if (!allRoutePaths.isEmpty()) {
                List<String> filteredPaths = allRoutePaths;
                if (allRoutePaths.size() == 2 && allRoutePaths.contains("/")) {
                    filteredPaths = allRoutePaths.stream()
                            .filter(path -> !path.equals("/"))
                            .collect(java.util.stream.Collectors.toList());
                }

                componentToRoute.put(file.getPath(), filteredPaths.get(0));
                componentToAllDirectRoutes.put(file.getPath(), filteredPaths);
                result.computeIfAbsent(file.getPath(), k -> new LinkedHashSet<>()).addAll(filteredPaths);

                if (filteredPaths.size() > 1) {
                    System.out.println("[Stage 1]   " + file.getPath() + " is used by " + filteredPaths.size() + " routes: " + filteredPaths);
                }
            }
        }


        System.out.println("\n[Stage 1] Propagating edges based on component dependencies...");

        Map<String, List<String>> routeChildren = routeTable.getChildren();
        Map<String, Set<String>> componentToAllRoutes = new HashMap<>();
        for (String componentPath : fileMap.keySet()) {
            Set<String> routes = findAllRoutesUsingComponent(componentPath, componentImports, componentToAllDirectRoutes, routeChildren, componentConditionsMap, new HashSet<>());
            if (!routes.isEmpty()) {
                componentToAllRoutes.put(componentPath, routes);
                result.put(componentPath, routes);
                System.out.println("[Stage 1]   " + componentPath + " → " + routes.size() + " routes: " + routes);
            } else {
                if (componentPath.contains("NavBar") || componentPath.contains("SideBar")) {
                    System.out.println("[Stage 1]   DEBUG: " + componentPath + " has NO routes!");
                }
            }
        }

        System.out.println("\n[Stage 1] Component dependency analysis complete.");
        System.out.println("[Stage 1] Total components with routes: " + result.size());

        return result;
    }


    private String buildSelectorFromDomNode(DomNode node) {
        if (node == null){
            return null;
        };
        String text = node.text != null ? node.text.trim() : null;
        String tag = node.tag;
        String cls = node.attrs != null ? node.attrs.get("class") : null;
        String id = node.attrs != null ? node.attrs.get("id") : null;
        String parentPath = node.parentPath;
        String parentTag = node.parentTag;

        String currentSelector = null;

        if (id != null && !id.isBlank()) {
            currentSelector = "#" + id;
        }
        else if (tag != null && tag.startsWith("el-")) {
            if (tag.equals("el-menu-item")) {
                if (text != null && !text.isBlank()) {
                    currentSelector = ".el-menu-item:has-text('" + text + "')";
                } else {
                    currentSelector = ".el-menu-item";
                }
            } else if (tag.equals("el-sub-menu") || tag.equals("el-submenu")) {
                String normalizedTag = isElementUI2 ? "el-submenu" : "el-sub-menu";
                if (text != null && !text.isBlank()) {
                    currentSelector = "." + normalizedTag + ":has-text('" + text + "')";
                } else {
                    currentSelector = "." + normalizedTag;
                }
            } else if (tag.equals("el-dropdown-item")) {
                if (text != null && !text.isBlank()) {
                    currentSelector = ".el-dropdown-menu__item:has-text('" + text + "')";
                } else {
                    currentSelector = ".el-dropdown-menu__item";
                }
            } else if (tag.equals("el-button")) {
                if (text != null && !text.isBlank()) {
                    currentSelector = "button:has-text('" + text + "')";
                } else {
                    currentSelector = "button";
                }
            } else if (tag.equals("el-avatar")) {
                if (text != null && !text.isBlank()) {
                    currentSelector = ".el-avatar:has-text('" + text + "')";
                } else {
                    currentSelector = ".el-avatar";
                }
            } else {
                if (text != null && !text.isBlank()) {
                    currentSelector = "." + tag + ":has-text('" + text + "')";
                } else {
                    currentSelector = "." + tag;
                }
            }
        }
        else if (cls != null && !cls.isBlank() && text != null && !text.isBlank()) {
            String firstClass = cls.split("\\s+")[0];
            currentSelector = "." + firstClass + ":has-text('" + text + "')";
        }
        else if (tag != null && !tag.isBlank() && text != null && !text.isBlank()) {
            currentSelector = tag + ":has-text('" + text + "')";
        }
        else if (cls != null && !cls.isBlank()) {
            String firstClass = cls.split("\\s+")[0];
            currentSelector = "." + firstClass;
        }
        else if (text != null && !text.isBlank()) {
            currentSelector = "text='" + text + "'";
        }
        else if (tag != null && !tag.isBlank()) {
            if (tag.startsWith("el-")) {
                currentSelector = "." + tag;
            } else {
                currentSelector = tag;
            }
        }

        if (currentSelector == null) {
            return null;
        }

        if (parentPath != null && !parentPath.isBlank()) {
            String keyParent = null;

            if (currentSelector.startsWith(".el-menu-item")) {
                if (parentTag != null && (parentTag.equals("el-submenu") || parentTag.equals("el-sub-menu"))) {
                    // Universal rule: Use correct submenu class based on UI framework
                    keyParent = isElementUI2 ? ".el-submenu" : ".el-sub-menu";
                } else if (parentPath != null && (parentPath.contains(".el-submenu") || parentPath.contains(".el-sub-menu"))) {
                    // Universal rule: Use correct submenu class based on UI framework
                    keyParent = isElementUI2 ? ".el-submenu" : ".el-sub-menu";
                }
            }
            else if (parentPath.contains(".el-dialog__footer") && currentSelector.startsWith("button")) {
                keyParent = ".el-dialog__footer";
            } else if (parentPath.contains(".el-dialog__body") && currentSelector.startsWith("button")) {
                keyParent = ".el-dialog__body";
            } else if (parentPath.contains(".el-dialog") && currentSelector.startsWith("button")) {
                keyParent = ".el-dialog__body";
            }

            if (keyParent != null) {
                return keyParent + " " + currentSelector;
            }
        }

        return currentSelector;
    }

    private StaticFacts extractFacts(VueFile file) {
        return ASTAnalyzer.extractFacts(file);
    }

    private String extractRouteFromInlinePush(String handlerExpr) {
        if (handlerExpr == null) return null;
        String expr = handlerExpr;
        expr = expr.replace("\n", " ");

        Pattern p1 = Pattern.compile("\\$router\\.push\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
        Matcher m1 = p1.matcher(expr);
        if (m1.find()) {
            return m1.group(1);
        }

        Pattern p2 = Pattern.compile("\\$router\\.push\\(\\s*['\"]([^'\"]+)['\"]\\s*\\+");
        Matcher m2 = p2.matcher(expr);
        if (m2.find()) {
            String basePath = m2.group(1);
            if (basePath.endsWith("/")) {
                return basePath.substring(0, basePath.length() - 1);
            }
            return basePath;
        }

        Pattern p3 = Pattern.compile("\\$router\\.replace\\(\\s*['\"]([^'\"]+)['\"]");
        Matcher m3 = p3.matcher(expr);
        if (m3.find()) {
            return m3.group(1);
        }

        return null;
    }

    private StructureGraph emptyGraph(String routePath) {
        StructureGraph g = new StructureGraph();
        List<PageNode> nodes = new ArrayList<>();
        nodes.add(new PageNode(routePath));
        g.setNodes(nodes);
        g.setEdges(new ArrayList<>());
        return g;
    }


    private void addStaticEdgesFromFacts(StructureGraph graph, String routePath, StaticFacts facts, String sourceFile, VueFile vueFile) {
        if (graph == null || facts == null) {
            return;
        }

        if (sourceFile.contains("dashboard")) {
            System.out.println("[DEBUG] Processing " + sourceFile + " for route " + routePath);
            System.out.println("[DEBUG]   domNodes: " + (facts.domNodes != null ? facts.domNodes.size() : 0));
            System.out.println("[DEBUG]   routerCalls: " + (facts.routerCalls != null ? facts.routerCalls.size() : 0));
            if (facts.routerCalls != null) {
                for (RouterCall rc : facts.routerCalls) {
                    System.out.println("[DEBUG]     RouterCall: handler=" + rc.handler + ", argument=" + rc.argument);
                }
            }
        }

        List<Edge> edges = graph.getEdges();
        if (edges == null) {
            edges = new ArrayList<>();
            graph.setEdges(edges);
        }

        if (facts.routerCalls != null && facts.domNodes != null) {
            for (RouterCall rc : facts.routerCalls) {
                if (rc == null || rc.argument == null || rc.argument.isBlank()) continue;

                String normalizedTo = normalizeRouteTarget(rc.argument);
                if (!routeTable.exists(normalizedTo)) {
                    continue;
                }

                if (sourceFile != null && (sourceFile.contains("Dialog") || sourceFile.contains("Modal"))) {
                    continue;
                }

                String handler = rc.handler;
                if (handler == null || handler.isBlank()) continue;

                for (DomNode node : facts.domNodes) {
                    if (node == null) continue;

                    boolean matched = false;
                    String eventKey = null;

                    if (node.events != null) {
                        for (Map.Entry<String, String> evt : node.events.entrySet()) {
                            String handlerExpr = evt.getValue();
                            if (handlerExpr != null && handlerExpr.contains(handler)) {
                                matched = true;
                                eventKey = evt.getKey();
                                break;
                            }
                        }
                    }

                    if (!matched && node.attrs != null && node.attrs.containsKey("command")) {
                        String commandValue = node.attrs.get("command");
                        if (handler.equals(commandValue)) {
                            matched = true;
                            eventKey = "click";
                        }
                    }

                    if (!matched) continue;

                    if (routePath.equals(normalizedTo)) {
                        if (node.tag == null ||
                            (!node.tag.equals("el-menu-item") && !node.tag.equals("el-avatar"))) {
                            continue;
                        }
                    }

                    String rawSelector = buildSelectorFromDomNode(node);
                    if (rawSelector == null || rawSelector.isBlank()) continue;

                    String eventType = normalizeEventType(eventKey);

                    Edge e = new Edge();
                    e.setFrom(routePath);
                    e.setSelector(rawSelector);
                    e.setEvent(eventType);
                    e.setTo(normalizedTo);
                    e.setSelectorKind("TEXT");

                    if (node.condition != null && !node.condition.isBlank()) {
                        e.setCondition(node.condition);
                    }

                    e.setSourceFile(sourceFile);
                    e.setExtractionMethod("AST");
                    e.setRawAstSelector(rawSelector);

                    if (!edgeExists(edges, e)) {
                        edges.add(e);
                    }
                }
            }
        }

        if (facts.domNodes != null) {
            for (DomNode node : facts.domNodes) {
                if (node == null || node.attrs == null) continue;

                String target = null;
                String event = "click";

                if (node.attrs.containsKey("to")) {
                    target = node.attrs.get("to");

                    if (target != null && (target.contains("tab.path") || target.contains("item.path") ||
                        target.contains("menu.path") || target.contains(".path"))) {
                        List<MenuItemInfo> menuItems = extractMenuItemsWithContext(vueFile);

                        if (!menuItems.isEmpty()) {
                            for (MenuItemInfo menuItem : menuItems) {
                                String routeTarget = menuItem.path;
                                String menuText = menuItem.text;

                                if (!routeTable.exists(routeTarget)) {
                                    continue;
                                }

                                String finalSelector;
                                String itemClass = menuItem.className != null ? menuItem.className : "el-menu-item";
                                if (menuItem.isInSubmenu) {
                                    String submenuClass = isElementUI2 ? ".el-submenu" : ".el-sub-menu";
                                    finalSelector = submenuClass + " ." + itemClass + ":has-text('" + menuText + "')";
                                } else {
                                    finalSelector = "." + itemClass + ":has-text('" + menuText + "')";
                                }

                                Edge e = new Edge();
                                e.setFrom(routePath);
                                e.setSelector(finalSelector);
                                e.setEvent("click");
                                e.setTo(routeTarget);
                                e.setSelectorKind("TEXT");
                                e.setSourceFile(sourceFile);
                                e.setExtractionMethod("AST");
                                e.setRawAstSelector(finalSelector);

                                if (!edgeExists(edges, e)) {
                                    edges.add(e);
                                }
                            }
                            continue;
                        }
                        target = null;
                    }
                }

                if (target == null && node.attrs.containsKey("index")) {
                    String indexValue = node.attrs.get("index");
                    if (indexValue != null && !indexValue.isBlank()) {
                        if ((indexValue.contains("subItem") || indexValue.contains("item.path") ||
                            indexValue.contains("child") || indexValue.contains("+")) &&
                            node.tag != null && node.tag.equals("el-menu-item")) {
                            String parentRoute = findParentRouteWithChildren(routePath);
                            if (parentRoute != null) {
                                Set<String> children = routeTable.getDirectChildren(parentRoute);
                                if (children != null && !children.isEmpty()) {
                                    Map<String, String> redirects = routeTable.getRedirects();
                                    String redirectTarget = redirects != null ? redirects.get(parentRoute) : null;
                                    List<MenuItemInfo> menuItems = extractMenuItemsWithContext(vueFile);

                                    if (!menuItems.isEmpty()) {
                                        for (MenuItemInfo menuItem : menuItems) {
                                            String childRoute = menuItem.path;
                                            String menuText = menuItem.text;

                                            if (routePath.equals(childRoute) && childRoute.equals(redirectTarget)) {
                                                continue;
                                            }

                                            if (!routeTable.exists(childRoute)) {
                                                continue;
                                            }

                                            String finalSelector;
                                            String itemClass = menuItem.className != null ? menuItem.className : "el-menu-item";
                                            if (menuItem.isInSubmenu) {
                                                String submenuClass = isElementUI2 ? ".el-submenu" : ".el-sub-menu";
                                                finalSelector = submenuClass + " ." + itemClass + ":has-text('" + menuText + "')";
                                            } else {
                                                finalSelector = "." + itemClass + ":has-text('" + menuText + "')";
                                            }

                                            Edge e = new Edge();
                                            e.setFrom(routePath);
                                            e.setSelector(finalSelector);
                                            e.setEvent("click");
                                            e.setTo(childRoute);
                                            e.setSelectorKind("INDEX");
                                            e.setSourceFile(sourceFile);
                                            e.setExtractionMethod("AST");
                                            e.setRawAstSelector(finalSelector);

                                            if (!edgeExists(edges, e)) {
                                                edges.add(e);
                                            }
                                        }
                                    } else {
                                        Map<String, String> routeTexts = extractMenuTextsFromVueFile(vueFile);

                                        for (String childRoute : children) {
                                            if (routePath.equals(childRoute) && childRoute.equals(redirectTarget)) {
                                                continue;
                                            }
                                            String menuText = routeTexts.get(childRoute);
                                            if (menuText == null || menuText.isBlank()) {
                                                continue;
                                            }

                                            String rawSelector = buildSelectorFromDomNode(node);
                                            if (rawSelector == null) continue;

                                            rawSelector = rawSelector + ":has-text('" + menuText + "')";

                                            Edge e = new Edge();
                                            e.setFrom(routePath);
                                            e.setSelector(rawSelector);
                                            e.setEvent("click");
                                            e.setTo(childRoute);
                                            e.setSelectorKind("INDEX");
                                            e.setSourceFile(sourceFile);
                                            e.setExtractionMethod("AST");
                                            e.setRawAstSelector(rawSelector);

                                            if (!edgeExists(edges, e)) {
                                                edges.add(e);
                                            }
                                        }
                                    }
                                    continue;
                                }
                            }
                        }
                        else if (indexValue.startsWith("/")) {
                            target = indexValue;
                        } else {
                            String candidatePath = "/" + indexValue.substring(0, 1).toUpperCase() + indexValue.substring(1);
                            if (routeTable.exists(candidatePath)) {
                                target = candidatePath;
                            } else {
                                candidatePath = "/" + indexValue;
                                if (routeTable.exists(candidatePath)) {
                                    target = candidatePath;
                                }
                            }
                        }
                    }
                }

                if ((target == null || target.isBlank()) && node.events != null) {
                    for (String handlerExpr : node.events.values()) {
                        if (handlerExpr == null) continue;

                        String extracted = extractRouteFromInlinePush(handlerExpr);
                        if (extracted != null && !extracted.isBlank()) {
                            target = extracted;
                            break;
                        }

                        Pattern methodCallPattern = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*['\"]([^'\"]+)['\"]");
                        Matcher methodCallMatcher = methodCallPattern.matcher(handlerExpr.trim());
                        if (methodCallMatcher.find()) {
                            String methodName = methodCallMatcher.group(1);
                            String routeArg = methodCallMatcher.group(2);

                            if (routeArg != null && !routeArg.isBlank()) {
                                target = routeArg;
                                break;
                            }
                        }
                        if ((target == null || target.isBlank()) && facts.routerCalls != null) {
                            Pattern methodPattern = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
                            Matcher methodMatcher = methodPattern.matcher(handlerExpr.trim());
                            if (methodMatcher.find()) {
                                String methodName = methodMatcher.group(1);

                                for (RouterCall rc : facts.routerCalls) {
                                    if (rc != null && rc.handler != null && rc.handler.equals(methodName)) {
                                        if (rc.argument != null && !rc.argument.isBlank()) {
                                            target = rc.argument;
                                            break;
                                        }
                                    }
                                }
                                if (target != null && !target.isBlank()) {
                                    break;
                                }
                            }
                        }
                    }
                }

                if ("el-dropdown-item".equals(node.tag)) {
                    String commandValue = node.attrs.get("command");
                    if (commandValue != null && !commandValue.isBlank() && facts.routerCalls != null) {
                        List<String> commandRoutes = new ArrayList<>();
                        for (RouterCall rc : facts.routerCalls) {
                            if (rc == null || rc.argument == null) continue;
                            if (rc.handler != null && rc.handler.toLowerCase().contains("command")) {
                                String normalizedRoute = normalizeRouteTarget(rc.argument);
                                if (routeTable.exists(normalizedRoute)) {
                                    commandRoutes.add(normalizedRoute);
                                }
                            }
                        }

                        String nodeText = node.text != null ? node.text.trim().toLowerCase() : "";

                        if (!commandRoutes.isEmpty()) {
                            if (nodeText.contains("退出") || nodeText.contains("登出") || nodeText.contains("logout")) {
                                target = commandRoutes.stream()
                                    .filter(r -> r.contains("login"))
                                    .findFirst()
                                    .orElse(commandRoutes.get(0));
                            } else if (nodeText.contains("个人中心") || nodeText.contains("设置") || nodeText.contains("setting")) {
                                target = commandRoutes.stream()
                                    .filter(r -> r.contains("setting"))
                                    .findFirst()
                                    .orElse(commandRoutes.get(0));
                            } else {
                                target = commandRoutes.get(0);
                            }
                        }
                    }
                }

                if (target == null || target.isBlank()) {
                    if (node.tag != null && node.tag.equals("el-menu-item")) {
                        target = routePath;
                    } else if (node.tag != null && (node.tag.equals("el-submenu") || node.tag.equals("el-sub-menu"))) {
                        target = routePath;
                    } else {
                        continue;
                    }
                }

                String normalizedTo = normalizeRouteTarget(target);
                if (!routeTable.exists(normalizedTo)) {
                    continue;
                }

                if (sourceFile != null && sourceFile.contains("layout") &&
                    !routePath.equals(normalizedTo)) {
                    Set<Integer> currentRoles = getRouteRoles(routePath);
                    Set<Integer> targetRoles = getRouteRoles(normalizedTo);

                    if (routePath.equals("/admin/dashboard") && normalizedTo.equals("/studentExam")) {
                        System.out.println("[DEBUG] Role filtering: " + routePath + " -> " + normalizedTo);
                        System.out.println("[DEBUG]   currentRoles: " + currentRoles);
                        System.out.println("[DEBUG]   targetRoles: " + targetRoles);
                    }

                    if (currentRoles != null && !currentRoles.isEmpty() &&
                        targetRoles != null && !targetRoles.isEmpty()) {
                        boolean hasCommonRole = currentRoles.stream()
                            .anyMatch(targetRoles::contains);
                        if (!hasCommonRole) {
                            continue;
                        }
                    }
                }

                if (routePath.equals(normalizedTo)) {
                    if (node.text == null || node.text.isBlank()) {
                        continue;
                    }

                    boolean isNavigationSelfLoop = node.tag != null &&
                        (node.tag.equals("el-menu-item") ||
                         node.tag.equals("el-avatar"));

                    if (!isNavigationSelfLoop) {
                        continue;
                    }
                }

                String rawSelector = buildSelectorFromDomNode(node);
                if (rawSelector == null || rawSelector.isBlank()) continue;
                String eventType = normalizeEventType(event);

                Edge e = new Edge();
                e.setFrom(routePath);
                e.setSelector(rawSelector);
                e.setEvent(eventType);
                e.setTo(normalizedTo);
                e.setSelectorKind("TEXT");

                if (node.condition != null && !node.condition.isBlank()) {
                    e.setCondition(node.condition);
                }

                e.setSourceFile(sourceFile);
                e.setExtractionMethod("AST");
                e.setRawAstSelector(rawSelector);

                if (!edgeExists(edges, e)) {
                    System.out.println("[AST] Extracted edge: " + e);
                    edges.add(e);
                }
            }
        }

        enhanceSelectorFromSource(edges, vueFile, routePath);


        if (vueFile != null && vueFile.getContent() != null && !vueFile.getContent().isBlank()) {
            String content = vueFile.getContent();

            Pattern pattern2 = Pattern.compile(
                "window\\.open\\([^)]*\\$router\\.resolve\\(['\"]([^'\"]+)['\"](?:\\s*\\+[^)]+)?\\)"
            );
            Matcher matcher2 = pattern2.matcher(content);

            while (matcher2.find()) {
                String rawPath = matcher2.group(1);
                if (rawPath == null || rawPath.isBlank()) continue;

                if (rawPath.endsWith("/")) {
                    rawPath = rawPath.substring(0, rawPath.length() - 1);
                }

                String normalizedTo = normalizeRouteTarget(rawPath);

                Edge e = new Edge();
                e.setFrom(routePath);
                e.setSelector("button");
                e.setEvent("click");
                e.setTo(normalizedTo);
                e.setSelectorKind("SCRIPT");

                e.setSourceFile(sourceFile);
                e.setExtractionMethod("AST");
                e.setRawAstSelector("button");

                if (!edgeExists(edges, e)) {
                    edges.add(e);
                }
            }

            Pattern pattern3a = Pattern.compile(
                "window\\.open\\(\\s*['\"]([^'\"]+)['\"]"
            );
            Matcher matcher3a = pattern3a.matcher(content);

            while (matcher3a.find()) {
                String rawPath = matcher3a.group(1);
                if (rawPath == null || rawPath.isBlank()) continue;

                if (rawPath.endsWith("/")) {
                    rawPath = rawPath.substring(0, rawPath.length() - 1);
                }

                String normalizedTo = normalizeRouteTarget(rawPath);

                Edge e = new Edge();
                e.setFrom(routePath);
                e.setSelector("button");
                e.setEvent("click");
                e.setTo(normalizedTo);
                e.setSelectorKind("SCRIPT");
                e.setSourceFile(sourceFile);
                e.setExtractionMethod("AST");
                e.setRawAstSelector("button");

                if (!edgeExists(edges, e)) {
                    edges.add(e);
                }
            }

            Pattern pattern3b = Pattern.compile(
                "window\\.open\\(\\s*`([^`]+)`"
            );
            Matcher matcher3b = pattern3b.matcher(content);

            while (matcher3b.find()) {
                String rawPath = matcher3b.group(1);
                if (rawPath == null || rawPath.isBlank()) continue;

                rawPath = rawPath.replaceAll("\\$\\{[^}]+\\}", "");
                if (rawPath.endsWith("/")) {
                    rawPath = rawPath.substring(0, rawPath.length() - 1);
                }

                String normalizedTo = normalizeRouteTarget(rawPath);

                Edge e = new Edge();
                e.setFrom(routePath);
                e.setSelector("button");
                e.setEvent("click");
                e.setTo(normalizedTo);
                e.setSelectorKind("SCRIPT");
                e.setSourceFile(sourceFile);
                e.setExtractionMethod("AST");
                e.setRawAstSelector("button");

                if (!edgeExists(edges, e)) {
                    edges.add(e);
                }
            }
        }

        if (facts.domNodes != null) {
            for (DomNode node : facts.domNodes) {
                if (node == null || node.events == null || node.events.isEmpty()) continue;

                String rawSelector = buildSelectorFromDomNode(node);
                if (rawSelector == null || rawSelector.isBlank()) continue;
                boolean alreadyProcessed = false;
                for (Edge existing : edges) {
                    if (existing.getFrom().equals(routePath) &&
                        existing.getSelector().equals(rawSelector)) {
                        alreadyProcessed = true;
                        break;
                    }
                }

                if (alreadyProcessed) continue;

                boolean isNavigationSelfLoop = node.tag != null &&
                    (node.tag.equals("el-menu-item") || node.tag.equals("el-avatar"));

                if (!isNavigationSelfLoop) {
                    continue;
                }
                for (Map.Entry<String, String> evt : node.events.entrySet()) {
                    Edge e = new Edge();
                    e.setFrom(routePath);
                    e.setSelector(rawSelector);
                    e.setEvent(evt.getKey() != null ? evt.getKey() : "click");
                    e.setTo(routePath);
                    e.setSelectorKind("TEXT");

                    if (node.condition != null && !node.condition.isBlank()) {
                        e.setCondition(node.condition);
                    }

                    e.setSourceFile(sourceFile);
                    e.setExtractionMethod("AST");
                    e.setRawAstSelector(rawSelector);

                    if (!edgeExists(edges, e)) {
                        edges.add(e);
                    }

                    break;
                }
            }
        }

        if (vueFile != null && vueFile.getContent() != null) {
            String content = vueFile.getContent();
            boolean hasVForMenuItem = content.contains("v-for") &&
                                     content.contains("el-menu-item") &&
                                     (content.contains("route.children") || content.contains("routes"));

            if (hasVForMenuItem) {
                Map<String, String> routeNames = extractRouteNamesFromConfig();
                Map<String, List<String>> routeChildren = routeTable.getChildren();

                if (routeChildren != null && !routeNames.isEmpty()) {
                    for (Map.Entry<String, List<String>> entry : routeChildren.entrySet()) {
                        List<String> children = entry.getValue();

                        if (children == null || children.isEmpty()) continue;

                        for (String childRoute : children) {
                            String menuText = routeNames.get(childRoute);

                            if (menuText != null && !menuText.isBlank()) {
                                String selector = ".el-sub-menu .el-menu-item:has-text('" + menuText + "')";

                                Edge e = new Edge();
                                e.setFrom(routePath);
                                e.setSelector(selector);
                                e.setEvent("click");
                                e.setTo(childRoute);
                                e.setSelectorKind("TEXT");
                                e.setSourceFile(sourceFile);
                                e.setExtractionMethod("AST");
                                e.setRawAstSelector(selector);

                                if (!edgeExists(edges, e)) {
                                    edges.add(e);
                                }
                            }
                        }
                    }
                }
            }

            boolean hasFirstLevelMenuItem = content.contains("v-else-if") &&
                                           content.contains("!route.hidden") &&
                                           content.contains("el-menu-item");

            if (hasFirstLevelMenuItem) {
                Map<String, String> routeNames = extractRouteNamesFromConfig();
                if (routeNames.containsKey("/")) {
                    String menuText = routeNames.get("/");
                    if (menuText != null && !menuText.isBlank()) {
                        String selector = ".el-menu-item:has-text('" + menuText + "')";

                        Edge e = new Edge();
                        e.setFrom(routePath);
                        e.setSelector(selector);
                        e.setEvent("click");
                        e.setTo("/");
                        e.setSelectorKind("TEXT");
                        e.setSourceFile(sourceFile);
                        e.setExtractionMethod("AST");
                        e.setRawAstSelector(selector);

                        if (!edgeExists(edges, e)) {
                            edges.add(e);
                        }
                    }
                }

                if (routeNames.containsKey("/setting")) {
                    String menuText = routeNames.get("/setting");
                    if (menuText != null && !menuText.isBlank()) {
                        String selector = ".el-menu-item:has-text('" + menuText + "')";

                        Edge e = new Edge();
                        e.setFrom(routePath);
                        e.setSelector(selector);
                        e.setEvent("click");
                        e.setTo("/setting");
                        e.setSelectorKind("TEXT");
                        e.setSourceFile(sourceFile);
                        e.setExtractionMethod("AST");
                        e.setRawAstSelector(selector);

                        if (!edgeExists(edges, e)) {
                            edges.add(e);
                        }
                    }
                }
            }
        }

        transformNestedRouteEdges(edges, routePath, sourceFile);
    }


    private void transformNestedRouteEdges(List<Edge> edges, String parentRoutePath, String sourceFile) {
        if (edges == null || parentRoutePath == null) {
            return;
        }

        Set<String> childRoutes = routeTable.getDirectChildren(parentRoutePath);
        if (childRoutes == null || childRoutes.isEmpty()) {
            return;
        }

        if (sourceFile == null ||
            !(sourceFile.toLowerCase().contains("menu") ||
              sourceFile.toLowerCase().contains("nav") ||
              sourceFile.toLowerCase().contains("header") ||
              sourceFile.toLowerCase().contains("aside"))) {
            return;
        }
        int parentEdgeCount = 0;
        int childEdgeCount = 0;

        for (Edge edge : edges) {
            if (edge != null && edge.getFrom() != null) {
                if (parentRoutePath.equals(edge.getFrom())) {
                    parentEdgeCount++;
                } else if (childRoutes.contains(edge.getFrom())) {
                    childEdgeCount++;
                }
            }
        }

        if (parentEdgeCount > 0 && childEdgeCount == 0) {
            transformParentToChildEdges(edges, parentRoutePath, childRoutes);
        }
        else if (childEdgeCount > 0 && parentEdgeCount == 0) {
            transformChildToParentEdges(edges, parentRoutePath, childRoutes, sourceFile);
        }
    }


    private void transformParentToChildEdges(List<Edge> edges, String parentRoutePath, Collection<String> childRoutes) {
        List<Edge> parentEdges = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge != null && parentRoutePath.equals(edge.getFrom())) {
                parentEdges.add(edge);
            }
        }

        for (Edge parentEdge : parentEdges) {
            edges.remove(parentEdge);

            for (String childRoute : childRoutes) {
                Edge childEdge = new Edge();
                childEdge.setFrom(childRoute);
                childEdge.setTo(parentEdge.getTo());
                childEdge.setSelector(parentEdge.getSelector());
                childEdge.setEvent(parentEdge.getEvent());
                childEdge.setSelectorKind(parentEdge.getSelectorKind());
                childEdge.setSourceFile(parentEdge.getSourceFile());
                childEdge.setExtractionMethod(parentEdge.getExtractionMethod());
                childEdge.setRawAstSelector(parentEdge.getRawAstSelector());
                childEdge.setCondition(parentEdge.getCondition());

                if (!edgeExists(edges, childEdge)) {
                    edges.add(childEdge);
                }
            }
        }
    }


    private void transformChildToParentEdges(List<Edge> edges, String parentRoutePath, Collection<String> childRoutes, String sourceFile) {
        Map<String, List<Edge>> edgeGroups = new HashMap<>();

        for (Edge edge : edges) {
            if (edge != null && childRoutes.contains(edge.getFrom())) {
                String key = edge.getTo() + "|" + edge.getSelector() + "|" + edge.getEvent();
                edgeGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
            }
        }

        for (Map.Entry<String, List<Edge>> entry : edgeGroups.entrySet()) {
            List<Edge> group = entry.getValue();

            if (group.size() == childRoutes.size()) {
                edges.removeAll(group);
                Edge parentEdge = new Edge();
                Edge template = group.get(0);
                parentEdge.setFrom(parentRoutePath);
                parentEdge.setTo(template.getTo());
                parentEdge.setSelector(template.getSelector());
                parentEdge.setEvent(template.getEvent());
                parentEdge.setSelectorKind(template.getSelectorKind());
                parentEdge.setSourceFile(sourceFile);
                parentEdge.setExtractionMethod(template.getExtractionMethod());
                parentEdge.setRawAstSelector(template.getRawAstSelector());
                parentEdge.setCondition(template.getCondition());

                if (!edgeExists(edges, parentEdge)) {
                    edges.add(parentEdge);
                }
            }
        }
    }

    private Map<String, String> extractRouteNamesFromConfig() {
        Map<String, String> routeNames = new HashMap<>();

        try {
            if (routerFilePath == null || routerFilePath.isBlank()) {
                System.err.println("[extractRouteNamesFromConfig] Router file path is not configured.");
                return routeNames;
            }

            VueFile routerFile = null;
            for (VueFile file : vueFiles) {
                String filePath = file.getPath();
                if (filePath != null && (routerFilePath.endsWith(filePath) || filePath.endsWith(routerFilePath))) {
                    routerFile = file;
                    break;
                }
            }

            if (routerFile == null || routerFile.getContent() == null) {
                System.err.println("[extractRouteNamesFromConfig] Router file not found in vueFiles: " + routerFilePath);
                return routeNames;
            }

            String content = routerFile.getContent();

            String[] lines = content.split("\n");
            String currentPath = null;
            String currentName = null;
            boolean inRouteObject = false;

            for (String line : lines) {
                if (line.contains("}")) {
                    if (inRouteObject && currentPath != null && currentName != null) {
                        String normalizedPath = currentPath;
                        if (normalizedPath.endsWith("/*")) {
                            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 2);
                        }
                        routeNames.put(normalizedPath, currentName);
                        currentPath = null;
                        currentName = null;
                        inRouteObject = false;
                    }
                }

                Pattern pathPattern = Pattern.compile("path\\s*:\\s*['\"`]([^'\"`]+)['\"`]");
                Matcher pathMatcher = pathPattern.matcher(line);
                if (pathMatcher.find()) {
                    currentPath = pathMatcher.group(1);
                    inRouteObject = true;
                }

                Pattern namePattern = Pattern.compile("name\\s*:\\s*['\"`]([^'\"`]+)['\"`]");
                Matcher nameMatcher = namePattern.matcher(line);
                if (nameMatcher.find() && inRouteObject) {
                    currentName = nameMatcher.group(1);
                }
            }

        } catch (Exception e) {
            System.err.println("[extractRouteNamesFromConfig] Error: " + e.getMessage());
        }

        return routeNames;
    }

    private boolean edgeExists(List<Edge> edges, Edge e) {
        for (Edge old : edges) {
            if (old.getFrom().equals(e.getFrom()) &&
                old.getSelector().equals(e.getSelector()) &&
                old.getEvent().equals(e.getEvent()) &&
                old.getTo().equals(e.getTo())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "click";
        }

        String normalized = eventType.toLowerCase().trim();
        if (normalized.equals("click") ||
            normalized.equals("submit") ||
            normalized.equals("redirect") ||
            normalized.equals("route") ||
            normalized.equals("child")) {
            return normalized;
        }
        return "click";
    }

    private String normalizeRouteTarget(String rawTo) {
        if (rawTo == null || rawTo.isBlank()) {
            return rawTo;
        }

        if (routeTable.exists(rawTo)) {
            return rawTo;
        }

        Set<String> allPaths = routeTable.getAllPaths();
        if (allPaths == null || allPaths.isEmpty()) {
            return rawTo;
        }
        for (String p : allPaths) {
            if (p != null && p.equalsIgnoreCase(rawTo)) {
                return p;
            }
        }

        String suffix = rawTo.startsWith("/") ? rawTo : "/" + rawTo;
        String candidate = null;

        for (String p : allPaths) {
            if (p != null && p.endsWith(suffix)) {
                if (candidate == null) {
                    candidate = p;
                } else {
                    return rawTo;
                }
            }
        }

        if (candidate != null) {
            return candidate;
        }

        String normalized = rawTo.startsWith("/") ? rawTo : "/" + rawTo;
        for (String p : allPaths) {
            if (p == null) continue;

            String[] normalizedParts = normalized.split("/");
            String[] pathParts = p.split("/");

            if (pathParts.length == normalizedParts.length + 1) {
                boolean matches = true;
                for (int i = 0; i < normalizedParts.length; i++) {
                    if (!normalizedParts[i].equals(pathParts[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches && pathParts[pathParts.length - 1].startsWith(":")) {
                    if (candidate == null) {
                        candidate = p;
                    } else {
                        return rawTo;
                    }
                }
            }
        }

        return candidate != null ? candidate : rawTo;
    }


    private void canonicalizeEdges(StructureGraph graph) {
        if (graph == null || graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return;
        }

        List<Edge> edges = graph.getEdges();
        for (Edge e : edges) {
            String selector = e.getSelector();

            if (selector != null && selector.equals(".el-submenu") && e.getSourceFile() != null) {
                String text = extractSubMenuTextFromSource(e.getSourceFile());
                if (text != null) {
                    e.setSelector(".el-submenu:has-text('" + text + "')");
                    e.setRawAstSelector(".el-submenu:has-text('" + text + "')");
                }
            }
        }

        for (Edge e : edges) {
            String selector = e.getSelector();
            if (selector != null && selector.startsWith("router-link:")) {
                String newSelector = selector.replace("router-link:", "a:");
                e.setSelector(newSelector);
                if (e.getRawAstSelector() != null && e.getRawAstSelector().startsWith("router-link:")) {
                    e.setRawAstSelector(e.getRawAstSelector().replace("router-link:", "a:"));
                }
            }
        }

        for (Edge e : edges) {
            if (e.getCondition() != null && !e.getCondition().isBlank()) {
                String condition = e.getCondition();

                condition = condition.replace("this.$store.state.", "store.");
                condition = condition.replace("this.$store.", "store.");
                condition = normalizeOrCondition(condition);

                e.setCondition(condition);
            }
        }

        Map<String, List<Edge>> grouped = new LinkedHashMap<>();
        for (Edge edge : edges) {
            String groupKey = edge.getFrom() + "|" + edge.getTo() + "|" + edge.getEvent();
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(edge);
        }

        List<Edge> deduplicated = new ArrayList<>();

        for (List<Edge> group : grouped.values()) {
            Set<String> seen = new HashSet<>();
            List<Edge> uniqueEdges = new ArrayList<>();

            for (Edge edge : group) {
                String key = edge.getSelector();
                if (!seen.contains(key)) {
                    seen.add(key);
                    uniqueEdges.add(edge);
                }
            }

            if (uniqueEdges.size() > 1) {
                boolean hasSpecificSelector = false;
                for (Edge edge : uniqueEdges) {
                    String selector = edge.getSelector();
                    if (selector != null && !selector.equals("button") &&
                        (selector.contains(".") || selector.contains(":has-text") || selector.contains("#"))) {
                        hasSpecificSelector = true;
                        break;
                    }
                }

                List<Edge> filtered = new ArrayList<>();

                if (hasSpecificSelector) {
                    for (Edge edge : uniqueEdges) {
                        if (!edge.getSelector().equals("button")) {
                            filtered.add(edge);
                        }
                    }
                } else {
                    filtered.addAll(uniqueEdges);
                }

                deduplicated.addAll(filtered);
                boolean hasMenuOrDropdown = false;
                for (Edge edge : deduplicated) {
                    String selector = edge.getSelector();
                    if (selector != null && (selector.contains(".el-menu-item") ||
                                            selector.contains(".el-dropdown-menu__item"))) {
                        hasMenuOrDropdown = true;
                        break;
                    }
                }

                if (hasMenuOrDropdown) {
                    deduplicated.removeIf(edge -> {
                        String selector = edge.getSelector();
                        return selector != null && selector.startsWith("router-link:");
                    });
                }
            } else {
                deduplicated.addAll(uniqueEdges);
            }
        }
        List<Edge> withoutDuplicateRoutes = new ArrayList<>();
        Map<String, List<Edge>> byFromTo = new LinkedHashMap<>();

        for (Edge edge : deduplicated) {
            String key = edge.getFrom() + "|" + edge.getTo();
            byFromTo.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
        }

        for (List<Edge> group : byFromTo.values()) {
            if (group.size() == 1) {
                withoutDuplicateRoutes.add(group.get(0));
            } else {
                Edge routeEdge = null;
                List<Edge> submenuItemEdges = new ArrayList<>();
                List<Edge> menuItemEdges = new ArrayList<>();
                List<Edge> aTagEdges = new ArrayList<>();
                List<Edge> otherEdges = new ArrayList<>();

                List<Edge> dropdownItemEdges = new ArrayList<>();
                List<Edge> genericButtonEdges = new ArrayList<>();

                for (Edge edge : group) {
                    String selector = edge.getSelector();
                    if ("route".equals(edge.getEvent()) && "-".equals(selector)) {
                        routeEdge = edge;
                    } else if (selector != null && selector.contains(".el-dropdown-menu__item")) {
                        dropdownItemEdges.add(edge);
                    } else if (selector != null && selector.contains(".el-submenu") && selector.contains(".el-menu-item")) {
                        submenuItemEdges.add(edge);
                    } else if (selector != null && selector.contains(".el-menu-item")) {
                        menuItemEdges.add(edge);
                    } else if (selector != null && selector.startsWith("a:has-text")) {
                        aTagEdges.add(edge);
                    } else if (selector != null && selector.matches("^button(:has-text\\(.*\\))?$")) {
                        genericButtonEdges.add(edge);
                    } else {
                        otherEdges.add(edge);
                    }
                }

                if (routeEdge != null) {
                    withoutDuplicateRoutes.add(routeEdge);
                }

                if (!dropdownItemEdges.isEmpty()) {
                    withoutDuplicateRoutes.addAll(dropdownItemEdges);
                    withoutDuplicateRoutes.addAll(otherEdges);
                } else if (!aTagEdges.isEmpty() && (!menuItemEdges.isEmpty() || !submenuItemEdges.isEmpty())) {
                    withoutDuplicateRoutes.addAll(aTagEdges);
                    withoutDuplicateRoutes.addAll(otherEdges);
                } else if (!submenuItemEdges.isEmpty()) {
                    withoutDuplicateRoutes.addAll(submenuItemEdges);
                    withoutDuplicateRoutes.addAll(otherEdges);
                } else if (!menuItemEdges.isEmpty()) {
                    withoutDuplicateRoutes.addAll(menuItemEdges);
                    withoutDuplicateRoutes.addAll(otherEdges);
                } else if (!aTagEdges.isEmpty()) {
                    withoutDuplicateRoutes.addAll(aTagEdges);
                    withoutDuplicateRoutes.addAll(otherEdges);
                } else if (!genericButtonEdges.isEmpty()) {
                    withoutDuplicateRoutes.addAll(genericButtonEdges);
                    withoutDuplicateRoutes.addAll(otherEdges);
                } else {
                    for (Edge edge : group) {
                        if (edge != routeEdge) {
                            withoutDuplicateRoutes.add(edge);
                        }
                    }
                }
            }
        }

        List<Edge> withoutAutoRedirect = new ArrayList<>();
        for (Edge edge : withoutDuplicateRoutes) {
            // Filter out auto-redirect edges: from="/*" and selector="-"
            if ("/*".equals(edge.getFrom()) && "-".equals(edge.getSelector())) {
                System.out.println("[Canonicalization] Filtered auto-redirect edge: " + edge.getFrom() + " -> " + edge.getTo());
                continue;
            }

            if ("route".equals(edge.getEvent())) {
                System.out.println("[Canonicalization] Filtered route config edge: " +
                    edge.getFrom() + " -> " + edge.getTo());
                continue;
            }

            withoutAutoRedirect.add(edge);
        }

        List<Edge> withoutRedundantPrefix = new ArrayList<>();
        Map<String, List<Edge>> byFromToText = new LinkedHashMap<>();

        for (Edge edge : withoutAutoRedirect) {
            String selector = edge.getSelector();
            if (selector == null) {
                withoutRedundantPrefix.add(edge);
                continue;
            }

            boolean isElementUISelector = selector.startsWith(".el-") ||
                                         selector.contains(".el-submenu ") ||
                                         selector.contains(".el-sub-menu ");

            if (!isElementUISelector) {
                withoutRedundantPrefix.add(edge);
                continue;
            }
            String text = extractTextFromSelector(selector);

            String baseSelector = selector;
            if (selector.startsWith(".el-submenu ")) {
                baseSelector = selector.substring(".el-submenu ".length());
            } else if (selector.startsWith(".el-sub-menu ")) {
                baseSelector = selector.substring(".el-sub-menu ".length());
            }

            String key = edge.getFrom() + "|" + edge.getTo() + "|" + (text != null ? text : "") + "|" + baseSelector;
            byFromToText.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
        }

        for (List<Edge> group : byFromToText.values()) {
            if (group.size() == 1) {
                withoutRedundantPrefix.add(group.get(0));
            } else {
                String from = group.get(0).getFrom();
                String to = group.get(0).getTo();

                if (from.equals(to)) {
                    Edge bestEdge = null;
                    for (Edge edge : group) {
                        String selector = edge.getSelector();
                        if (selector.contains(".el-submenu ") || selector.contains(".el-sub-menu ")) {
                            bestEdge = edge;
                            break;
                        }
                    }
                    if (bestEdge == null) {
                        bestEdge = group.get(0);
                    }
                    withoutRedundantPrefix.add(bestEdge);
                } else {
                    Edge bestEdge = null;
                    for (Edge edge : group) {
                        String selector = edge.getSelector();
                        if (!selector.contains(".el-submenu ") && !selector.contains(".el-sub-menu ")) {
                            bestEdge = edge;
                            break;
                        }
                    }
                    if (bestEdge == null) {
                        bestEdge = group.get(0);
                    }
                    withoutRedundantPrefix.add(bestEdge);
                }
            }
        }

        List<Edge> withoutCrossGroup = new ArrayList<>();
        int crossGroupCount = 0;

        Map<String, String> pageGroups = analyzeConditionalPageGroups();

        for (Edge edge : withoutRedundantPrefix) {
            if (isCrossGroupEdge(edge, pageGroups)) {
                crossGroupCount++;
                System.out.println("[Canonicalization] Filtered cross-group edge: " +
                    edge.getFrom() + " -> " + edge.getTo() +
                    " (" + pageGroups.get(edge.getFrom()) + " -> " + pageGroups.get(edge.getTo()) + ")");
                continue;
            }

            withoutCrossGroup.add(edge);
        }

        if (crossGroupCount > 0) {
            System.out.println("[Canonicalization] Filtered " + crossGroupCount + " cross-group edges");
        }

        List<Edge> filtered = new ArrayList<>();

        for (Edge edge : withoutCrossGroup) {
            if (!edge.getFrom().equals(edge.getTo())) {
                filtered.add(edge);
                continue;
            }
            if (shouldKeepSelfLoopEdge(edge)) {
                filtered.add(edge);
            }
        }

        graph.setEdges(filtered);
    }


    private Map<String, String> analyzeConditionalPageGroups() {
        Map<String, String> pageGroups = new HashMap<>();

        System.out.println("[Canonicalization] Analyzing conditional page groups from " +
            (vueFiles != null ? vueFiles.size() : 0) + " Vue files...");
        for (VueFile file : vueFiles) {
            if (!hasConditionalMenuRendering(file)) {
                continue;
            }

            System.out.println("[Canonicalization] Found conditional menu in: " + file.getPath());
            String content = file.getContent();

            List<String> baseMenuPaths = new ArrayList<>();
            Pattern baseMenuPattern = Pattern.compile("const\\s+baseMenu\\s*=\\s*\\[");
            Matcher baseMenuMatcher = baseMenuPattern.matcher(content);
            if (baseMenuMatcher.find()) {
                int baseMenuStart = baseMenuMatcher.end() - 1;
                int baseMenuEnd = findMatchingBracket(content, baseMenuStart);
                if (baseMenuEnd != -1) {
                    String baseMenuContent = content.substring(baseMenuStart + 1, baseMenuEnd);
                    Pattern pathPattern = Pattern.compile("path\\s*:\\s*['\"]([^'\"]+)['\"]");
                    Matcher pathMatcher = pathPattern.matcher(baseMenuContent);
                    while (pathMatcher.find()) {
                        String path = pathMatcher.group(1);
                        if (path != null && !path.isBlank()) {
                            String fullPath = path.startsWith("/") ? path : "/" + path;
                            baseMenuPaths.add(fullPath);
                        }
                    }
                    if (!baseMenuPaths.isEmpty()) {
                        System.out.println("[Canonicalization] Found baseMenu with paths: " + baseMenuPaths);
                    }
                }
            }

            int groupIndex = 0;
            int pos = 0;
            while (pos < content.length()) {
                int ifPos = content.indexOf("if", pos);
                if (ifPos == -1) break;
                if (ifPos > 0) {
                    char prevChar = content.charAt(ifPos - 1);
                    if (Character.isLetterOrDigit(prevChar)) {
                        pos = ifPos + 2;
                        continue;
                    }
                }

                int condStart = content.indexOf("(", ifPos);
                if (condStart == -1 || condStart - ifPos > 10) {
                    pos = ifPos + 2;
                    continue;
                }

                int condEnd = findMatchingParen(content, condStart);
                if (condEnd == -1) {
                    pos = ifPos + 2;
                    continue;
                }
                int ifBlockStart = content.indexOf("{", condEnd);
                if (ifBlockStart == -1 || ifBlockStart - condEnd > 20) {
                    pos = ifPos + 2;
                    continue;
                }

                int ifBlockEnd = findMatchingBrace(content, ifBlockStart);
                if (ifBlockEnd == -1) {
                    pos = ifPos + 2;
                    continue;
                }

                String ifBlock = content.substring(ifBlockStart + 1, ifBlockEnd);
                boolean hasMenuContent = ifBlock.contains("this.items") ||
                                        ifBlock.contains("this.menuList") ||
                                        ifBlock.contains("this.menus") ||
                                        ifBlock.contains("this.menuItems") ||
                                        ifBlock.contains("return [") ||
                                        ifBlock.contains("path:") ||
                                        ifBlock.contains("label:");

                if (!hasMenuContent) {
                    pos = ifBlockEnd + 1;
                    continue;
                }

                Pattern pathPattern = Pattern.compile("path\\s*:\\s*['\"]([^'\"]+)['\"]");
                Matcher ifPathMatcher = pathPattern.matcher(ifBlock);
                while (ifPathMatcher.find()) {
                    String path = ifPathMatcher.group(1);
                    if (path != null && !path.isBlank()) {
                        String fullPath = path.startsWith("/") ? path : "/" + path;
                        pageGroups.put(fullPath, "group_" + groupIndex + "_if");
                    }
                }
                if (ifBlock.contains("...baseMenu") && !baseMenuPaths.isEmpty()) {
                    for (String baseMenuPath : baseMenuPaths) {
                        if (!pageGroups.containsKey(baseMenuPath)) {
                            pageGroups.put(baseMenuPath, "group_" + groupIndex + "_if");
                        }
                    }
                }

                int currentPos = ifBlockEnd + 1;
                int branchIndex = 0;
                while (currentPos < content.length()) {
                    String remaining = content.substring(currentPos, Math.min(currentPos + 30, content.length())).trim();
                    if (!remaining.startsWith("else")) {
                        break;
                    }

                    int elsePos = content.indexOf("else", currentPos);
                    if (elsePos == -1) break;
                    String afterElse = content.substring(elsePos + 4, Math.min(elsePos + 20, content.length())).trim();
                    boolean isElseIf = afterElse.startsWith("if");

                    String branchLabel;
                    int blockStart;

                    if (isElseIf) {
                        branchLabel = "group_" + groupIndex + "_elseif" + branchIndex;
                        branchIndex++;
                        int ifPos2 = content.indexOf("if", elsePos);
                        int condStart2 = content.indexOf("(", ifPos2);
                        if (condStart2 == -1) break;

                        int condEnd2 = findMatchingParen(content, condStart2);
                        if (condEnd2 == -1) break;

                        blockStart = content.indexOf("{", condEnd2);
                    } else {
                        branchLabel = "group_" + groupIndex + "_else";
                        blockStart = content.indexOf("{", elsePos);
                    }

                    if (blockStart == -1 || blockStart - elsePos > 50) {
                        break;
                    }

                    int blockEnd = findMatchingBrace(content, blockStart);
                    if (blockEnd == -1) {
                        break;
                    }

                    String branchBlock = content.substring(blockStart + 1, blockEnd);
                    Matcher branchPathMatcher = pathPattern.matcher(branchBlock);
                    while (branchPathMatcher.find()) {
                        String path = branchPathMatcher.group(1);
                        if (path != null && !path.isBlank()) {
                            String fullPath = path.startsWith("/") ? path : "/" + path;
                            pageGroups.put(fullPath, branchLabel);
                        }
                    }

                    if (branchBlock.contains("...baseMenu") && !baseMenuPaths.isEmpty()) {
                        for (String baseMenuPath : baseMenuPaths) {
                            if (!pageGroups.containsKey(baseMenuPath)) {
                                pageGroups.put(baseMenuPath, branchLabel);
                            }
                        }
                    }

                    currentPos = blockEnd + 1;
                    if (!isElseIf) {
                        break;
                    }
                }

                groupIndex++;
                pos = currentPos;
            }
        }

        if (!pageGroups.isEmpty()) {
            System.out.println("[Canonicalization] Detected conditional page groups:");
            Map<String, List<String>> groupedPaths = new HashMap<>();
            for (Map.Entry<String, String> entry : pageGroups.entrySet()) {
                groupedPaths.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
            }
            for (Map.Entry<String, List<String>> entry : groupedPaths.entrySet()) {
                System.out.println("  " + entry.getKey() + ": " + entry.getValue());
            }
        }

        return pageGroups;
    }

    private int findMatchingParen(String content, int start) {
        int depth = 1;
        for (int i = start + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int findMatchingBrace(String content, int start) {
        int depth = 1;
        for (int i = start + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private boolean isCrossGroupEdge(Edge edge, Map<String, String> pageGroups) {
        if (pageGroups.isEmpty()) {
            return false;
        }

        String fromGroup = pageGroups.get(edge.getFrom());
        String toGroup = pageGroups.get(edge.getTo());

        if (fromGroup != null && toGroup != null && !fromGroup.equals(toGroup)) {
            return true;
        }

        return false;
    }

    private String extractTextFromSelector(String selector) {
        if (selector == null) {
            return null;
        }

        // Match has-text('...') pattern
        int start = selector.indexOf(":has-text('");
        if (start == -1) {
            start = selector.indexOf(":has-text(\"");
            if (start == -1) {
                return null;
            }
            start += ":has-text(\"".length();
            int end = selector.indexOf("\")", start);
            if (end == -1) {
                return null;
            }
            return selector.substring(start, end);
        }

        start += ":has-text('".length();
        int end = selector.indexOf("')", start);
        if (end == -1) {
            return null;
        }
        return selector.substring(start, end);
    }

    private boolean shouldKeepSelfLoopEdge(Edge edge) {
        String selector = edge.getSelector();
        if (selector == null) return false;

        if (selector.matches("^\\.el-menu:has-text\\(.*\\)$")) {
            return false;
        }

        if (selector.equals(".el-dropdown")) {
            return false;
        }

        if (selector.contains(".el-dropdown-menu__item")) {
            return false;
        }

        return true;
    }

    private String extractSubMenuTextFromSource(String sourceFilePath) {
        try {
            VueFile targetFile = null;
            for (VueFile file : vueFiles) {
                if (file.getPath().equals(sourceFilePath)) {
                    targetFile = file;
                    break;
                }
            }

            if (targetFile == null) {
                return null;
            }

            String content = targetFile.getContent();

            Pattern subMenuPattern = Pattern.compile(
                "<el-sub-menu[^>]*>(.*?)</el-sub-menu>",
                Pattern.DOTALL
            );
            Matcher subMenuMatcher = subMenuPattern.matcher(content);

            if (subMenuMatcher.find()) {
                String subMenuContent = subMenuMatcher.group(1);

                Pattern titlePattern = Pattern.compile(
                    "<template[^>]*(?:#title|slot=[\"']title[\"'])[^>]*>(.*?)</template>",
                    Pattern.DOTALL
                );
                Matcher titleMatcher = titlePattern.matcher(subMenuContent);

                String textContent = null;
                if (titleMatcher.find()) {
                    textContent = titleMatcher.group(1);
                } else {
                    textContent = subMenuContent;
                }
                Pattern spanPattern = Pattern.compile(
                    "<span[^>]*>([^<]+)</span>"
                );
                Matcher spanMatcher = spanPattern.matcher(textContent);

                if (spanMatcher.find()) {
                    return spanMatcher.group(1).trim();
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }


    private String normalizeOrCondition(String condition) {
        Pattern pattern = Pattern.compile(
            "(\\w+)===(['\"])([^'\"]+)\\2(?:\\|\\|\\1===\\2([^'\"]+)\\2)+"
        );
        Matcher matcher = pattern.matcher(condition);

        if (matcher.find()) {
            String variable = matcher.group(1);
            List<String> values = new ArrayList<>();

            Pattern valuePattern = Pattern.compile(
                variable + "===(['\"])([^'\"]+)\\1"
            );
            Matcher valueMatcher = valuePattern.matcher(condition);

            while (valueMatcher.find()) {
                values.add(valueMatcher.group(2));
            }

            if (values.size() > 1) {
                return variable + " in [" + values.stream()
                    .map(v -> "'" + v + "'")
                    .collect(java.util.stream.Collectors.joining(",")) + "]";
            }
        }

        return condition;
    }

    private Set<String> findAllRoutesUsingComponent(
            String componentPath,
            Map<String, List<String>> componentImports,
            Map<String, List<String>> componentToAllDirectRoutes,
            Map<String, List<String>> routeChildren,
            Map<String, Map<String, String>> componentConditionsMap,
            Set<String> visited) {

        Set<String> routes = new LinkedHashSet<>();

        boolean isDebug = componentPath.contains("NavBar") || componentPath.contains("SideBar");

        if (visited.contains(componentPath)) {
            if (isDebug) {
                System.out.println("[DEBUG] " + componentPath + " already visited, skipping");
            }
            return routes;
        }
        visited.add(componentPath);

        if (isDebug) {
            System.out.println("[DEBUG] Processing " + componentPath);
        }
        if (componentToAllDirectRoutes.containsKey(componentPath)) {
            List<String> parentRoutes = componentToAllDirectRoutes.get(componentPath);
            Map<String, String> redirects = routeTable.getRedirects();
            Map<String, Integer> pathDefinitionCount = routeTable.getPathDefinitionCount();

            for (String parentRoute : parentRoutes) {
                routes.add(parentRoute);

                boolean hasRedirect = redirects != null && redirects.containsKey(parentRoute);

                if (routeChildren != null && routeChildren.containsKey(parentRoute)) {
                    List<String> children = routeChildren.get(parentRoute);
                    if (children != null) {
                        if (hasRedirect) {
                            routes.addAll(children);
                            if (isDebug) {
                                System.out.println("[DEBUG]   Component has redirect: " + parentRoute + ", propagating to all children: " + children);
                            }
                        } else {
                            int defCount = pathDefinitionCount != null && pathDefinitionCount.containsKey(parentRoute)
                                ? pathDefinitionCount.get(parentRoute) : 1;

                            if (defCount == 1) {
                                routes.addAll(children);
                                if (isDebug) {
                                    System.out.println("[DEBUG]   Component path defined once: " + parentRoute + ", propagating to all children: " + children);
                                }
                            } else {
                                if (isDebug) {
                                    System.out.println("[DEBUG]   Component path defined " + defCount + " times: " + parentRoute + ", no propagation to children: " + children);
                                }
                            }
                        }
                    }
                }
            }
        }

        boolean hasParentComponent = false;
        for (Map.Entry<String, List<String>> entry : componentImports.entrySet()) {
            String parentComponent = entry.getKey();
            List<String> imports = entry.getValue();

            if (imports.contains(componentPath)) {
                hasParentComponent = true;
                if (isDebug) {
                    System.out.println("[DEBUG]   Found parent: " + parentComponent);
                }
                Set<String> parentRoutes = findAllRoutesUsingComponent(
                    parentComponent, componentImports, componentToAllDirectRoutes, routeChildren, componentConditionsMap, visited
                );
                if (isDebug) {
                    System.out.println("[DEBUG]   Parent routes: " + parentRoutes);
                }
                routes.addAll(parentRoutes);
            }
        }

        if (isDebug) {
            System.out.println("[DEBUG] " + componentPath + " hasParentComponent=" + hasParentComponent + ", routes.size=" + routes.size());
        }

        if (hasParentComponent && routes.isEmpty()) {
            Set<String> allRoutes = routeTable.getAllPaths();
            Map<String, String> redirects = routeTable.getRedirects();

            String componentName = extractComponentName(componentPath);
            Set<String> excludedRoutes = new HashSet<>();

            for (Map.Entry<String, List<String>> entry : componentImports.entrySet()) {
                String parentComp = entry.getKey();
                if (entry.getValue().contains(componentPath)) {
                    Map<String, String> parentConditions = componentConditionsMap.get(parentComp);
                    if (parentConditions != null) {
                        String condition = parentConditions.get(componentName);
                        if ("v-else".equals(condition)) {
                            VueFile parentFile = findVueFileByPath(parentComp);
                            if (parentFile != null && parentFile.getContent() != null) {
                                String content = parentFile.getContent();
                                Pattern p = Pattern.compile("v-if\\s*=\\s*['\"]([^'\"]*\\$route\\.name\\s*===?\\s*['\"]([^'\"]+)['\"][^'\"]*)['\"]");
                                Matcher m = p.matcher(content);
                                while (m.find()) {
                                    String routeName = m.group(2);
                                    for (String r : allRoutes) {
                                        if (r.toLowerCase().contains("/" + routeName.toLowerCase())) {
                                            excludedRoutes.add(r);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            for (String route : allRoutes) {
                if (redirects != null && redirects.containsKey(route)) {
                    continue;
                }
                if (excludedRoutes.contains(route)) {
                    continue;
                }

                if (componentPath.contains("TabBar") || componentPath.contains("BottomNav") ||
                    componentPath.contains("Navigation") || componentPath.contains("NavBar")) {
                    if (route.contains(":")) {
                        continue;
                    }
                    String routeLower = route.toLowerCase();
                    if (routeLower.contains("/confirm") || routeLower.contains("/detail") ||
                        routeLower.contains("/edit") || routeLower.contains("/add")) {
                        continue;
                    }
                }

                routes.add(route);
            }
        }

        return routes;
    }


    private String extractComponentName(String componentPath) {
        if (componentPath == null || componentPath.isEmpty()) {
            return null;
        }

        int lastSlash = componentPath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? componentPath.substring(lastSlash + 1) : componentPath;
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }


    private VueFile findVueFileByPath(String path) {
        if (vueFiles == null || path == null) {
            return null;
        }
        for (VueFile file : vueFiles) {
            if (path.equals(file.getPath())) {
                return file;
            }
        }
        return null;
    }


    private String resolveComponentPath(String currentFilePath, String importPath) {
        if (importPath == null || importPath.isEmpty()) {
            return null;
        }

        if (importPath.startsWith("@/")) {
            if (currentFilePath.startsWith("src/")) {
                String resolved = "src/" + importPath.substring(2);
                if (!resolved.endsWith(".vue") && !resolved.endsWith(".ts") && !resolved.endsWith(".js")) {
                    resolved += ".vue";
                }
                return resolved;
            }

            int srcIndex = currentFilePath.indexOf("/src/");
            if (srcIndex != -1) {
                String projectRoot = currentFilePath.substring(0, srcIndex);
                String resolved = projectRoot + "/src/" + importPath.substring(2);
                if (!resolved.endsWith(".vue") && !resolved.endsWith(".ts") && !resolved.endsWith(".js")) {
                    resolved += ".vue";
                }
                return resolved;
            }
        }

        if (importPath.startsWith("./") || importPath.startsWith("../")) {
            int lastSlash = currentFilePath.lastIndexOf('/');
            if (lastSlash == -1) {
                return null;
            }
            String currentDir = currentFilePath.substring(0, lastSlash);

            String[] parts = importPath.split("/");
            String resolvedDir = currentDir;

            for (String part : parts) {
                if (part.equals(".")) {
                    continue;
                } else if (part.equals("..")) {
                    int prevSlash = resolvedDir.lastIndexOf('/');
                    if (prevSlash != -1) {
                        resolvedDir = resolvedDir.substring(0, prevSlash);
                    }
                } else {
                    resolvedDir = resolvedDir + "/" + part;
                }
            }
            if (!resolvedDir.endsWith(".vue")) {
                resolvedDir += ".vue";
            }

            return resolvedDir;
        }

        return null;
    }

    private void mergeGraph(StructureGraph global, StructureGraph partial) {
        for (PageNode n : partial.getNodes()) {
            boolean exists = global.getNodes().stream()
                    .anyMatch(x -> x.getName().equals(n.getName()));
            if (!exists) {
                global.getNodes().add(n);
            }
        }

        for (Edge e : partial.getEdges()) {
            if (e.getFrom() == null || e.getFrom().isBlank()) continue;
            if (e.getSelector() == null || e.getSelector().isBlank()) continue;
            if (e.getEvent() == null || e.getEvent().isBlank()) continue;
            if (e.getTo() == null || e.getTo().isBlank()) continue;

            boolean exists = global.getEdges().stream().anyMatch(old ->
                    old.getFrom().equals(e.getFrom())
                            && old.getSelector().equals(e.getSelector())
                            && old.getEvent().equals(e.getEvent())
                            && old.getTo().equals(e.getTo())
            );

            if (!exists) {
                global.getEdges().add(e);
            }
        }
    }


    private void enhanceSelectorFromSource(List<Edge> edges, VueFile vueFile, String routePath) {
        if (vueFile == null || vueFile.getContent() == null || edges == null) return;

        String content = vueFile.getContent();

        List<EdgeInfo> clickElements = new ArrayList<>();

        Pattern clickPattern = Pattern.compile(
            "<(\\w+)([^>]*@click\\s*=\\s*['\"]\\$router\\.push\\(['\"]([^'\"]+)['\"]\\)[^'\"]*['\"][^>]*)>",
            Pattern.DOTALL
        );
        Matcher clickMatcher = clickPattern.matcher(content);

        while (clickMatcher.find()) {
            String tag = clickMatcher.group(1);
            String attrs = clickMatcher.group(2);
            String targetPath = clickMatcher.group(3);

            String normalizedTo = normalizeRouteTarget(targetPath);
            if (!routeTable.exists(normalizedTo) || routePath.equals(normalizedTo)) {
                continue;
            }

            Pattern classPattern = Pattern.compile("class\\s*=\\s*['\"]([^'\"]+)['\"]");
            Matcher classMatcher = classPattern.matcher(attrs);
            String selectorBase = tag;
            if (classMatcher.find()) {
                String cls = classMatcher.group(1);
                if (cls != null && !cls.isBlank()) {
                    selectorBase = "." + cls.split("\\s+")[0];
                }
            }

            int startPos = clickMatcher.end();
            String endTag = "</" + tag + ">";
            int endPos = content.indexOf(endTag, startPos);
            String selector = selectorBase;
            if (endPos != -1) {
                String innerContent = content.substring(startPos, endPos);
                String text = extractVisibleText(innerContent);
                if (text != null && !text.isBlank()) {
                    selector = selectorBase + ":has-text('" + text + "')";
                }
            }

            clickElements.add(new EdgeInfo(normalizedTo, selector));
        }

        Map<String, List<String>> targetToSelectors = new LinkedHashMap<>();
        for (EdgeInfo info : clickElements) {
            targetToSelectors.computeIfAbsent(info.target, k -> new ArrayList<>()).add(info.selector);
        }

        for (Map.Entry<String, List<String>> entry : targetToSelectors.entrySet()) {
            String targetRoute = entry.getKey();
            List<String> selectors = entry.getValue();

            edges.removeIf(e ->
                e.getFrom().equals(routePath) &&
                e.getTo().equals(targetRoute) &&
                (e.getSelector().equals("button") || e.getSelector().startsWith("button:has-text"))
            );

            for (String selector : selectors) {
                boolean exists = edges.stream().anyMatch(e ->
                    e.getFrom().equals(routePath) &&
                    e.getTo().equals(targetRoute) &&
                    e.getSelector().equals(selector)
                );

                if (!exists) {
                    Edge newEdge = new Edge();
                    newEdge.setFrom(routePath);
                    newEdge.setTo(targetRoute);
                    newEdge.setEvent("click");
                    newEdge.setSelector(selector);
                    newEdge.setRawAstSelector(selector);
                    newEdge.setSourceFile(vueFile.getPath());
                    newEdge.setExtractionMethod("AST+LLM");
                    edges.add(newEdge);
                    System.out.println("[DEBUG] Created edge: " + routePath + " -> " + targetRoute + " [" + selector + "]");
                }
            }
        }
    }

    private static class EdgeInfo {
        String target;
        String selector;

        EdgeInfo(String target, String selector) {
            this.target = target;
            this.selector = selector;
        }
    }


    private String extractVisibleText(String htmlContent) {
        if (htmlContent == null || htmlContent.isBlank()) return null;

        Pattern textPattern = Pattern.compile(">([^<]+)<");
        Matcher textMatcher = textPattern.matcher(htmlContent);

        String longestText = null;
        int maxLength = 0;

        while (textMatcher.find()) {
            String t = textMatcher.group(1).trim();
            if (t != null && !t.isBlank()) {
                if (t.matches(".*[\\u4e00-\\u9fa5a-zA-Z0-9]+.*")) {
                    if (t.length() > maxLength) {
                        longestText = t;
                        maxLength = t.length();
                    }
                }
            }
        }

        return longestText;
    }

    private StructureGraph deepCopyGraph(StructureGraph source) {
        String json = JSON.toJSONString(source);
        return JSON.parseObject(json, StructureGraph.class);
    }
}
