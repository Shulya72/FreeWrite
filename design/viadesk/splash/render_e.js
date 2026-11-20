const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const [mode] = process.argv.slice(2);   // preview — с надписью; bg — только фон; hero — только надпись (прозрачная)
  const devs = { tablet: [800, 1280, 4], phone: [360, 780, 6], land: [1280, 800, 4] };
  for (const [d, [w, h, k]] of Object.entries(devs)) {
    if (mode === 'preview' && d === 'land') continue;
    const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: k });
    const q = mode === 'preview' ? '&hero=1&nobld=1' : '';
    await p.goto(`file://${__dirname}/bg.html?v=E&w=${w}&h=${h}${q}`);
    await p.waitForFunction(() => document.title === 'ok', null, { timeout: 120000 });
    await p.screenshot({ path: `e_${mode}_${d}_big.png` });
    await p.close();
  }
  await b.close();
})();
