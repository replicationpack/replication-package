package vue.llm.ast;

public class RouterCall {
    public String type;
    public String argument;
    public String handler;

    @Override
    public String toString() {
        return "RouterCall{" +
                "type='" + type + '\'' +
                ", argument='" + argument + '\'' +
                ", handler='" + handler + '\'' +
                '}';
    }
}
