const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  // Фоны: клетка и свечение, без плашки.
  const devs = { tablet: [800, 1280, 4], phone: [360, 780, 6], land: [1280, 800, 4] };
  for (const [d, [w, h, k]] of Object.entries(devs)) {
    const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: k });
    await p.goto(`file://${__dirname}/bg.html?v=R&w=${w}&h=${h}&light=1&noplate=1`);
    await p.waitForFunction(() => document.title === 'ok', null, { timeout: 120000 });
    await p.screenshot({ path: `g_bg_${d}_big.png` }); await p.close();
  }
  // Плашка со знаком и надписью, прозрачный фон.
  const p = await b.newPage({ viewport: { width: 1456, height: 560 }, deviceScaleFactor: 3 });
  await p.goto(`file://${__dirname}/hero_glass.html`); await p.waitForFunction(() => document.title === 'ok');
  await p.screenshot({ path: 'g_hero_big.png', omitBackground: true }); await p.close();
  await b.close();
})();
