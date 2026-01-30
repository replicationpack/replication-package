package vue.llm.router;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class RouterParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class RouterDTO {
        public Map<String, String> routes;
        public List<RedirectDTO> redirects;
        public Map<String, List<String>> children;
        public Map<String, Integer> pathDefinitionCount;
        public static class RedirectDTO {
            public String from;
            public String to;
        }
    }

    public static RouteTable parse(String routerFilePath) throws Exception {

        String routerContent = new String(Files.readAllBytes(
                Paths.get(routerFilePath)
        ));

        ProcessBuilder pb = new ProcessBuilder(
                "node",
                "src/main/node/ast/parseRouterAST.js"
        );
        pb.redirectErrorStream(false);

        Process process = pb.start();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream()))) {
            writer.write(routerContent);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread tOut = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (Exception ignored) {}
        });

        Thread tErr = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append("\n");
            } catch (Exception ignored) {}
        });

        tOut.start();
        tErr.start();
        tOut.join();
        tErr.join();

        int exit = process.waitFor();

        if (exit != 0) {
            throw new RuntimeException("[RouterParser] Node exited with code " + exit);
        }

        String json = stdout.toString().trim();
        RouterDTO dto = mapper.readValue(json, RouterDTO.class);

        RouteTable table = new RouteTable();

        if (dto.routes != null) {
            dto.routes.forEach(table::addRoute);
        } else {
            System.out.println("[RouterParser] WARNING: dto.routes is null");
        }

        if (dto.redirects != null) {
            for (RouterDTO.RedirectDTO r : dto.redirects) {
                table.addRedirect(r.from, r.to);
            }
        }
        if (dto.children != null) {
            for (var entry : dto.children.entrySet()) {
                String parent = entry.getKey();
                for (String child : entry.getValue()) {
                    table.addChild(parent, child);
                }
            }
        }

        if (dto.pathDefinitionCount != null) {
            for (var entry : dto.pathDefinitionCount.entrySet()) {
                table.setPathDefinitionCount(entry.getKey(), entry.getValue());
            }
        }

        if (dto.routes != null) {
            table.addAllPaths(dto.routes.keySet().stream().toList());
        }

        return table;
    }
}
