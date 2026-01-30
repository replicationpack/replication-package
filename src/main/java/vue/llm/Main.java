package vue.llm;

import com.alibaba.fastjson2.JSON;
import vue.llm.core.StructureExtraction;
import vue.llm.config.ProjectConfig;
import vue.llm.evaluation.GraphEvaluator;
import vue.llm.graph.Edge;
import vue.llm.graph.StructureGraph;
import vue.llm.io.VueFile;
import vue.llm.io.VueFileCollector;
import vue.llm.router.RouteTable;
import vue.llm.router.RouterParser;
import vue.llm.util.LlmClient;
import vue.llm.util.RouteGraph;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Main {
    public static void main(String[] args) throws Exception {
        String activeProject = "library";
        buildPTG(activeProject);
    }


    private static void displayProjectStatistics(String projectName) {
        ProjectConfig config = ProjectConfig.PROJECTS.get(projectName);
        if (config == null) {
            return;
        }

        int sfcCount = 0;
        int totalLOC = 0;

        try {
            Path root = Path.of(config.projectRoot);

            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String pathStr = path.toString();

                        if (pathStr.contains("node_modules") ||
                                pathStr.contains(".git") ||
                                pathStr.contains("dist") ||
                                pathStr.contains("build")) {
                            return;
                        }

                        boolean isSourceFile = pathStr.endsWith(".vue") ||
                                pathStr.endsWith(".js") ||
                                pathStr.endsWith(".ts") ||
                                pathStr.endsWith(".jsx") ||
                                pathStr.endsWith(".tsx");

                        if (!isSourceFile) {
                            return;
                        }
                    });

            sfcCount = (int) Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String pathStr = path.toString();
                        return !pathStr.contains("node_modules") &&
                                !pathStr.contains(".git") &&
                                !pathStr.contains("dist") &&
                                !pathStr.contains("build") &&
                                pathStr.endsWith(".vue");
                    })
                    .count();

            totalLOC = (int) Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String pathStr = path.toString();
                        if (pathStr.contains("node_modules") ||
                                pathStr.contains(".git") ||
                                pathStr.contains("dist") ||
                                pathStr.contains("build")) {
                            return false;
                        }
                        return pathStr.endsWith(".vue") ||
                                pathStr.endsWith(".js") ||
                                pathStr.endsWith(".ts") ||
                                pathStr.endsWith(".jsx") ||
                                pathStr.endsWith(".tsx");
                    })
                    .mapToInt(path -> {
                        try {
                            return (int) Files.lines(path).count();
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .sum();

        } catch (Exception e) {
            System.err.println("[displayProjectStatistics] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void buildPTG(String activeProject) throws Exception {
        ProjectConfig config = ProjectConfig.PROJECTS.get(activeProject);
        if (config == null) {
            throw new IllegalArgumentException("Unknown project: " + activeProject);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path outDir = Path.of("out", config.projectName, timestamp);
        Files.createDirectories(outDir);
        Path router = Path.of(config.routerRelativePath);
        String routerPath = router.toString();
        RouteTable routeTable = RouterParser.parse(routerPath);
        List<VueFile> vueFiles = VueFileCollector.collect(config.projectRoot);
        displayProjectStatistics(activeProject);
        LlmClient llm = new LlmClient();
        StructureExtraction structureExtraction = new StructureExtraction(llm, routeTable, config.routerRelativePath);
        StructureGraph stage3Graph = structureExtraction.run(activeProject, vueFiles);
        Path expertGraphPath = Path.of("ptg", activeProject + ".json");
        StructureGraph expertGraph = null;
        if (Files.exists(expertGraphPath)) {
            String expertJson = Files.readString(expertGraphPath, StandardCharsets.UTF_8);
            expertGraph = JSON.parseObject(expertJson, StructureGraph.class);
        }
        String stage3Json = JSON.toJSONString(stage3Graph);
        Files.writeString(outDir.resolve("stage3_graph.json"), stage3Json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("\n[Output] Stage 3 graph saved to stage3_graph.json");
        if (expertGraph != null) {
            evaluation(structureExtraction, expertGraph, stage3Graph, outDir);
        }

        Path htmlOut = outDir.resolve("show.html");
        String dot = RouteGraph.toDot(stage3Graph);
        Path dotOut = outDir.resolve("routes.dot");
        Files.writeString(dotOut, dot);
        Files.writeString(htmlOut, RouteGraph.SHOW_HTML, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void detectUndefinedNodes(StructureGraph expertGraph, RouteTable routeTable) {
        if (expertGraph == null || routeTable == null) return;
        Set<String> undefinedNodes = new HashSet<>();
        java.util.Set<String> definedRoutes = new HashSet<>(routeTable.getAllPaths());

        for (vue.llm.graph.PageNode node : expertGraph.getNodes()) {
            String nodeName = node.getName();
            if (!definedRoutes.contains(nodeName)) {
                undefinedNodes.add(nodeName);
            }
        }

        for (Edge edge : expertGraph.getEdges()) {
            if (edge.getFrom() != null && !definedRoutes.contains(edge.getFrom())) {
                undefinedNodes.add(edge.getFrom());
            }
            if (edge.getTo() != null && !definedRoutes.contains(edge.getTo())) {
                undefinedNodes.add(edge.getTo());
            }
        }

        if (undefinedNodes.isEmpty()) {
            System.out.println("\nAll nodes are defined in router configuration.");
        } else {
            for (String node : undefinedNodes) {
                List<String> referencingEdges = new ArrayList<>();
                for (Edge edge : expertGraph.getEdges()) {
                    if (node.equals(edge.getFrom()) || node.equals(edge.getTo())) {
                        referencingEdges.add(edge.getFrom() + " -> " + edge.getTo());
                    }
                }
            }
        }
    }


    public static GraphEvaluator.EvaluationResult evaluation(StructureExtraction structureExtraction, StructureGraph expertGraph,
                                                             StructureGraph stage3Graph, Path outDir) throws Exception {
        if (expertGraph == null) {
            System.out.println("[Evaluation] Expert graph not found, skipping evaluation.");
            return null;
        }

        StructureGraph stage1Graph = structureExtraction.getStage1Snapshot();
        StructureGraph stage2Graph = structureExtraction.getStage2Snapshot();
        detectUndefinedNodes(expertGraph, structureExtraction.getRouteTable());
        if (stage1Graph != null) {
            GraphEvaluator.EvaluationResult stage1Result = GraphEvaluator.evaluateStage1(stage1Graph, expertGraph);
            String stage1EvalJson = JSON.toJSONString(stage1Result);
            Files.writeString(outDir.resolve("stage1_evaluation.json"), stage1EvalJson,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            String stage1Json = JSON.toJSONString(stage1Graph);
            Files.writeString(outDir.resolve("stage1_graph.json"), stage1Json,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        if (stage2Graph != null) {
            GraphEvaluator.EvaluationResult stage2Result = GraphEvaluator.evaluate(stage2Graph, expertGraph);
            String stage2EvalJson = JSON.toJSONString(stage2Result);
            Files.writeString(outDir.resolve("stage2_evaluation.json"), stage2EvalJson,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            String stage2Json = JSON.toJSONString(stage2Graph);
            Files.writeString(outDir.resolve("stage2_graph.json"), stage2Json,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        GraphEvaluator.EvaluationResult stage3Result = GraphEvaluator.evaluate(stage3Graph, expertGraph);
        String stage3EvalJson = JSON.toJSONString(stage3Result);
        Files.writeString(outDir.resolve("stage3_evaluation.json"), stage3EvalJson,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return stage3Result;
    }
}
