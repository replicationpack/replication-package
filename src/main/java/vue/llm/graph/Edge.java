package vue.llm.graph;

import java.util.Objects;

public class Edge {
    private String from;
    private String selector;
    private String event;
    private String to;
    private String selectorKind;
    private String condition;

    private String sourceFile;
    private String extractionMethod;
    private Double confidence;
    private String llmReasoning;
    private String rawAstSelector;
    private Boolean isValid;
    private Boolean isDefinedButUnused;

    public Edge() {}

    public Edge(String from, String selector, String event, String to) {
        this.from = from;
        this.selector = selector;
        this.event = event;
        this.to = to;
    }

    public String getFrom() { return from; }
    public String getSelector() { return selector; }
    public String getEvent() { return event; }
    public String getTo() { return to; }
    public String getSelectorKind() { return selectorKind; }
    public String getCondition() { return condition; }
    public String getSourceFile() { return sourceFile; }
    public String getExtractionMethod() { return extractionMethod; }
    public Double getConfidence() { return confidence; }
    public String getLlmReasoning() { return llmReasoning; }
    public String getRawAstSelector() { return rawAstSelector; }
    public Boolean getIsValid() { return isValid; }
    public Boolean getIsDefinedButUnused() { return isDefinedButUnused; }

    public void setFrom(String from) { this.from = from; }
    public void setSelector(String selector) { this.selector = selector; }
    public void setEvent(String event) { this.event = event; }
    public void setTo(String to) { this.to = to; }
    public void setSelectorKind(String selectorKind) { this.selectorKind = selectorKind; }
    public void setCondition(String condition) { this.condition = condition; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public void setExtractionMethod(String extractionMethod) { this.extractionMethod = extractionMethod; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public void setLlmReasoning(String llmReasoning) { this.llmReasoning = llmReasoning; }
    public void setRawAstSelector(String rawAstSelector) { this.rawAstSelector = rawAstSelector; }
    public void setIsValid(Boolean isValid) { this.isValid = isValid; }
    public void setIsDefinedButUnused(Boolean isDefinedButUnused) { this.isDefinedButUnused = isDefinedButUnused; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Edge)) return false;
        Edge e = (Edge) o;

        return Objects.equals(from, e.from) &&
                Objects.equals(selector, e.selector) &&
                Objects.equals(event, e.event) &&
                Objects.equals(to, e.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, selector, event, to);
    }

    @Override
    public String toString() {
        return "Edge{" +
                "from='" + from + '\'' +
                ", selector='" + selector + '\'' +
                ", event='" + event + '\'' +
                ", to='" + to + '\'' +
                ", selectorKind='" + selectorKind + '\'' +
                ", condition='" + condition + '\'' +
                '}';
    }
}
