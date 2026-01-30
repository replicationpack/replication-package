package vue.llm.ast;

import java.io.*;

public class NodeBridge {
    public static String parseAST(String vueContent) {

        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "node", "src/main/node/ast/parseVueAST.js"
            );
            Process process = builder.start();

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream()))) {
                writer.write(vueContent);
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder output = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null)
                output.append(line);

            return output.toString();

        } catch (IOException e) {
            throw new RuntimeException("NodeBridge parseAST error", e);
        }
    }
}
