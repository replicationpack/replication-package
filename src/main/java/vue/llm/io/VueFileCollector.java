package vue.llm.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


public class VueFileCollector {
    public static List<VueFile> collect(String projectPath) throws IOException {
        List<VueFile> files = new ArrayList<>();
        Path root = Path.of(projectPath);

        Files.walk(root).forEach(path -> {
            String name = path.toString();

            if (name.endsWith(".vue") || name.endsWith(".js") || name.endsWith(".ts")) {
                try {
                    files.add(new VueFile(
                            root.relativize(path).toString(),
                            Files.readString(path)
                    ));
                } catch (IOException ignored) {}
            }
        });
        return files;
    }
}
