package vue.llm.evaluation;

import vue.llm.graph.Edge;
import vue.llm.graph.StructureGraph;

import java.util.*;


public class GraphEvaluator {

    public static class EvaluationResult {
        public int truePositives;
        public int falsePositives;
        public int falseNegatives;
        public double precision;
        public double recall;
        public double fnr;
        public double f1Score;
        public int graphEditDistance;

        public List<Edge> missingEdges;
        public List<Edge> incorrectEdges;

        public int extractedNodes;
        public int expertNodes;
        public int matchedNodes;
        public int missingNodes;
        public int extraNodes;
        public double nodeRecall;

        @Override
        public String toString() {
            return String.format(
                "=== Evaluation Results ===\n" +
                "--- Nodes ---\n" +
                "Extracted:       %d\n" +
                "Expert:          %d\n" +
                "Matched:         %d\n" +
                "Missing:         %d\n" +
                "Extra:           %d\n" +
                "Node Recall:     %.2f%%\n" +
                "\n--- Edges ---\n" +
                "True Positives:  %d\n" +
                "False Positives: %d\n" +
                "False Negatives: %d\n" +
                "Precision:       %.2f%%\n" +
                "Recall:          %.2f%%\n" +
                "FNR:             %.2f%%\n" +
                "F1-Score:        %.2f%%\n" +
                "Graph Edit Dist: %d\n",
                extractedNodes, expertNodes, matchedNodes, missingNodes, extraNodes,
                nodeRecall * 100,
                truePositives, falsePositives, falseNegatives,
                precision * 100, recall * 100, fnr * 100, f1Score * 100,
                graphEditDistance
            );
        }
    }


    public static EvaluationResult evaluate(StructureGraph extracted, StructureGraph expert) {
        return evaluate(extracted, expert, false);
    }


    public static EvaluationResult evaluateStage1(StructureGraph extracted, StructureGraph expert) {
        return evaluate(extracted, expert, true);
    }


    private static EvaluationResult evaluate(StructureGraph extracted, StructureGraph expert, boolean ignoreSelector) {
        EvaluationResult result = new EvaluationResult();

        Set<String> extractedNodeSet = new HashSet<>();
        if (extracted.getNodes() != null) {
            for (var node : extracted.getNodes()) {
                if (node != null && node.getName() != null) {
                    extractedNodeSet.add(node.getName());
                }
            }
        }

        Set<String> expertNodeSet = new HashSet<>();
        if (expert.getNodes() != null) {
            for (var node : expert.getNodes()) {
                if (node != null && node.getName() != null) {
                    expertNodeSet.add(node.getName());
                }
            }
        }

        Set<String> matchedNodes = new HashSet<>(extractedNodeSet);
        matchedNodes.retainAll(expertNodeSet);

        Set<String> missingNodeSet = new HashSet<>(expertNodeSet);
        missingNodeSet.removeAll(extractedNodeSet);

        Set<String> extraNodeSet = new HashSet<>(extractedNodeSet);
        extraNodeSet.removeAll(expertNodeSet);

        result.extractedNodes = extractedNodeSet.size();
        result.expertNodes = expertNodeSet.size();
        result.matchedNodes = matchedNodes.size();
        result.missingNodes = missingNodeSet.size();
        result.extraNodes = extraNodeSet.size();
        result.nodeRecall = result.expertNodes > 0 ?
            (double) result.matchedNodes / result.expertNodes : 0.0;

        Set<String> extractedEdgeSet = ignoreSelector ?
            edgesToStringSetSimple(extracted.getEdges()) :
            edgesToStringSet(extracted.getEdges());
        Set<String> expertEdgeSet = ignoreSelector ?
            edgesToStringSetSimple(expert.getEdges()) :
            edgesToStringSet(expert.getEdges());

        Set<String> tp = new HashSet<>(extractedEdgeSet);
        tp.retainAll(expertEdgeSet);

        Set<String> fp = new HashSet<>(extractedEdgeSet);
        fp.removeAll(expertEdgeSet);

        Set<String> fn = new HashSet<>(expertEdgeSet);
        fn.removeAll(extractedEdgeSet);

        result.truePositives = tp.size();
        result.falsePositives = fp.size();
        result.falseNegatives = fn.size();

        if (result.truePositives + result.falsePositives > 0) {
            result.precision = (double) result.truePositives / (result.truePositives + result.falsePositives);
        } else {
            result.precision = 0.0;
        }

        if (result.truePositives + result.falseNegatives > 0) {
            result.recall = (double) result.truePositives / (result.truePositives + result.falseNegatives);
        } else {
            result.recall = 0.0;
        }

        result.fnr = 1.0 - result.recall;

        if (result.precision + result.recall > 0) {
            result.f1Score = 2 * (result.precision * result.recall) / (result.precision + result.recall);
        } else {
            result.f1Score = 0.0;
        }

        result.graphEditDistance = result.falsePositives + result.falseNegatives;
        result.missingEdges = findEdgesByStringSet(expert.getEdges(), fn);
        result.incorrectEdges = findEdgesByStringSet(extracted.getEdges(), fp);

        return result;
    }


    private static Set<String> edgesToStringSet(List<Edge> edges) {
        Set<String> set = new HashSet<>();
        if (edges == null) return set;

        for (Edge e : edges) {
            if (e == null) continue;
            String key = edgeToString(e);
            set.add(key);
        }
        return set;
    }


    private static Set<String> edgesToStringSetSimple(List<Edge> edges) {
        Set<String> set = new HashSet<>();
        if (edges == null) return set;

        for (Edge e : edges) {
            if (e == null) continue;
            String key = edgeToStringSimple(e);
            set.add(key);
        }
        return set;
    }


    private static String edgeToString(Edge e) {
        String selector = normalizeSelector(e.getSelector());
        return String.format("%s|%s|%s|%s",
            e.getFrom(), selector, e.getEvent(), e.getTo());
    }


    private static String normalizeSelector(String selector) {
        if (selector == null) return "";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(":has-text\\(['\"]([^'\"]+)['\"]\\)");
        java.util.regex.Matcher matcher = pattern.matcher(selector);

        if (matcher.find()) {
            String text = matcher.group(1);
            String normalizedText = text.trim()
                .replaceAll("[><!\\-»«]+", "")
                .replaceAll("\\s+", " ")
                .trim();
            return matcher.replaceAll(":has-text('" + normalizedText + "')");
        }

        return selector;
    }


    private static String edgeToStringSimple(Edge e) {
        return String.format("%s|%s", e.getFrom(), e.getTo());
    }


    private static List<Edge> findEdgesByStringSet(List<Edge> edges, Set<String> stringSet) {
        List<Edge> result = new ArrayList<>();
        if (edges == null) return result;

        for (Edge e : edges) {
            if (e == null) continue;
            String key = edgeToString(e);
            if (stringSet.contains(key)) {
                result.add(e);
            }
        }
        return result;
    }
}
