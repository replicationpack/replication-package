package vue.llm.evaluation;

import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;


public class StatisticsCalculator {

    public static class ProjectStats {
        public String projectName;
        public List<Double> precisions = new ArrayList<>();
        public List<Double> recalls = new ArrayList<>();
        public List<Double> f1Scores = new ArrayList<>();
        public List<Integer> geds = new ArrayList<>();

        public double getPrecisionMean() {
            return calculateMean(precisions);
        }

        public double getPrecisionStd() {
            return calculateStd(precisions);
        }

        public double getRecallMean() {
            return calculateMean(recalls);
        }

        public double getRecallStd() {
            return calculateStd(recalls);
        }

        public double getF1Mean() {
            return calculateMean(f1Scores);
        }

        public double getF1Std() {
            return calculateStd(f1Scores);
        }

        public double getGedMean() {
            return calculateMean(geds.stream().map(Integer::doubleValue).collect(Collectors.toList()));
        }

        public double getGedStd() {
            return calculateStd(geds.stream().map(Integer::doubleValue).collect(Collectors.toList()));
        }

        private double calculateMean(List<Double> values) {
            if (values.isEmpty()) return 0.0;
            return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        private double calculateStd(List<Double> values) {
            if (values.size() <= 1) return 0.0;
            double mean = calculateMean(values);
            double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
            return Math.sqrt(variance);
        }
    }

    public static ProjectStats collectProjectStats(String projectName) throws IOException {
        ProjectStats stats = new ProjectStats();
        stats.projectName = projectName;

        Path projectOutDir = Path.of("out", projectName);
        if (!Files.exists(projectOutDir)) {
            System.out.println("Warning: No output directory found for " + projectName);
            return stats;
        }

        List<Path> timestampDirs = Files.list(projectOutDir)
            .filter(Files::isDirectory)
            .filter(p -> p.getFileName().toString().matches("\\d{8}_\\d{6}"))
            .sorted()
            .collect(Collectors.toList());

        System.out.println("Found " + timestampDirs.size() + " runs for " + projectName);

        for (Path runDir : timestampDirs) {
            Path evalFile = runDir.resolve("stage3_evaluation.json");
            if (Files.exists(evalFile)) {
                try {
                    String json = Files.readString(evalFile, StandardCharsets.UTF_8);
                    GraphEvaluator.EvaluationResult result = JSON.parseObject(json, GraphEvaluator.EvaluationResult.class);

                    stats.precisions.add(result.precision);
                    stats.recalls.add(result.recall);
                    stats.f1Scores.add(result.f1Score);
                    stats.geds.add(result.graphEditDistance);
                } catch (Exception e) {
                    System.out.println("Warning: Failed to read " + evalFile + ": " + e.getMessage());
                }
            }
        }

        return stats;
    }

    public static void generateStatisticsTable() throws IOException {
        List<String> projectNames = Arrays.asList(
            "library", "questionnaire", "dormitory", "musicmanage", "blogmanage",
            "covid", "vehicle", "takeout", "exam", "houserental"
        );

        Map<String, ProjectStats> allStats = new LinkedHashMap<>();

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COLLECTING STATISTICS FROM ALL RUNS");
        System.out.println("=".repeat(80) + "\n");

        for (String projectName : projectNames) {
            ProjectStats stats = collectProjectStats(projectName);
            allStats.put(projectName, stats);
        }

        printStatisticsTable(allStats);
    }

    private static void printStatisticsTable(Map<String, ProjectStats> allStats) {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("STATISTICS TABLE (mean±std over multiple runs)");
        System.out.println("=".repeat(100));
        System.out.println(String.format("%-4s %20s %20s %20s %15s",
            "ID", "Precision (%)", "Recall (%)", "F1 (%)", "GED"));
        System.out.println("-".repeat(100));

        double avgPrecision = 0.0, avgRecall = 0.0, avgF1 = 0.0, avgGed = 0.0;
        int validProjects = 0;
        int projectId = 1;

        for (Map.Entry<String, ProjectStats> entry : allStats.entrySet()) {
            ProjectStats stats = entry.getValue();

            if (stats.precisions.size() > 0) {
                double pMean = stats.getPrecisionMean() * 100;
                double pStd = stats.getPrecisionStd() * 100;
                double rMean = stats.getRecallMean() * 100;
                double rStd = stats.getRecallStd() * 100;
                double fMean = stats.getF1Mean() * 100;
                double fStd = stats.getF1Std() * 100;
                double gMean = stats.getGedMean();
                double gStd = stats.getGedStd();

                System.out.println(String.format("P%-3d %20s %20s %20s %15s",
                    projectId++,
                    formatMeanStd(pMean, pStd),
                    formatMeanStd(rMean, rStd),
                    formatMeanStd(fMean, fStd),
                    formatMeanStd(gMean, gStd)));

                avgPrecision += pMean;
                avgRecall += rMean;
                avgF1 += fMean;
                avgGed += gMean;
                validProjects++;
            } else {
                System.out.println(String.format("P%-3d %20s %20s %20s %15s",
                    projectId++, "N/A", "N/A", "N/A", "N/A"));
            }
        }

        System.out.println("-".repeat(100));
        if (validProjects > 0) {
            System.out.println(String.format("%-4s %20.2f %20.2f %20.2f %15.1f",
                "Avg.",
                avgPrecision / validProjects,
                avgRecall / validProjects,
                avgF1 / validProjects,
                avgGed / validProjects));
        } else {
            System.out.println(String.format("%-4s %20s %20s %20s %15s",
                "Avg.", "N/A", "N/A", "N/A", "N/A"));
        }

        System.out.println("=".repeat(100) + "\n");
    }

    private static String formatMeanStd(double mean, double std) {
        return String.format("%.2f±%.2f", mean, std);
    }
}
