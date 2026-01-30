const {chromium} = require('playwright');
const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '../../../..');


const WAIT_TIMES = {
    AFTER_CLICK: 100,           // Wait after click (600ms for web, paper uses 2000ms for mobile)
    AFTER_NAVIGATION: 300,      // Wait after navigation
    BEFORE_NEXT_ACTION: 200,    // Short wait before next action
    MENU_EXPAND: 300,           // Wait after menu expansion
    SUBMENU_EXPAND: 120,        // Wait after submenu expansion
    AFTER_GOTO: 300,            // Wait after goto
};

/**
 * Project configurations
 */
const PROJECT_CONFIGS = {
    library: {
        routeMode: 'history',
        baseUrl: 'http://localhost:9876',
        startPage: '/dashboard',
        auth: {
            authType: 1,
            sessionStorage: {
                // 1: sessionStorage
                items: [
                    {
                        key: 'user',
                        value: {"id":17,"username":"admin","nickName":null,"password":"123456","sex":null,"address":null,"phone":null,"token":"eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIxNyIsImV4cCI6MTc2ODY0ODEwN30.qvLvoquEvNoGusnwqR6bB5ezEJaVe4QBcq6k1jmk30o","role":1}
                    },
                ],
            },
        },
        nyc: {
            cwd: path.join(REPO_ROOT, 'sut', 'Vue-Springboot-Library', 'library'),
            reportRoot: path.join(__dirname, 'coverage', 'library'),
        },
    },
    dormitory: {
        routeMode: 'history',
        baseUrl: 'http://localhost:9999/',
        startPage: '/home',
        auth: {
            authType: 2,
            localStorage: {
                items: [
                    {
                        key: 'systemAdmin',
                        value: {"id":1,"username":"admin","password":"123456","name":"管理员1","telephone":"88132001"}
                    },
                ],
            }
        },
        nyc: {
            cwd: path.join(REPO_ROOT, 'sut', 'dormitory_springboot', 'dormitoryms'),
            reportRoot: path.join(__dirname, 'coverage', 'dormitory'),
        }
    }
};

function ensureDir(dir) {
    fs.mkdirSync(dir, {recursive: true});
}

function safeWriteJson(filePath, data) {
    if (!filePath) throw new Error('safeWriteJson: filePath is undefined');
    ensureDir(path.dirname(filePath));
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf8');
}

function makeRunId(projectName, mode) {
    // e.g. 20260108-153012
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const ts =
        `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-` +
        `${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;

    const tag = process.env.RUN_TAG ? String(process.env.RUN_TAG).replace(/[^\w.-]/g, '_') : '';
    return [ts, projectName, mode, tag].filter(Boolean).join('_');
}

function projectOutRoot() {
    return path.join(__dirname, '../../../../out');
}

function nycOutputRoot(runId) {
    return path.join(process.cwd(), '.nyc_output', runId);
}

function runNycReport({tempDir, cwdDir, reportDir}) {
    return new Promise((resolve, reject) => {
        try {
            ensureDir(reportDir);

            console.log('[NYC] Generating report...');
            console.log('[NYC] tempDir:', tempDir);
            console.log('[NYC] cwdDir:', cwdDir);
            console.log('[NYC] reportDir:', reportDir);

            // Try to use NYC programmatically
            try {
                const NYC = require('nyc');
                const nyc = new NYC({
                    tempDir: tempDir,
                    cwd: cwdDir,
                    reportDir: reportDir,
                    excludeAfterRemap: false,
                    reporter: ['text-summary', 'html', 'json-summary']
                });

                // report() is async, need to await it
                nyc.report().then(() => {
                    const summaryPath = path.join(reportDir, 'coverage-summary.json');
                    if (!fs.existsSync(summaryPath)) {
                        return reject(new Error(`nyc summary not found: ${summaryPath}`));
                    }

                    const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf-8'));
                    resolve(summary);
                }).catch((err) => {
                    console.error('[NYC] Report generation failed:', err.message);
                    console.log('[NYC] Skipping coverage report generation');
                    resolve({total: {lines: {pct: 0}, statements: {pct: 0}, functions: {pct: 0}, branches: {pct: 0}}});
                });
            } catch (nycError) {
                console.error('[NYC] Failed to use NYC programmatically:', nycError.message);
                console.log('[NYC] Skipping coverage report generation');
                // Return empty summary instead of failing
                resolve({total: {lines: {pct: 0}, statements: {pct: 0}, functions: {pct: 0}, branches: {pct: 0}}});
            }
        } catch (error) {
            reject(error);
        }
    });
}

function toJsonSafe(obj) {
    return JSON.parse(JSON.stringify(obj));
}

function remainingMs(endTime) {
    return Math.max(0, endTime - Date.now());
}

async function safeSleep(page, ms, endTime) {
    const t = Math.min(ms, remainingMs(endTime));
    if (t <= 0) return;
    try {
        await page.waitForTimeout(t);
    } catch (_) {
    }
}

async function isLogoutOrLoginRelated(element) {
    try {
        return await element.evaluate((node) => {
            const text = (node.textContent || '').toLowerCase().trim();
            const href = (node.getAttribute('href') || '').toLowerCase();
            const onclick = (node.getAttribute('onclick') || '').toLowerCase();
            const className = (node.className || '').toLowerCase();
            const id = (node.id || '').toLowerCase();

            const logoutKeywords = ['logout', 'log out', 'sign out', 'signout', '退出', '登出', 'exit'];
            const loginKeywords = ['login', 'log in', 'sign in', 'signin', '登录', '登入'];

            for (const keyword of [...logoutKeywords, ...loginKeywords]) {
                if (text.includes(keyword)) return true;
            }

            if (href.includes('/login') || href.includes('/logout') || href.includes('login') || href.includes('logout')) {
                return true;
            }

            for (const keyword of [...logoutKeywords, ...loginKeywords]) {
                if (onclick.includes(keyword)) return true;
            }

            if (className.includes('logout') || className.includes('login') ||
                id.includes('logout') || id.includes('login')) {
                return true;
            }

            return false;
        });
    } catch (error) {
        return false;
    }
}

async function closeOpenModals(page) {
    try {
        const closeSelectors = [
            '.el-dialog__close',
            '.el-message-box__close',
            '.el-drawer__close-btn',
            '[aria-label="Close"]',
            'button.close',
            '.modal-close',
            '[class*="close"]:visible',
            'button:has-text("取消")',
            'button:has-text("Cancel")',
            'button:has-text("关闭")',
            'button:has-text("Close")',
        ];

        let closed = false;
        for (const selector of closeSelectors) {
            try {
                const closeBtn = page.locator(selector).first();
                const count = await closeBtn.count().catch(() => 0);
                if (count > 0) {
                    const isVisible = await closeBtn.isVisible({timeout: 500}).catch(() => false);
                    if (isVisible) {
                        await closeBtn.click({timeout: 1000, noWaitAfter: true}).catch(() => {});
                        console.log(`[Modal] Closed modal using selector: ${selector}`);
                        closed = true;
                        await page.waitForTimeout(300); // Wait for modal to close
                        break;
                    }
                }
            } catch (e) {
            }
        }

        if (!closed) {
            await page.keyboard.press('Escape').catch(() => {});
            console.log('[Modal] Pressed Escape key to close modal');
        }

        return true;
    } catch (error) {
        console.log(`[Modal] Error closing modal: ${error.message}`);
        return false;
    }
}

function normalizeRouteKey(raw, config = null) {
    let s = String(raw || '').trim();

    if (/^https?:\/\//i.test(s)) {
        const u = new URL(s);
        s = (u.hash && u.hash.startsWith('#')) ? u.hash.slice(1) : u.pathname;
    }

    if (s.startsWith('#')) s = s.slice(1);

    if (s.startsWith('/#/')) s = s.slice(2);

    if (!s.startsWith('/')) s = '/' + s;

    s = s.split('?')[0].split('#')[0];

    if (s.length > 1 && s.endsWith('/')) s = s.slice(0, -1);

    if (config && config.dynamicRoutePatterns) {
        for (const pattern of config.dynamicRoutePatterns) {
            const regexPattern = pattern.replace(/:[^/]+/g, '[^/]+');
            const regex = new RegExp('^' + regexPattern + '$');

            if (regex.test(s)) {
                return pattern;
            }
        }
    }

    return s;
}

function buildUrlFromRouteKey(baseUrl, routeKey, routeMode, config = null) {
    const origin = new URL(baseUrl).origin;
    const key = normalizeRouteKey(routeKey, config);
    if (routeMode === 'hash') return origin + '/#' + key;
    return origin + key;
}

function getCurrentRouteKey(page, config) {
    const u = new URL(page.url());
    if (config.routeMode === 'hash') {
        const h = u.hash || '';            // "#/welcome"
        const p = h.startsWith('#') ? h.slice(1) : h;
        return normalizeRouteKey(p, config);
    }
    return normalizeRouteKey(u.pathname, config);
}


class PTGTest {
    constructor(page, ptg, config) {
        this.page = page;
        this.ptg = ptg;
        this.config = config;

        this.visitedPages = new Set();
        this.allVisitedPages = new Set();
        this.actionCount = 0;
        this.startTime = Date.now();
        this.coverageData = null;

        this.totalPages = this.ptg?.nodes?.length || 0;
        this.totalEdges = this.ptg?.edges?.length || 0;

        this._openedDropdownOnce = false;
        this._expandedSubMenus = new Set();
        this._expandedAllOnce = false;
    }

    async test(timeBudgetSeconds = 300) {
        console.log(`[PTG Test] Starting PTG-guided testing with ${timeBudgetSeconds}s time budget...`);

        const startPage = this.findStartPage();
        const endTime = Date.now() + timeBudgetSeconds * 1000;

        try {
            await this.gotoPath(startPage, endTime).catch(() => {});
            await safeSleep(this.page, WAIT_TIMES.AFTER_NAVIGATION, endTime);

            let allPagesVisited = false;

            while (Date.now() < endTime) {
                console.log(`[PTG Test] ===== New round (remaining ${remainingMs(endTime)}ms) =====`);
                this.visitedPages.clear();

                await this.ensureMenusExpanded(endTime);

                await this.visitPageWithTimeLimit(startPage, endTime);

                if (!allPagesVisited && this.allVisitedPages.size >= this.totalPages) {
                    allPagesVisited = true;
                    console.log(`[PTG Test] All ${this.totalPages} pages visited! Switching to exploration-only mode.`);
                }

                await safeSleep(this.page, WAIT_TIMES.BEFORE_NEXT_ACTION, endTime);
            }
        } finally {
            this.coverageData = await this.collectNYCCoverage();
        }

        const duration = (Date.now() - this.startTime) / 1000;
        const metrics = await this.calculateMetrics();

        console.log(`[PTG Test] Completed in ${duration.toFixed(3)}s`);
        console.log(`[PTG Test] Action Number (AN): ${this.actionCount}`);
        console.log(`[PTG Test] Page Coverage (PC): ${(metrics.pageCoverage * 100).toFixed(2)}%`);

        return {
            duration,
            actionNumber: this.actionCount,
            pageCoverage: metrics.pageCoverage,
            statementCoverage: null,
            pagesVisited: this.allVisitedPages.size,
            totalPages: this.totalPages,
            edgesTraversed: metrics.edgesTraversed,
            totalEdges: this.totalEdges,
            coverageData: this.coverageData,
            detailedCoverage: metrics.detailedCoverage,
        };
    }

    async collectNYCCoverage() {
        try {
            if (this.page.isClosed()) {
                console.warn('[PTG Test] Page already closed, cannot collect coverage');
                return null;
            }

            const coverage = await this.page.evaluate(() => window.__coverage__ || null);
            if (coverage) {
                console.log('[PTG Test] NYC coverage data collected successfully');
                return coverage;
            }

            console.warn('[PTG Test] window.__coverage__ is undefined - NYC instrumentation may not be enabled');
            return null;
        } catch (error) {
            console.error(`[PTG Test] Failed to collect NYC coverage: ${error.message}`);
            return null;
        }
    }

    async visitPageWithTimeLimit(pagePath, endTime) {
        if (Date.now() >= endTime) {
            console.log('[PTG Test] Time budget exhausted');
            return;
        }

        console.log(`[PTG Test] Visiting page: ${pagePath}`);
        const key = normalizeRouteKey(pagePath, this.config);
        console.log(`[PTG Test] Visiting page: ${key}`);

        if (this.visitedPages.has(key)) {
            console.log(`[PTG Test] Page already visited: ${key}`);

            await safeSleep(this.page, WAIT_TIMES.BEFORE_NEXT_ACTION, endTime);

            const before = getCurrentRouteKey(this.page, this.config);

            await this.page
                .goBack({timeout: Math.min(2000, remainingMs(endTime))})
                .catch(() => {
                });
            await safeSleep(this.page, WAIT_TIMES.BEFORE_NEXT_ACTION, endTime);

            const after = getCurrentRouteKey(this.page, this.config);

            if (after === before || after === 'about:blank') {
                await this.gotoPath(key, endTime);
                await safeSleep(this.page, WAIT_TIMES.BEFORE_NEXT_ACTION, endTime);
            }

            return;
        }

        this.visitedPages.add(key);
        this.allVisitedPages.add(key);
        await this.ensureMenusExpanded(endTime);

        const allPagesVisitedNow = this.allVisitedPages.size >= this.totalPages;

        if (!allPagesVisitedNow) {
            const edges = this.getEdgesFromPage(key);
            console.log(`[PTG Test] Found ${edges.length} edges from pagePath ${pagePath}`);
            console.log(`[PTG Test] Found ${edges.length} edges from key ${key}`);

            // Filter out edges pointing to already visited pages to save time
            const unvisitedEdges = edges.filter(edge => {
                const toKey = normalizeRouteKey(edge.to, this.config);
                return !this.visitedPages.has(toKey);
            });

            if (unvisitedEdges.length < edges.length) {
                console.log(`[PTG Test] Skipping ${edges.length - unvisitedEdges.length} edges to already visited pages`);
            }

            for (const edge of unvisitedEdges) {
                if (Date.now() >= endTime) {
                    console.log('[PTG Test] Time budget exhausted during edge traversal');
                    break;
                }

                try {
                    await this.traverseEdge(edge, key, endTime);
                } catch (error) {
                    console.error(`[PTG Test] Error traversing edge: ${error.message}`);
                }
            }
        } else {
            console.log(`[PTG Test] All pages visited - skipping edge traversal`);
        }

        if (Date.now() < endTime) {
            const fixedClicks = 10;
            console.log(`[PTG Test] Doing ${fixedClicks} random clicks on page ${key} (paper strategy)`);
            await this.fixedRandomClicks(fixedClicks, endTime);
        }

        if (Date.now() < endTime && key !== '/') {
            await safeSleep(this.page, WAIT_TIMES.BEFORE_NEXT_ACTION, endTime);

            const before = getCurrentRouteKey(this.page, this.config);

            await this.page
                .goBack({timeout: Math.min(2000, remainingMs(endTime))})
                .catch(() => {
                });
            await safeSleep(this.page, WAIT_TIMES.BEFORE_NEXT_ACTION, endTime);

            const after = getCurrentRouteKey(this.page, this.config);

            if (after === before || after === 'about:blank') {
                await this.gotoPath(this.findStartPage(), endTime);
                await safeSleep(this.page, WAIT_TIMES.BEFORE_NEXT_ACTION, endTime);
            }
        }
    }

    async fixedRandomClicks(maxClicks, endTime) {
        let successfulClicks = 0;

        // Selector matching Random test's approach
        const selector = [
            'button:visible',
            'a:visible',
            '[role="button"]:visible',
            'input[type="button"]:visible',
            'input[type="submit"]:visible',
            '.el-button:visible',
            '.el-menu-item:visible',
            '.el-sub-menu__title:visible',
            '.el-submenu__title:visible',
            '.el-dropdown-menu__item:visible',
            '.el-link:visible',
            '.el-pager li:visible',
        ].join(',');

        for (let i = 0; i < maxClicks; i++) {
            if (Date.now() >= endTime) {
                console.log(`[PTG Test] Time budget exhausted after ${successfulClicks} clicks`);
                break;
            }

            const timeout = Math.min(1200, remainingMs(endTime));
            if (timeout <= 0) break;

            const curPageBefore = getCurrentRouteKey(this.page, this.config);

            const candidates = this.page.locator(selector);
            const count = await candidates.count().catch(() => 0);

            if (count <= 0) {
                console.log(`[PTG Test] No clickable elements found`);
                break;
            }

            const tries = Math.min(10, count);
            let clicked = false;

            for (let t = 0; t < tries && !clicked; t++) {
                const idx = Math.floor(Math.random() * count);
                const el = candidates.nth(idx);

                const disabled = await el.evaluate((node) => {
                    const aria = node.getAttribute?.('aria-disabled');
                    const dis = node.getAttribute?.('disabled');
                    return aria === 'true' || dis !== null;
                }).catch(() => false);

                if (disabled) continue;

                const isAuthRelated = await isLogoutOrLoginRelated(el);
                if (isAuthRelated) continue;

                await el.scrollIntoViewIfNeeded({timeout: 500}).catch(() => {});
                clicked = await el.click({timeout, noWaitAfter: true})
                    .then(() => true)
                    .catch(() => false);

                if (clicked) {
                    successfulClicks++;
                    this.actionCount++;
                    console.log(`[PTG Test] Random click ${successfulClicks}/${maxClicks}, total actions: ${this.actionCount}`);
                    await safeSleep(this.page, WAIT_TIMES.AFTER_CLICK, endTime);

                    const curPageAfter = getCurrentRouteKey(this.page, this.config);
                    const newPageKey = normalizeRouteKey(curPageAfter, this.config);

                    if (!this.visitedPages.has(newPageKey) && curPageAfter !== curPageBefore) {
                        console.log(`[PTG Test] Random click navigated to new page: ${curPageAfter}, recursively exploring...`);
                        await this.visitPageWithTimeLimit(curPageAfter, endTime);

                        const currentPage = getCurrentRouteKey(this.page, this.config);
                        if (currentPage !== curPageBefore) {
                            await this.gotoPath(curPageBefore, endTime).catch(() => {});
                            await safeSleep(this.page, WAIT_TIMES.AFTER_NAVIGATION, endTime);
                        }
                    }

                    if (successfulClicks % 3 === 0) {
                        await closeOpenModals(this.page);
                    }
                    break;
                }
            }

            if (!clicked) {
                await closeOpenModals(this.page);
            }
        }

        console.log(`[PTG Test] Fixed random clicks completed: ${successfulClicks}/${maxClicks} successful`);
    }

    async calculateMetrics() {
        const pageCoverage = this.totalPages > 0 ? this.allVisitedPages.size / this.totalPages : 0;

        let edgesTraversed = 0;
        for (const edge of this.ptg?.edges || []) {
            const fromK = normalizeRouteKey(edge.from, this.config);
            const toK = normalizeRouteKey(edge.to, this.config);
            if (this.allVisitedPages.has(fromK) && this.allVisitedPages.has(toK)) {
                edgesTraversed++;
            }
        }

        return {
            pageCoverage,
            edgesTraversed,
            detailedCoverage: {
                totalPages: this.totalPages,
                visitedPages: this.allVisitedPages.size,
                totalEdges: this.totalEdges,
                edgesTraversed,
            },
        };
    }

    findStartPage() {
        const edges = this.ptg?.edges || [];

        const outDeg = new Map();
        for (const e of edges) {
            const fk = normalizeRouteKey(e.from, this.config);
            outDeg.set(fk, (outDeg.get(fk) || 0) + 1);
        }

        if (this.config?.startPage) {
            const k = normalizeRouteKey(this.config.startPage, this.config);
            if ((outDeg.get(k) || 0) > 0) return k;
            console.log(`[PTG Test] startPage ${k} has 0 outgoing edges, fallback to PTG best start`);
        }

        let best = null, bestDeg = -1;
        for (const [k, deg] of outDeg.entries()) {
            if (deg > bestDeg) {
                bestDeg = deg;
                best = k;
            }
        }
        if (best) return best;

        if (this.ptg?.nodes?.length) return normalizeRouteKey(this.ptg.nodes[0].name, this.config);
        return '/';
    }

    getEdgesFromPage(pageKey) {
        const k = normalizeRouteKey(pageKey, this.config);
        if (!this.ptg?.edges) return [];
        return this.ptg.edges.filter((e) => normalizeRouteKey(e.from, this.config) === k);
    }

    isRouterRedirectEdge(edge) {
        const e = (edge?.event || '').toLowerCase();
        const k = (edge?.selectorKind || '').toUpperCase();
        const note = (edge?.note || '').toLowerCase();
        const sel = edge?.selector;

        return (
            sel === '-' &&
            (e.includes('redirect') || e.includes('route') || k === 'ROUTER' || note.includes('redirect'))
        );
    }

    async gotoPath(to, endTime) {
        if (!to) return false;

        const key = normalizeRouteKey(to, this.config);
        const full = buildUrlFromRouteKey(this.config.baseUrl, key, this.config.routeMode, this.config);

        console.log(`[PTG Test] [router] goto ${full}`);
        await this.page.goto(full, {
            waitUntil: 'domcontentloaded',
            timeout: Math.min(4000, remainingMs(endTime)),
        }).catch((e) => console.log('[PTG Test] [router] goto failed:', e.message));

        await safeSleep(this.page, WAIT_TIMES.AFTER_GOTO, endTime);

        const curKey = getCurrentRouteKey(this.page, this.config);
        return curKey === key;
    }

    async _openDropdownIfNeeded(endTime) {
        if (this._openedDropdownOnce) return false;

        const timeout = Math.min(1200, remainingMs(endTime));
        if (timeout <= 0) return false;

        const triggerSelectors = [
            '.el-dropdown:visible',
            '.el-dropdown-link:visible',
            '.el-dropdown-selfdefine:visible',
            '.el-avatar:visible',
            '[class*="el-dropdown"]:visible',
        ];

        for (const sel of triggerSelectors) {
            const loc = this.page.locator(sel).first();
            const cnt = await loc.count().catch(() => 0);
            if (cnt <= 0) continue;

            const vis = await loc.isVisible({timeout}).catch(() => false);
            if (!vis) continue;

            const ok = await loc.click({timeout, noWaitAfter: true}).then(() => true).catch(() => false);
            if (ok) {
                this._openedDropdownOnce = true;
                await safeSleep(this.page, WAIT_TIMES.MENU_EXPAND, endTime);
                return true;
            }
        }

        return false;
    }

    async _expandSubMenuForItem(itemLocator, endTime) {
        const timeout = Math.min(1200, remainingMs(endTime));
        if (timeout <= 0) return false;

        const subMenu = itemLocator
            .locator('xpath=ancestor::*[contains(@class,"el-submenu") or contains(@class,"el-sub-menu")]')
            .first();
        const subCnt = await subMenu.count().catch(() => 0);
        if (subCnt <= 0) return false;

        const key = await subMenu
            .evaluate((n) => n.getAttribute('data-index') || n.className || (n.outerHTML ? n.outerHTML.slice(0, 60) : ''))
            .catch(() => '');

        if (key && this._expandedSubMenus.has(key)) return false;

        const title = subMenu.locator('.el-submenu__title, .el-sub-menu__title').first();
        const titleCnt = await title.count().catch(() => 0);
        if (titleCnt <= 0) return false;

        const isOpened = await subMenu.evaluate((n) => n.classList.contains('is-opened')).catch(() => false);

        if (!isOpened) {
            const ok = await title.click({timeout, noWaitAfter: true}).then(() => true).catch(() => false);
            if (ok) {
                if (key) this._expandedSubMenus.add(key);
                await safeSleep(this.page, WAIT_TIMES.MENU_EXPAND, endTime);
                return true;
            }
        } else {
            if (key) this._expandedSubMenus.add(key);
        }

        return false;
    }

    async _expandSubMenuByItemText(selector, endTime) {
        const timeout = Math.min(1200, remainingMs(endTime));
        if (timeout <= 0) return false;

        const m = String(selector).match(/has-text\((['"])(.*?)\1\)/);
        const text = m?.[2];
        if (!text) return false;

        const item = this.page.locator(`.el-menu-item:has-text("${text}")`).first();
        const cnt = await item.count().catch(() => 0);
        if (cnt <= 0) return false;

        const subMenu = item.locator('xpath=ancestor::*[contains(@class,"el-submenu") or contains(@class,"el-sub-menu")]').first();
        const subCnt = await subMenu.count().catch(() => 0);
        if (subCnt <= 0) return false;

        const title = subMenu.locator('.el-submenu__title, .el-sub-menu__title').first();
        await title.click({timeout, noWaitAfter: true}).catch(() => {
        });
        await safeSleep(this.page, WAIT_TIMES.MENU_EXPAND, endTime);
        return true;
    }

    async _expandAllSubMenus(endTime) {
        const timeout = Math.min(1200, remainingMs(endTime));
        if (timeout <= 0) return false;

        const titles = this.page.locator('.el-submenu__title, .el-sub-menu__title');
        const n = await titles.count().catch(() => 0);
        const limit = Math.min(n, 12);
        for (let i = 0; i < limit; i++) {
            const title = titles.nth(i);
            const sub = title.locator('xpath=ancestor::*[contains(@class,"el-submenu") or contains(@class,"el-sub-menu")]').first();

            const opened = await sub.evaluate(n => n.classList.contains('is-opened')).catch(() => false);
            if (opened) continue;

            await title.click({timeout, noWaitAfter: true}).catch(() => {
            });
            await safeSleep(this.page, WAIT_TIMES.SUBMENU_EXPAND, endTime);
        }
        return limit > 0;
    }

    async ensureMenusExpanded(endTime) {
        if (this._expandedAllOnce) return;
        const ok = await this._expandAllSubMenus(endTime);
        if (ok) this._expandedAllOnce = true;
    }

    async _revealForSelector(selector, endTime) {
        const lower = String(selector).toLowerCase();

        if (lower.includes('el-dropdown-menu__item')) {
            return await this._openDropdownIfNeeded(endTime);
        }

        if (
            lower.includes('el-submenu') ||
            lower.includes('el-sub-menu') ||
            lower.includes('el-menu-item')
        ) {
            const ok = await this._expandSubMenuByItemText(selector, endTime);
            if (ok) return true;

            const item = this.page.locator(selector).first();
            const cnt = await item.count().catch(() => 0);
            if (cnt > 0) {
                return await this._expandSubMenuForItem(item, endTime);
            }

            const titles = this.page.locator('.el-submenu__title:visible, .el-sub-menu__title:visible');
            const n = await titles.count().catch(() => 0);
            const limit = Math.min(3, n);

            for (let i = 0; i < limit; i++) {
                await titles
                    .nth(i)
                    .click({timeout: Math.min(800, remainingMs(endTime)), noWaitAfter: true})
                    .catch(() => {
                    });
                await safeSleep(this.page, WAIT_TIMES.BEFORE_NEXT_ACTION, endTime);
            }
            return limit > 0;
        }

        return false;
    }

    async traverseEdge(edge, currentPage, endTime) {
        if (Date.now() >= endTime) return;

        const {from, to, selector} = edge;
        console.log(`[PTG Test] Traversing edge: ${from} -> ${to}`);
        console.log(`[PTG Test] selector = ${JSON.stringify(selector)}`);

        if (selector === '-') {
            console.log(`[PTG Test] selector = "-"`);

            if (this.isRouterRedirectEdge(edge)) {
                console.log(`[PTG Test] treat as router redirect: ${from} -> ${to}`);
                const ok = await this.gotoPath(to, endTime);
                if (ok) {
                    this.actionCount++;
                    const toKey = normalizeRouteKey(to, this.config);
                    if (!this.visitedPages.has(toKey)) {
                        await this.visitPageWithTimeLimit(toKey, endTime);
                        await this._expandAllSubMenus(endTime);
                    }
                }
            } else {
                console.log(`[PTG Test] skip: selector is empty or '-' (not router redirect)`);
            }
            return;
        }

        const timeout = Math.min(2000, remainingMs(endTime));
        if (timeout <= 0) return;

        try {
            let loc = this.page.locator(selector).first();

            let count = await this.page.locator(selector).count().catch(() => 0);
            console.log(`[PTG Test] locator.count = ${count}`);

            if (count === 0) {
                const revealed = await this._revealForSelector(selector, endTime);
                if (revealed) {
                    loc = this.page.locator(selector).first();
                    count = await this.page.locator(selector).count().catch(() => 0);
                    console.log(`[PTG Test] locator.count(after reveal) = ${count}`);
                }
            }

            if (count === 0) {
                console.log(`[PTG Test] skip: element not found for selector`);
                return;
            }

            let visible = await loc.isVisible({timeout}).catch((e) => {
                console.log(`[PTG Test] isVisible error: ${e.message}`);
                return false;
            });
            console.log(`[PTG Test] isVisible = ${visible}`);

            if (!visible) {
                const revealed = await this._revealForSelector(selector, endTime);
                if (revealed) {
                    visible = await loc
                        .isVisible({timeout: Math.min(1200, remainingMs(endTime))})
                        .catch(() => false);
                    console.log(`[PTG Test] isVisible(after reveal) = ${visible}`);
                }
            }

            if (!visible) {
                console.log(`[PTG Test] skip: element exists but not visible`);
                return;
            }

            const isAuthRelated = await isLogoutOrLoginRelated(loc);
            if (isAuthRelated) {
                console.log(`[PTG Test] skip: element is logout/login related (avoiding logout)`);
                return;
            }

            const beforeUrl = this.page.url();
            console.log(`[PTG Test] before click url = ${beforeUrl}`);

            const clicked = await loc
                .click({timeout, noWaitAfter: true})
                .then(() => true)
                .catch((e) => {
                    console.log(`[PTG Test] click error: ${e.message}`);
                    return false;
                });

            if (!clicked) return;

            this.actionCount++;

            await safeSleep(this.page, WAIT_TIMES.AFTER_CLICK, endTime);

            const afterUrl = this.page.url();
            console.log(`[PTG Test] after  click url = ${afterUrl}`);

            const newKey = getCurrentRouteKey(this.page, this.config);
            const expectedKey = normalizeRouteKey(to, this.config);
            const currentKey = normalizeRouteKey(currentPage, this.config);

            console.log(`[PTG Test] newKey = ${newKey}, expected = ${expectedKey}`);

            if (newKey === expectedKey && !this.visitedPages.has(expectedKey)) {
                await this.visitPageWithTimeLimit(expectedKey, endTime);
            } else if (newKey !== currentKey && newKey !== expectedKey && !this.visitedPages.has(newKey)) {
                await this.visitPageWithTimeLimit(newKey, endTime);
            } else {
                console.log(`[PTG Test] no navigation / already visited / path not matched`);
            }
        } catch (e) {
            console.log(`[PTG Test] traverseEdge exception: ${e.message}`);
        }
    }
}


class RandomTest {
    constructor(page, ptg, config) {
        this.page = page;
        this.ptg = ptg;
        this.config = config;

        this.visitedPages = new Set();
        this.actionCount = 0;
        this.coverageData = null;

        this.totalPages = ptg?.nodes?.length || 0;
        this.totalEdges = ptg?.edges?.length || 0;
        this._expandedAllOnce = false;
    }

    async test(timeBudgetSeconds = 300) {
        console.log(`[Random Test] Starting naive random testing with ${timeBudgetSeconds}s time budget...`);
        const endTime = Date.now() + timeBudgetSeconds * 1000;

        try {
            while (Date.now() < endTime) {
                const curKey = getCurrentRouteKey(this.page, this.config);
                this.visitedPages.add(curKey);

                await this.naiveClickOnce(endTime);
                await safeSleep(this.page, WAIT_TIMES.AFTER_CLICK, endTime); // 统一使用 WAIT_TIMES
            }

            await safeSleep(this.page, remainingMs(endTime), endTime);
        } finally {
            this.coverageData = await this.collectNYCCoverage();
        }

        const duration = (Date.now() - this.startTime) / 1000;
        const metrics = await this.calculateMetrics();

        console.log(`[Random Test] Completed in ${duration}s`);
        console.log(`[Random Test] Action Number (AN): ${this.actionCount}`);
        console.log(`[Random Test] Page Coverage (PC): ${(metrics.pageCoverage * 100).toFixed(2)}%`);

        return {
            duration,
            actionNumber: this.actionCount,
            pageCoverage: metrics.pageCoverage,
            statementCoverage: null,
            pagesVisited: this.visitedPages.size,
            totalPages: this.totalPages,
            edgesTraversed: metrics.edgesTraversed,
            totalEdges: this.totalEdges,
            coverageData: this.coverageData,
            detailedCoverage: metrics.detailedCoverage
        };
    }

    async naiveClickOnce(endTime) {
        const timeout = Math.min(1200, remainingMs(endTime));
        if (timeout <= 0) return;

        const selector = [
            'button:visible',
            'a:visible',
            '[role="button"]:visible',
            'input[type="button"]:visible',
            'input[type="submit"]:visible',
            '.el-button:visible',
            '.el-menu-item:visible',
            '.el-sub-menu__title:visible',
            '.el-submenu__title:visible',
            '.el-dropdown-menu__item:visible',
            '.el-link:visible',
            '.el-pager li:visible',
        ].join(',');

        const candidates = this.page.locator(selector);

        const n = await candidates.count().catch(() => 0);
        if (n <= 0) return;

        const tries = Math.min(10, n);
        for (let t = 0; t < tries; t++) {
            const idx = Math.floor(Math.random() * n);
            const el = candidates.nth(idx);

            const disabled = await el.evaluate((node) => {
                const aria = node.getAttribute?.('aria-disabled');
                const dis = node.getAttribute?.('disabled');
                return aria === 'true' || dis !== null;
            }).catch(() => false);

            if (disabled) continue;

            const isAuthRelated = await isLogoutOrLoginRelated(el);
            if (isAuthRelated) {
                console.log(`[Random Test] skip logout/login element`);
                continue;
            }

            await el.scrollIntoViewIfNeeded().catch(() => {
            });
            const ok = await el.click({timeout, noWaitAfter: true, trial: true}).then(() => true).catch(() => false);
            if (!ok) continue;

            const clicked = await el.click({timeout, noWaitAfter: true}).then(() => true).catch(() => false);
            if (clicked) {
                this.actionCount++;
                return;
            }
        }
    }

    async collectNYCCoverage() {
        try {
            if (this.page.isClosed()) {
                console.warn('[Random Test] Page already closed, cannot collect coverage');
                return null;
            }
            const coverage = await this.page.evaluate(() => window.__coverage__ || null);
            if (coverage) {
                console.log('[Random Test] NYC coverage data collected successfully');
                return coverage;
            }
            console.warn('[Random Test] window.__coverage__ is undefined - NYC instrumentation may not be enabled');
            return null;
        } catch (error) {
            console.error(`[Random Test] Failed to collect NYC coverage: ${error.message}`);
            return null;
        }
    }

    async calculateMetrics() {
        const pageCoverage = this.totalPages > 0 ? this.visitedPages.size / this.totalPages : 0;

        let edgesTraversed = 0;
        for (const edge of this.ptg?.edges || []) {
            const fromK = normalizeRouteKey(edge.from, this.config);
            const toK = normalizeRouteKey(edge.to, this.config);
            if (this.visitedPages.has(fromK) && this.visitedPages.has(toK)) {
                edgesTraversed++;
            }
        }

        return {
            pageCoverage,
            edgesTraversed,
            detailedCoverage: {
                totalPages: this.totalPages,
                visitedPages: this.visitedPages.size,
                totalEdges: this.totalEdges,
                edgesTraversed,
            }
        };
    }
}


function findLatestPTG(projectName) {
    const outDir = path.join(projectOutRoot(), projectName);

    if (!fs.existsSync(outDir)) {
        throw new Error(`Output directory not found: ${outDir}`);
    }

    const isTimeDir = (name) => {
        return /^\d{8}[-_]\d{6}/.test(name);
    };

    const candidates = fs.readdirSync(outDir)
        .filter((name) => {
            const full = path.join(outDir, name);
            if (!fs.statSync(full).isDirectory()) return false;
            if (name === 'runs') return false;
            if (!isTimeDir(name)) return false;
            // 必须存在 stage3_graph.json
            return fs.existsSync(path.join(full, 'stage3_graph.json'));
        })
        .sort()
        .reverse();

    if (candidates.length === 0) {
        throw new Error(`No PTG stage3_graph.json found under: ${outDir}`);
    }

    const latestDir = candidates[0];
    const ptgPath = path.join(outDir, latestDir, 'stage3_graph.json');
    return ptgPath;
}

async function applyAuth({context, page, config}) {
    const auth = config.auth || {authType: 9};
    const authType = auth.authType ?? 9;

    if (authType === 0) {
        const raw = auth.cookie?.cookies || [];
        if (!raw.length) return;

        const origin = new URL(config.baseUrl);
        const host = origin.hostname; // localhost

        const cookiesToSet = raw.map((c) => ({
            name: c.name,
            value: c.value,
            domain: c.domain || host,
            path: c.path || '/',
            ...(typeof c.httpOnly === 'boolean' ? {httpOnly: c.httpOnly} : {}),
            ...(typeof c.secure === 'boolean' ? {secure: c.secure} : {}),
            ...(c.sameSite ? {sameSite: c.sameSite} : {}),
            ...(typeof c.expires === 'number' ? {expires: c.expires} : {}),
        }));

        console.log('[AUTH] cookiesToSet(domain)=', cookiesToSet);
        await context.addCookies(cookiesToSet);

        console.log('[AUTH] cookies(after)=', await context.cookies(origin.origin));
        return;
    }

    if (authType === 1) {
        const items = auth.sessionStorage?.items || [];
        if (!items.length) return;

        const targetOrigin = new URL(config.baseUrl).origin;

        await context.addInitScript(({pairs, origin}) => {
            try {
                if (location.origin !== origin) return;
                for (const {key, value} of pairs) {
                    const v = typeof value === 'string' ? value : JSON.stringify(value);
                    sessionStorage.setItem(key, v);
                }
            } catch (e) {
            }
        }, {pairs: items, origin: targetOrigin});

        const resp = await page.goto(targetOrigin + '/', {
            waitUntil: 'domcontentloaded',
            timeout: 15000
        }).catch(() => null);
        if (!resp || !resp.ok()) {
            console.log('[AUTH] goto failed, skip immediate sessionStorage write. url=', page.url());
            return;
        }

        await page.evaluate((pairs) => {
            for (const {key, value} of pairs) {
                const v = typeof value === 'string' ? value : JSON.stringify(value);
                sessionStorage.setItem(key, v);
            }
        }, items);

        return;
    }

    if (authType === 2) {
        const items = auth.localStorage?.items || [];
        if (!items.length) return;

        const targetOrigin = new URL(config.baseUrl).origin;

        await context.addInitScript(({pairs, origin}) => {
            try {
                if (location.origin !== origin) return;
                for (const {key, value} of pairs) {
                    const v = typeof value === 'string' ? value : JSON.stringify(value);
                    localStorage.setItem(key, v);
                }
            } catch (e) {
            }
        }, {pairs: items, origin: targetOrigin});

        const resp = await page.goto(targetOrigin + '/', {
            waitUntil: 'domcontentloaded',
            timeout: 15000,
        }).catch(() => null);

        if (!resp || !resp.ok()) {
            console.log('[AUTH] goto failed, skip immediate localStorage write. url=', page.url());
            return;
        }

        await page.evaluate((pairs) => {
            for (const {key, value} of pairs) {
                const v = typeof value === 'string' ? value : JSON.stringify(value);
                localStorage.setItem(key, v);
            }
        }, items);

        return;
    }

    if (authType === 4) {
        const raw = auth.jwt?.token;
        if (!raw) return;

        const scheme = auth.jwt?.scheme || 'Bearer';
        const bearer = raw.startsWith(scheme + ' ') ? raw : `${scheme} ${raw}`;

        await page.route('**/*', async (route) => {
            const req = route.request();
            const headers = {
                ...req.headers(),
                Authorization: bearer,
            };
            await route.continue({headers});
        });

        return;
    }
}


async function main(projectName, mode, totalDuration, saveInterval = null) {
    if (!saveInterval) {
        saveInterval = totalDuration;
    }

    const isSingleRun = (saveInterval === totalDuration);
    const expectedSnapshots = Math.floor(totalDuration / saveInterval);

    console.log('\n' + '='.repeat(80));
    console.log(`Project: ${projectName}`);
    console.log(`Mode: ${mode}`);
    console.log(`Total Duration: ${totalDuration}s`);
    console.log(`Save Interval: ${saveInterval}s`);

    if (isSingleRun) {
        console.log(`Execution Mode: SINGLE RUN (save final result at ${totalDuration}s)`);
    } else {
        console.log(`Execution Mode: ITERATIVE (time series: ${saveInterval}, ${saveInterval*2}, ${saveInterval*3}...${totalDuration})`);
        console.log(`Expected Snapshots: ${expectedSnapshots}`);
    }

    console.log('='.repeat(80) + '\n');

    const config = PROJECT_CONFIGS[projectName];
    if (!config) {
        throw new Error(`Unknown project: ${projectName}. Available: ${Object.keys(PROJECT_CONFIGS).join(', ')}`);
    }

    const runId = makeRunId(projectName, mode);

    const runsRoot = path.join(projectOutRoot(), projectName, 'runs', runId);
    ensureDir(runsRoot);

    const ptgPath = findLatestPTG(projectName);
    console.log(`PTG: ${ptgPath}\n`);
    const ptg = JSON.parse(fs.readFileSync(ptgPath, 'utf-8'));

    console.log('[PTG] nodes=', ptg?.nodes?.length || 0, 'edges=', ptg?.edges?.length || 0);

    await runIterativeMode(projectName, mode, totalDuration, saveInterval, config, ptg, runId, runsRoot);
}


async function runIterativeMode(projectName, mode, totalDuration, saveInterval, config, ptg, runId, runsRoot) {
    console.log('\n[ITERATIVE MODE] Starting continuous test...\n');
    console.log(`Total Duration: ${totalDuration}s`);
    console.log(`Save Interval: ${saveInterval}s`);
    console.log(`Expected Snapshots: ${Math.floor(totalDuration / saveInterval)}\n`);

    const startTime = Date.now();
    const endTime = startTime + totalDuration * 1000;
    let snapshotCount = 0;
    let lastSaveTime = startTime;

    let browser;
    let testInstance;
    let page;

    try {
        browser = await chromium.launch({headless: false});
        const context = await browser.newContext();
        page = await context.newPage();

        page.setDefaultTimeout(1500);
        page.setDefaultNavigationTimeout(5000);

        await applyAuth({context, page, config});

        page.on('dialog', async (d) => {
            console.log('[DIALOG]', d.type(), d.message());
            try {
                await d.accept();
            } catch (e) {
                console.log('[DIALOG] accept failed', e.message);
            }
        });

        const startKey = normalizeRouteKey(config.startPage ?? '/', config);
        const startUrl = buildUrlFromRouteKey(config.baseUrl, startKey, config.routeMode, config);
        await page.goto(startUrl, {waitUntil: 'domcontentloaded'});
        await page.waitForTimeout(1500);

        if (mode === 'ptg') {
            testInstance = new PTGTest(page, ptg, config);
        } else {
            testInstance = new RandomTest(page, ptg, config);
        }

        console.log(`[ITERATIVE] Test started at ${new Date().toISOString()}\n`);

        const testPromise = testInstance.test(totalDuration);

        const saveLoop = async () => {
            while (Date.now() < endTime) {
                const now = Date.now();
                const elapsed = (now - lastSaveTime) / 1000;

                if (elapsed >= saveInterval) {
                    snapshotCount++;
                    const currentElapsed = (now - startTime) / 1000;

                    console.log(`\n${'='.repeat(80)}`);
                    console.log(`[SNAPSHOT ${snapshotCount}] Saving results at ${currentElapsed.toFixed(1)}s`);
                    console.log('='.repeat(80));

                    const timeBudget = snapshotCount * saveInterval;
                    await saveSnapshot(
                        projectName, mode, runId, runsRoot, config,
                        testInstance, snapshotCount, currentElapsed, saveInterval, timeBudget
                    );

                    lastSaveTime = now;
                    console.log(`[SNAPSHOT ${snapshotCount}] Saved successfully\n`);
                }

                await new Promise(resolve => setTimeout(resolve, 1000));
            }
        };

        await Promise.race([
            testPromise,
            saveLoop()
        ]);

        snapshotCount++;
        const finalElapsed = (Date.now() - startTime) / 1000;
        console.log(`\n${'='.repeat(80)}`);
        console.log(`[FINAL SNAPSHOT ${snapshotCount}] Saving final results at ${finalElapsed.toFixed(1)}s`);
        console.log('='.repeat(80));

        const timeBudget = totalDuration;
        await saveSnapshot(
            projectName, mode, runId, runsRoot, config,
            testInstance, snapshotCount, finalElapsed, saveInterval, timeBudget, true
        );

        console.log(`\n${'='.repeat(80)}`);
        console.log('ITERATIVE MODE COMPLETED');
        console.log('='.repeat(80));
        console.log(`Total Duration:       ${finalElapsed.toFixed(2)}s`);
        console.log(`Total Snapshots:      ${snapshotCount}`);
        console.log(`Results saved in:     ${runsRoot}`);
        console.log('='.repeat(80) + '\n');

    } finally {
        if (browser) {
            try {
                await browser.close();
            } catch (_) {
            }
        }
    }
}


async function saveSnapshot(projectName, mode, runId, runsRoot, config, testInstance, snapshotNum, elapsed, saveInterval, timeBudget, isFinal = false) {
    const snapshotId = `snapshot_${snapshotNum.toString().padStart(3, '0')}`;

    const coverageData = await testInstance.page.evaluate(() => window.__coverage__ || null);

    const cumulativePages = testInstance.allVisitedPages || testInstance.visitedPages;
    const results = {
        actionNumber: testInstance.actionCount,
        pagesVisited: cumulativePages.size,
        duration: elapsed,
        coverageData: coverageData,
        pageCoverage: testInstance.totalPages > 0 ? cumulativePages.size / testInstance.totalPages : 0,
        statementCoverage: 0
    };

    let coverageFile = null;
    let nycRawFile = null;
    let nycDir = null;

    if (coverageData) {
        coverageFile = path.join(runsRoot, `${mode}_coverage_${snapshotId}.json`);
        safeWriteJson(coverageFile, toJsonSafe(coverageData));

        nycDir = nycOutputRoot(runId);
        ensureDir(nycDir);
        nycRawFile = path.join(nycDir, `coverage-${projectName}-${mode}-${runId}-${snapshotId}.json`);
        safeWriteJson(nycRawFile, toJsonSafe(coverageData));
    }

    let reportDir = null;
    if (coverageData && config.nyc?.cwd && config.nyc?.reportRoot) {
        const tempDir = nycDir;
        reportDir = path.join(config.nyc.reportRoot, `${runId}_${snapshotId}`);

        const nycSummary = await runNycReport({tempDir, cwdDir: config.nyc.cwd, reportDir});

        const pct = nycSummary?.total?.statements?.pct;
        if (typeof pct === 'number') {
            results.statementCoverage = pct / 100;
        }
        results.nycSummary = nycSummary;
    }

    const resultsFile = path.join(runsRoot, `${mode}_results_${snapshotId}.json`);
    safeWriteJson(resultsFile, {
        project: projectName,
        mode,
        runId,
        snapshotId,
        snapshotNumber: snapshotNum,
        timestamp: new Date().toISOString(),
        timeBudget: timeBudget,
        elapsed: elapsed,
        saveInterval: saveInterval,
        isFinal: isFinal,
        ...results,
    });

    console.log(`  Actions: ${results.actionNumber}, Pages: ${results.pagesVisited}, SC: ${(results.statementCoverage * 100).toFixed(2)}%`);
    console.log(`  Saved: ${resultsFile}`);
}

if (require.main === module) {
    const args = process.argv.slice(2);

    if (args.length < 2) {
        console.error('Usage: node ptg-test-with-coverage.js <project> <mode> <totalDuration> [saveInterval]');
        console.error('');
        console.error('Arguments:');
        console.error('  project        - Project name');
        console.error('  mode           - ptg or random');
        console.error('  totalDuration  - Total execution time in seconds (default: 300)');
        console.error('  saveInterval   - Save results every N seconds (default: totalDuration)');
        console.error('');
        console.error('Execution Modes:');
        console.error('  Single Run:    saveInterval = totalDuration (or omit saveInterval)');
        console.error('                 Example: 300 or 300 300 → saves final result at 300s');
        console.error('');
        console.error('  Time Series:   saveInterval < totalDuration');
        console.error('                 Example: 600 60 → saves at 60s, 120s, 180s...600s');
        console.error('                 This produces a continuous time series, not multiple runs');
        console.error('');
        console.error('Available projects:');
        Object.keys(PROJECT_CONFIGS).forEach((name) => console.error(`  - ${name}`));
        console.error('');
        console.error('Examples:');
        console.error('  node ptg-test-with-coverage.js library ptg 300        # Single run: save at 300s');
        console.error('  node ptg-test-with-coverage.js library ptg 300 300    # Same as above');
        console.error('  node ptg-test-with-coverage.js library ptg 600 60     # Time series: 60,120,180...600');
        console.error('  node ptg-test-with-coverage.js library random 1800 300 # Time series: 300,600,900...1800');
        process.exit(1);
    }

    const [projectName, mode, timeBudget = '300', saveInterval] = args;

    if (mode !== 'ptg' && mode !== 'random') {
        console.error('Error: mode must be "ptg" or "random"');
        process.exit(1);
    }

    const timeBudgetNum = parseInt(timeBudget, 10);
    const saveIntervalNum = saveInterval ? parseInt(saveInterval, 10) : null;

    main(projectName, mode, timeBudgetNum, saveIntervalNum)
        .then(() => {
            console.log('Test completed');
            process.exit(0);
        })
        .catch((error) => {
            console.error('Error:', error.message);
            process.exit(1);
        });
}

module.exports = {PTGTest, RandomTest, main, PROJECT_CONFIGS};
