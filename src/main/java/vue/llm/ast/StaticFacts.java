package vue.llm.ast;

import java.util.List;
import java.util.Map;

public class StaticFacts {

	public List<String> domSelectors;
	public List<String> eventHandlers;
	public List<RouterCall> routerCalls;
	public List<String> routeComponents;
	public List<DomNode> domNodes;
	public boolean usesCompositionAPI;
	public List<ImportedComponent> importedComponents;
	public Map<String, String> componentConditions;  // Component name -> rendering condition

	@Override
	public String toString() {
		return "StaticFacts{" +
				"domSelectors=" + domSelectors +
				", eventHandlers=" + eventHandlers +
				", routerCalls=" + routerCalls +
				", components=" + routeComponents +
				", domNodes=" + domNodes +
				", usesCompositionAPI=" + usesCompositionAPI +
				", importedComponents=" + importedComponents +
				'}';
	}

	public static class ImportedComponent {
		public String name;
		public String path;

		@Override
		public String toString() {
			return "ImportedComponent{name='" + name + "', path='" + path + "'}";
		}
	}
}
