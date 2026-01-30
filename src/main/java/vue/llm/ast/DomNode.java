package vue.llm.ast;

import java.util.Map;

public class DomNode {
    public String tag;
    public Map<String, String> attrs;
    public Map<String, String> events;
    public String text;
    public String parentPath;  // Parent selector path, e.g., ".el-sub-menu"
    public String parentTag;   // Parent node's original tag, e.g., "el-submenu" or "el-sub-menu"
    public String condition;   // v-if/v-show condition, e.g., "!store.login"

    @Override
    public String toString() {
        return "DomNode{" +
                "tag='" + tag + '\'' +
                ", attrs=" + attrs +
                ", events=" + events +
                ", text='" + text + '\'' +
                ", parentPath='" + parentPath + '\'' +
                ", condition='" + condition + '\'' +
                '}';
    }
}
