const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1236, height: 372 }, deviceScaleFactor: 3 });
  await p.goto('file://' + __dirname + '/hero_alpha.html'); await p.waitForFunction(() => document.title === 'ok');
  await p.screenshot({ path: 'hero_alpha_big.png', omitBackground: true }); await b.close();
})();
