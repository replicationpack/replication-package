package vue.llm.util;

public class SelfCorrector {
    public static String extractJson(String raw) {
        int start = raw.indexOf("{");
        int end = raw.lastIndexOf("}");
        if (start < 0 || end < 0) return "{}";
        return raw.substring(start, end + 1);
    }
}
