This repository contains the artifact/code for the paper:

**LLM-Assisted Model-Based GUI Testing for Vue.js Web Applications**

It provides:
- PTG (Page Transition Graph) construction for Vue.js web applications.
- LLMVue-guided and Random GUI testing (Playwright) with optional NYC/Istanbul coverage collection.


## Repository Structure

```text
llm-vue/
  ├── pom.xml
  ├── src/main/java/vue/llm/                 # PTG construction pipeline (Java)
  │   ├── Main.java                          # PTG construction entry
  │   ├── config/ProjectConfig.java          # SUT project path + router path configuration
  │   ├── router/                            # Router parsing (invokes Node AST parser)
  │   └── util/LlmClient.java                # LLM endpoint/model/key configuration
  ├── src/main/node/                         # Node utilities
  │   ├── ast/parseRouterAST.js              # Parse Vue router file -> route table (JSON)
  │   └── test/                              # GUI testing with PTG + coverage
  │       ├── ptg-test-with-coverage.js
  │       └── package.json
  ├── out/                                   # Generated outputs (created at runtime)
  └── sut/                                   # Subject Vue.js applications (SUTs)
      └── url.txt                            # Source repos + commit hashes for SUTs
```

---

## Requirements

- Java: **JDK 17** (see `pom.xml`)
- Maven: 3.x
- Node.js: v18.20.8
- npm
- Playwright dependencies (installed via npm)

---

## 1) Configure the LLM Client

File:
- `src/main/java/vue/llm/util/LlmClient.java`

You must set:
- `apiKey` (currently placeholder `sk-xx`)

Optional:
- `endpoint` (`https://api.openai.com/v1/chat/completions`)
- `model` (`gpt-4o`)


---

## 2) Configure SUT Project Paths (Vue Apps)

File:
- `src/main/java/vue/llm/config/ProjectConfig.java`

This file maps each project name (e.g., `library`, `dormitory`) to:
- `projectRoot`: where the Vue project is located
- `routerRelativePath`: the router file path (used for PTG construction)

### Typical setup

If you place all SUT projects under `llm-vue/sut/`, you can set:
- `ProjectConfig.root = "sut/"`

Then ensure each entry points to the correct router file inside the corresponding Vue project.

---

## 3) Build PTG (Page Transition Graph)

Entry class:
- `src/main/java/vue/llm/Main.java`

By default:
- `activeProject = "library"`

Change it to any key in `ProjectConfig.PROJECTS`.

### Run

- **Recommended:** run `vue.llm.Main` from your IDE (run configuration at repository root).


### Outputs

PTG outputs are written to:

- `out/<projectName>/<timestamp>/stage3_graph.json` (PTG)


### Optional: expert PTG for evaluation

`Main.java` will try to load an expert graph:

- `ptg/<project>.json`

If this file does not exist, evaluation is skipped.

---

## 4) Enable Coverage Collection (NYC/Istanbul) in the SUT

The testing script collects coverage from:
- `window.__coverage__`

So the SUT must be started with Istanbul instrumentation enabled.

A practical option for Vite + Vue is `vite-plugin-istanbul` (see also `src/main/node/test/readme.txt`).

High-level steps (in the SUT Vue project root):

1) Install

```bash
npm i -D vite-plugin-istanbul cross-env nyc
```

2) Add Vite instrumentation (example)

```js
// vite.config.js / vite.config.ts
import istanbul from 'vite-plugin-istanbul'

istanbul({
  include: 'src/**/*',
  exclude: ['node_modules', 'test/**', '**/*.spec.*', '**/*.test.*'],
  extension: ['.js', '.ts', '.vue', '.jsx', '.tsx'],
  requireEnv: true,
})
```

3) Start SUT in coverage mode

```bash
npm run dev:cov
```

4) Verify in browser console

- `window.__coverage__` should be a non-empty object.

---

## 5) Run GUI Testing (PTG-guided / Random) with Coverage

Test script:
- `src/main/node/test/ptg-test-with-coverage.js`

Install test dependencies:

```bash
npm install
```

Run from:
- `llm-vue/src/main/node/test/`



- `project`: key in `PROJECT_CONFIGS` inside `ptg-test-with-coverage.js` (currently includes `library`, `dormitory`)
- `mode`: `ptg` or `random`
- `totalDuration`: total execution time (seconds)
- `saveInterval` (optional): snapshot interval for time-series saving

Examples:

```bash
node ptg-test-with-coverage.js library ptg 300
node ptg-test-with-coverage.js library random 300
node ptg-test-with-coverage.js library ptg 600 60
```

### Project config (baseUrl/auth/nyc)

Inside `ptg-test-with-coverage.js`, edit `PROJECT_CONFIGS` for each SUT:
- `baseUrl`: where the SUT is running
- `startPage`: initial route
- `auth`: optional login/session injection
- `nyc.cwd`: SUT project root for NYC report generation
- `nyc.reportRoot`: where coverage reports will be generated

---

## Outputs (Testing)

The test script reads the latest PTG from:
- `out/<project>/.../stage3_graph.json` (the newest timestamp folder)

It writes run outputs to:

- `out/<project>/runs/<runId>/`
  - `<mode>_results_snapshot_XXX.json`
  - `<mode>_coverage_snapshot_XXX.json` (raw `window.__coverage__`)

It also writes NYC raw coverage to:

- `.nyc_output/<runId>/coverage-<project>-<mode>-<runId>-snapshot_XXX.json`

If `nyc` is available and `PROJECT_CONFIGS[project].nyc` is configured, HTML reports are generated to:

- `src/main/node/test/coverage/<project>/<runId>_<snapshotId>/`
