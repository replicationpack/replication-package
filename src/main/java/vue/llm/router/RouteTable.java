package vue.llm.router;

import java.util.*;

public class RouteTable {

    private final Map<String, String> routes = new HashMap<>();

    private final Map<String, String> redirects = new HashMap<>();

    private final Set<String> allPaths = new HashSet<>();

    private final Map<String, List<String>> children = new HashMap<>();

    private final Map<String, Integer> pathDefinitionCount = new HashMap<>();

    private String normalize(String path) {
        if (path == null) return "";

        String p = path.replace("\\", "/");

        if (p.startsWith("@/")) {
            p = "src/" + p.substring(2);
        }

        if (p.startsWith("../")) {
            p = p.substring(3);
            if (!p.startsWith("src/")) {
                p = "src/" + p;
            }
        }

        int idx = p.indexOf("src/");
        if (idx >= 0) {
            p = p.substring(idx);
        }

        if (p.startsWith("./")) {
            p = "src/" + p.substring(2);
        }

        if (p.endsWith(".vue")) {
            p = p.substring(0, p.length() - 4);
        }

        return p;
    }


    public void addRoute(String path, String componentPath) {
        String normalized = normalize(componentPath);
        routes.put(path, normalized);
        allPaths.add(path);
    }

    public void addRedirect(String from, String to) {
        String target = to;
        if (target != null && !target.isBlank() && !target.startsWith("/")) {
            target = "/" + target;
        }
        redirects.put(from, target);
    }

    public Map<String, String> getRedirects() {
        return redirects;
    }

    public void addAllPaths(List<String> paths) {
        allPaths.addAll(paths);
    }

    public Set<String> getAllPaths() {
        return new LinkedHashSet<>(allPaths);
    }

    public String getPathByName(String name) {
        if (name == null || name.isBlank()) return null;

        String direct = "/" + name;
        if (allPaths.contains(direct)) {
            return direct;
        }

        for (String p : allPaths) {
            if (p != null && p.endsWith("/" + name)) {
                return p;
            }
        }
        return null;
    }
    public boolean exists(String path) {
        return allPaths.contains(path);
    }

    public void addChild(String parent, String child) {
        children.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
    }

    public void setPathDefinitionCount(String path, Integer count) {
        pathDefinitionCount.put(path, count);
    }


    public Map<String, List<String>> getChildren() {
        return children;
    }

    public Map<String, Integer> getPathDefinitionCount() {
        return pathDefinitionCount;
    }

    public Set<String> getDirectChildren(String parentPath) {
        if (parentPath == null) {
            return new HashSet<>();
        }
        List<String> childList = children.get(parentPath);
        if (childList == null) {
            return new HashSet<>();
        }
        return new HashSet<>(childList);
    }

    public String getRoutePathByComponent(String componentFilePath) {
        String normalized = normalize(componentFilePath);

        return routes.entrySet()
                .stream()
                .filter(e -> normalize(e.getValue()).equals(normalized))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("UNKNOWN");
    }


    public java.util.List<String> getAllRoutePathsByComponent(String componentFilePath) {
        String normalized = normalize(componentFilePath);

        return routes.entrySet()
                .stream()
                .filter(e -> normalize(e.getValue()).equals(normalized))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RouteTable {\n");

        sb.append("  Routes:\n");
        for (var entry : routes.entrySet()) {
            sb.append("    ").append(entry.getKey())
                    .append("  →  ").append(entry.getValue())
                    .append("\n");
        }

        sb.append("  Redirects:\n");
        for (var entry : redirects.entrySet()) {
            sb.append("    ").append(entry.getKey())
                    .append("  →  ").append(entry.getValue())
                    .append("\n");
        }

        sb.append("  All Valid Paths:\n");
        for (String p : allPaths) {
            sb.append("    ").append(p).append("\n");
        }

        sb.append("}");
        return sb.toString();
    }
}
