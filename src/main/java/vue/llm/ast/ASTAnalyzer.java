package vue.llm.ast;

import vue.llm.io.VueFile;
import vue.llm.util.JsonUtil;

public class ASTAnalyzer {
    public static StaticFacts extractFacts(VueFile file) {

        String json = NodeBridge.parseAST(file.getContent());

        try {
            return JsonUtil.fromJson(json, StaticFacts.class);
        } catch (Exception e) {
            return new StaticFacts();
        }
    }
}
