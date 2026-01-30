package vue.llm.graph;

import java.util.ArrayList;
import java.util.List;

public class StructureGraph {

    private List<PageNode> nodes = new ArrayList<>();
    private List<Edge> edges = new ArrayList<>();

    // getters
    public List<PageNode> getNodes() { return nodes; }
    public List<Edge> getEdges() { return edges; }

    // setters
    public void setNodes(List<PageNode> nodes) {
        this.nodes = (nodes != null) ? nodes : new ArrayList<>();
    }

    public void setEdges(List<Edge> edges) {
        this.edges = (edges != null) ? edges : new ArrayList<>();
    }

    // utils
    public void addNode(PageNode node) {
        if (node != null && !nodes.contains(node)) {
            nodes.add(node);
        }
    }

    public void addEdge(Edge edge) {
        if (edge != null && !edges.contains(edge)) {
            edges.add(edge);
        }
    }

    @Override
    public String toString() {
        return "StructureGraph{" +
                "nodes=" + nodes +
                ", edges=" + edges +
                '}';
    }
}
