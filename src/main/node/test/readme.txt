node ptg-test-with-coverage.js.bakeup <project> <mode> [timeBudget]

# PTG 测试，300 秒
node ptg-test-with-coverage.js.bakeup library ptg 300

# Random 测试，300 秒
node ptg-test-with-coverage.js.bakeup library random 300


library: {
    baseUrl: 'http://localhost:8080',
    cookies: [
        {
            name: 'sessionToken',
            value: 'your-token-here',
            domain: 'localhost',
            path: '/'
        }
    ]
}

ptg_results.json 或 random_results.json - 测试结果
ptg_coverage.json 或 random_coverage.json - NYC 覆盖率数据



方案 A：Vite + Vue（推荐你现在这条：vite-plugin-istanbul）

A1. 安装依赖

在 Vue 项目根目录执行：

npm i -D nyc vite-plugin-istanbul cross-env

A2. 配置 Vite 插桩（生成 window.coverage）

编辑 vite.config.ts（或 vite.config.js），加入：

import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import istanbul from "vite-plugin-istanbul";

export default defineConfig({
  plugins: [
    vue(),
    istanbul({
      include: "src/**/*",
      exclude: ["node_modules", "test/**", "**/*.spec.*", "**/*.test.*"],
      extension: [".js", ".ts", ".vue", ".jsx", ".tsx"],
      requireEnv: true, // 关键：只在指定环境变量时插桩
    }),
  ],
});

requireEnv: true 的意思是：你必须在启动时设置环境变量（下面 A4）才会插桩。

A3. 配置 nyc（负责“读 .nyc_output 并生成报告”）

在项目根目录新建 .nycrc.json：

{
  "report-dir": "coverage",
  "reporter": ["text-summary", "html", "json-summary"],
  "temp-dir": ".nyc_output",
  "all": false
}

你现在覆盖率数据是从浏览器抓到后写到 .nyc_output，所以 nyc 这里只要能 report 就行，不需要它去 instrument 源码。

A4. package.json 增加脚本（覆盖率模式启动）

在 package.json 增加：

{
  "scripts": {
    "dev": "vite",
    "dev:cov": "cross-env VITE_COVERAGE=true vite",
    "cov:report": "nyc report --reporter=text-summary --reporter=html --reporter=json-summary"
  }
}

A5. 启动验证：window.coverage

启动覆盖率模式：

npm run dev:cov

浏览器打开页面后，在 DevTools Console 输入：

window.__coverage__

	•	如果打印出一个对象 ✅ 插桩成功
	•	如果是 undefined ❌ 说明没插桩（常见原因：没用 dev:cov 启动、requireEnv 没吃到变量、vite.config 没生效）

A6. 跑你的 Playwright 脚本（落盘到本地）

你之前我给你改过“把 coverage 同时写到 .nyc_output/”的版本的话：
跑一次：

node ptg-test-with-coverage.js.bakeup library ptg 300

跑完你应该能看到：
	•	项目根目录 .nyc_output/coverage-xxx.json ✅（nyc 会用它出报告）

A7. 生成 nyc 报告（Statements=SC）

npm run cov:report

输出：
	•	终端有 Statements : xx% (a/b)（这就是 SC）
	•	coverage/index.html
	•	coverage/coverage-summary.json
