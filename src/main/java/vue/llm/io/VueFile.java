package vue.llm.io;


public class VueFile {
    private final String path;
    private final String content;

    public VueFile(String path, String content) {
        this.path = path;
        this.content = content;
    }

    public String getPath() { return path; }
    public String getContent() { return content; }

    @Override
    public String toString() {
        return "VueFile{" +
                "path='" + path + '\'' +
                '}';
    }
}
