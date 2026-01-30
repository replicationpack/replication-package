package vue.llm.util;

import vue.llm.graph.StructureGraph;
import vue.llm.graph.PageNode;
import vue.llm.graph.Edge;

public class RouteGraph {

    public static String toDot(StructureGraph graph) {
        StringBuilder sb = new StringBuilder();

        sb.append("digraph Routes {\n");
        sb.append("  graph [rankdir=LR, ranksep=2, nodesep=0.8];\n");
        sb.append("  node [shape=rect, style=\"rounded,filled\", fontname=\"Helvetica\"];\n\n");

        for (PageNode node : graph.getNodes()) {

            String route = node.getName();
            String id = "route:" + route;
            String label = id;

            String fillColor = "#F6F8FA";
            String borderColor = "#1E90FF";

            sb.append("  \"").append(escape(id)).append("\" ")
                    .append("[label=\"").append(escape(label))
                    .append("\", fillcolor=\"").append(fillColor)
                    .append("\", color=\"").append(borderColor)
                    .append("\"];\n");
        }

        sb.append("\n");

        // === Edges ===
        for (Edge edge : graph.getEdges()) {

            String from = "route:" + edge.getFrom();
            String to   = "route:" + edge.getTo();

            // label：route\nclick\n.selector
            StringBuilder label = new StringBuilder("route");

            if (edge.getEvent() != null && !edge.getEvent().isEmpty())
                label.append("\\n").append(edge.getEvent());

            if (edge.getSelector() != null && !edge.getSelector().isEmpty())
                label.append("\\n").append(edge.getSelector());


            sb.append("  \"").append(escape(from)).append("\" -> \"")
                    .append(escape(to)).append("\" ")
                    .append("[color=\"#1E90FF\", style=solid, label=\"")
                    .append(escape(label.toString()))
                    .append("\"];\n");
        }

        sb.append("}\n");
        return sb.toString();
    }


    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }


    public static final String SHOW_HTML = """
        <!DOCTYPE html>
        <html lang="zh">
        <head>
            <meta charset="UTF-8">
            <title>Vue Route Graph</title>
            <script src="https://d3js.org/d3.v7.min.js"></script>
            <script src="https://unpkg.com/d3-graphviz@5.0.0/build/d3-graphviz.js"></script>
            <style>
                body {
                    background: #f9fafb;
                    display: flex;
                    justify-content: center;
                    align-items: flex-start;
                    min-height: 100vh;
                    margin: 0;
                    padding-top: 40px;
                }
                #graph {
                    width: 80%;
                    max-width: 1600px;
                    border: 1px solid #ddd;
                    background: #fff;
                    box-shadow: 0 2px 6px rgba(0,0,0,0.1);
                    border-radius: 8px;
                    overflow: auto;
                }
                svg {
                    display: block;
                    margin: auto;
                }
                #loading {
                    position: fixed;
                    top: 10px;
                    left: 50%;
                    transform: translateX(-50%);
                    font: 14px/1.6 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
                    color: #374151;
                    background: #fff;
                    border: 1px solid #e5e7eb;
                    border-radius: 8px;
                    padding: 6px 10px;
                    box-shadow: 0 2px 6px rgba(0,0,0,0.05);
                }
            </style>
            <script type="module">
                import {Graphviz} from "https://cdn.jsdelivr.net/npm/@hpcc-js/wasm/dist/graphviz.js";
                window["@hpcc-js/wasm"] = {Graphviz};

                async function loadDotFile(path) {
                    const resp = await fetch(path);
                    if (!resp.ok) throw new Error(`Load failed: ${path}`);
                    return await resp.text();
                }

                (async () => {
                    const loading = document.getElementById("loading");
                    try {
                        const dot = await loadDotFile("routes.dot");
                        await d3.select("body")
                            .append("div")
                            .attr("id", "graph")
                            .graphviz()
                            .width(1200)
                            .height(800)
                            .renderDot(dot);
                        loading.textContent = "Render complete ✅";
                        setTimeout(() => loading.remove(), 800);
                    } catch (err) {
                        loading.textContent = "Render failed ❌ " + err.message;
                        console.error(err);
                    }
                })();
            </script>
        </head>
        <body style="background:#f9fafb;">
            <div id="loading">Loading and rendering graph...</div>
        </body>
        </html>
        """;
}
