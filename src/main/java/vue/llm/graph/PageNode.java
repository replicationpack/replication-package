package vue.llm.graph;

public class PageNode {
    private String name;

    public PageNode() {}
    public PageNode(String name) { this.name = name; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    @Override
    public boolean equals(Object o) {
        return (o instanceof PageNode) &&
                ((PageNode)o).name.equals(this.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
