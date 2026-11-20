// Векторный «баннер» ViaDesk в единицах исходной картинки (1536 × 406): знак, надпись, кран и дом.
// heroSvg(opts) → строка SVG-элементов (без <svg>), id с префиксом «h».
function heroSvg(o = {}) {
  const F = (n) => Math.round(n * 100) / 100;
  const P = (pts) => pts.map(p => F(p[0]) + ',' + F(p[1])).join(' ');
  // Скруглённый многоугольник: углы срезаны дугой радиуса r.
  const rpath = (pts, r) => {
    const n = pts.length; let d = '';
    for (let i = 0; i < n; i++) {
      const p0 = pts[(i - 1 + n) % n], p1 = pts[i], p2 = pts[(i + 1) % n];
      const v1 = [p0[0] - p1[0], p0[1] - p1[1]], v2 = [p2[0] - p1[0], p2[1] - p1[1]];
      const l1 = Math.hypot(...v1), l2 = Math.hypot(...v2);
      const rr = Array.isArray(r) ? r[i] : r, k1 = Math.min(rr, l1 / 2), k2 = Math.min(rr, l2 / 2);
      const a = [p1[0] + v1[0] / l1 * k1, p1[1] + v1[1] / l1 * k1], b = [p1[0] + v2[0] / l2 * k2, p1[1] + v2[1] / l2 * k2];
      d += (i === 0 ? 'M' : 'L') + F(a[0]) + ' ' + F(a[1]) + 'Q' + F(p1[0]) + ' ' + F(p1[1]) + ' ' + F(b[0]) + ' ' + F(b[1]);
    }
    return d + 'Z';
  };
  const out = [];
  const add = (s) => out.push(s);
  const line = (x1, y1, x2, y2, st, w, ex = '') => add(`<line x1="${F(x1)}" y1="${F(y1)}" x2="${F(x2)}" y2="${F(y2)}" stroke="${st}" stroke-width="${w}" stroke-linecap="round" ${ex}/>`);

  add(`<defs>
  <linearGradient id="hTeal" gradientUnits="userSpaceOnUse" x1="130" y1="100" x2="380" y2="310">
    <stop offset="0" stop-color="#8AF3EC"/><stop offset="0.35" stop-color="#63D8D4"/><stop offset="0.75" stop-color="#4CC4C8"/><stop offset="1" stop-color="#58D2D2"/></linearGradient>
  <linearGradient id="hTealSheen" gradientUnits="userSpaceOnUse" x1="120" y1="0" x2="270" y2="0">
    <stop offset="0" stop-color="#E8FFFD" stop-opacity="0.55"/><stop offset="0.18" stop-color="#FFFFFF" stop-opacity="0.12"/><stop offset="0.6" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
  <linearGradient id="hWhite" gradientUnits="userSpaceOnUse" x1="590" y1="170" x2="410" y2="370">
    <stop offset="0" stop-color="#FFFFFF"/><stop offset="0.35" stop-color="#EEF2F7"/><stop offset="0.75" stop-color="#C4CEDB"/><stop offset="1" stop-color="#9AA8BB"/></linearGradient>
  <linearGradient id="hGlass" gradientUnits="userSpaceOnUse" x1="340" y1="100" x2="470" y2="280">
    <stop offset="0" stop-color="#3B82A6" stop-opacity="0.42"/><stop offset="1" stop-color="#16425E" stop-opacity="0.5"/></linearGradient>
  <linearGradient id="hGlass2" gradientUnits="userSpaceOnUse" x1="440" y1="90" x2="520" y2="270">
    <stop offset="0" stop-color="#25587A" stop-opacity="0.55"/><stop offset="1" stop-color="#0E2D45" stop-opacity="0.7"/></linearGradient>
  <linearGradient id="hLayer" gradientUnits="userSpaceOnUse" x1="0" y1="250" x2="0" y2="370">
    <stop offset="0" stop-color="#1A4F66" stop-opacity="0.18"/><stop offset="0.7" stop-color="#2A7486" stop-opacity="0.30"/><stop offset="1" stop-color="#3B9AA6" stop-opacity="0.45"/></linearGradient>
  <linearGradient id="hAmber" gradientUnits="userSpaceOnUse" x1="410" y1="180" x2="400" y2="242">
    <stop offset="0" stop-color="#FFC24E"/><stop offset="1" stop-color="#E9922A"/></linearGradient>
  <linearGradient id="hBld" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="406">
    <stop offset="0" stop-color="#8DB8E2"/><stop offset="0.7" stop-color="#6F9FD0"/><stop offset="1" stop-color="#5A8CC0"/></linearGradient>
  <filter id="hGlowS" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="2.2" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  <filter id="hGlowM" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="5" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  <filter id="hShadow" x="-30%" y="-30%" width="160%" height="170%"><feGaussianBlur in="SourceAlpha" stdDeviation="9"/><feOffset dy="10"/>
    <feComponentTransfer><feFuncA type="linear" slope="0.55"/></feComponentTransfer><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  <filter id="hTextSh" x="-10%" y="-30%" width="120%" height="160%"><feGaussianBlur in="SourceAlpha" stdDeviation="7"/><feOffset dy="6"/>
    <feComponentTransfer><feFuncA type="linear" slope="0.45"/></feComponentTransfer><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  <filter id="hBlur8"><feGaussianBlur stdDeviation="8"/></filter>
  </defs>`);

  // ---------------- Знак
  add(`<g filter="url(#hShadow)">`);
  // Стеклянные слои под лентой (V-образные «подошвы»).
  const layer = (dx, dy, x1, op) => {
    const pts = [[200 + dx, 230 + dy], [272 + dx, 300 + dy], [x1, 300 + dy], [x1 + 46, 228 + dy]];
    const d = rpath([[pts[0][0], pts[0][1]], [pts[1][0], pts[1][1] + 6], [pts[2][0], pts[2][1] + 6], [pts[3][0], pts[3][1]]], 16);
    add(`<path d="${d}" fill="url(#hLayer)" stroke="#8FF0EE" stroke-opacity="${op}" stroke-width="2"/>`);
    add(`<path d="M${pts[1][0] + 10} ${pts[1][1] + 6} L${pts[2][0] - 10} ${pts[2][1] + 6}" stroke="#C8FFFB" stroke-opacity="${op * 0.8}" stroke-width="2.4" stroke-linecap="round" filter="url(#hGlowS)"/>`);
  };
  layer(28, 58, 462, 0.75);
  layer(14, 30, 410, 0.85);
  // Белый лист — правая сторона «V».
  add(`<path d="${rpath([[505, 172], [590, 172], [478, 366], [392, 366]], [10, 10, 12, 12])}" fill="url(#hWhite)"/>`);
  add(`<path d="M588 176 L480 362" stroke="#FFFFFF" stroke-opacity="0.85" stroke-width="2"/>`);
  // Задний стеклянный лист с белой рамкой.
  add(`<path d="${rpath([[440, 89], [585, 89], [482, 268], [337, 268]], 8)}" fill="url(#hGlass2)" stroke="#F2F8FF" stroke-opacity="1" stroke-width="3.6" filter="url(#hGlowS)"/>`);
  // Передний стеклянный лист с планом.
  const S = [[333, 113], [545, 113], [470, 270], [258, 270]];
  add(`<path d="${rpath(S, 7)}" fill="url(#hGlass)" stroke="#9FD6F0" stroke-opacity="0.7" stroke-width="1.8"/>`);
  const at = (u, v) => { // точка на листе (u — вдоль, v — вниз)
    const top = [S[0][0] + (S[1][0] - S[0][0]) * u, S[0][1] + (S[1][1] - S[0][1]) * u];
    const bot = [S[3][0] + (S[2][0] - S[3][0]) * u, S[3][1] + (S[2][1] - S[3][1]) * u];
    return [top[0] + (bot[0] - top[0]) * v, top[1] + (bot[1] - top[1]) * v];
  };
  for (const u of [0.2, 0.4, 0.6, 0.8]) { const a = at(u, 0.03), b = at(u, 0.97); line(a[0], a[1], b[0], b[1], '#BFE6FF', 0.9, 'stroke-opacity="0.35"'); }
  for (const v of [0.25, 0.5, 0.75]) { const a = at(0.03, v), b = at(0.97, v); line(a[0], a[1], b[0], b[1], '#BFE6FF', 0.9, 'stroke-opacity="0.35"'); }
  // Стены плана.
  const walls = [[[0.12, 0.11], [0.52, 0.11]], [[0.62, 0.11], [0.93, 0.11]], [[0.5, 0.11], [0.5, 0.3]], [[0.78, 0.11], [0.74, 0.55]],
    [[0.05, 0.28], [0.22, 0.28]], [[0.3, 0.28], [0.44, 0.28]], [[0.56, 0.28], [0.68, 0.28]], [[0.16, 0.28], [0.12, 0.55]],
    [[0.02, 0.6], [0.16, 0.6]], [[0.24, 0.6], [0.4, 0.6]]];
  add(`<g filter="url(#hGlowS)">`);
  for (const [p, q] of walls) { const a = at(...p), b = at(...q); line(a[0], a[1], b[0], b[1], '#F4FAFF', 3.2, 'stroke-opacity="0.95"'); }
  for (const c of [[0.5, 0.28], [0.16, 0.6], [0.3, 0.6], [0.62, 0.28]]) { const a = at(...c); add(`<rect x="${F(a[0] - 3)}" y="${F(a[1] - 3)}" width="6" height="6" fill="#EAF6FF" opacity="0.9"/>`); }
  add(`</g>`);
  // Янтарная захватка с точками.
  const Z = [[410, 182], [493, 181], [457, 241], [368, 241]];
  add(`<polygon points="${P(Z)}" fill="#FFB52E" opacity="0.55" filter="url(#hBlur8)"/>`);
  add(`<polygon points="${P(Z)}" fill="url(#hAmber)" fill-opacity="0.92" stroke="#FFC861" stroke-width="2.6" stroke-linejoin="round"/>`);
  for (const q of Z) add(`<circle cx="${q[0]}" cy="${q[1]}" r="6" fill="#FFE7A8" stroke="#FFB52E" stroke-width="2.6" filter="url(#hGlowS)"/>`);
  // Бирюзовая лента — левая сторона «V».
  const ribbon = 'M140 102 L236 102 Q250 102 257 114 L356 285 Q368 306 346 306 L252 306 Q237 306 230 294 L127 121 Q120 108 133 103 Z';
  add(`<path d="${ribbon}" fill="url(#hTeal)"/>`);
  add(`<path d="${ribbon}" fill="url(#hTealSheen)"/>`);
  add(`<path d="M131 112 L229 286" stroke="#D9FFFB" stroke-opacity="0.8" stroke-width="2.2" stroke-linecap="round"/>`);
  add(`<path d="M146 103.5 L232 103.5" stroke="#F2FFFE" stroke-opacity="0.7" stroke-width="1.6" stroke-linecap="round"/>`);
  add(`</g>`);

  // ---------------- Надпись
  add(`<g filter="url(#hTextSh)">
    <text x="625" y="245" font-family="VMontserrat" font-weight="700" font-size="${o.fs || 166}" letter-spacing="${o.ls ?? -6}" fill="#FFFFFF">V<tspan>ı</tspan>aDesk</text>
  </g>`);
  // точка «i» — янтарный косой брусок
  add(`<polygon points="${P([[765, 126], [791, 126], [782, 147], [756, 147]])}" fill="#F7B23C"/>`);
  add(`<rect x="625" y="273" width="5.5" height="68" rx="2.5" fill="#F2A93B"/>`);
  add(`<text font-family="Inter" font-weight="400" font-size="${o.ts || 27.8}" fill="#EAF1F8" letter-spacing="0.38">
    <tspan x="653" y="302">Единое рабочее пространство</tspan><tspan x="653" y="337">для строительных и инженерных команд</tspan></text>`);

  if (o.noBuilding) return out.join('\n');
  // ---------------- Кран
  const C = '#86B2DE';
  add(`<g filter="url(#hGlowS)" stroke="${C}" fill="none" stroke-linecap="round">`);
  // башня
  add(`<path d="M1352 70 L1352 190 M1370 70 L1370 190" stroke-width="2.2"/>`);
  let zz = 'M1352 78'; for (let y = 78, k = 0; y < 186; y += 12, k++) zz += ` L${k % 2 ? 1352 : 1370} ${y + 12}`;
  add(`<path d="${zz}" stroke-width="1.1" stroke-opacity="0.8"/>`);
  add(`<path d="M1361 10 L1352 70 M1361 10 L1370 70 M1361 10 L1361 66" stroke-width="1.8"/>`);
  // стрела
  add(`<path d="M1146 87 L1440 40 M1150 81 L1352 49" stroke-width="2"/>`);
  let jz = 'M1152 86'; for (let i = 0; i < 26; i++) { const x = 1152 + i * 7.8, y1 = 86 - (x - 1146) * 0.16, y0 = 81 - (x - 1150) * 0.158; jz += ` L${F(x + 3.9)} ${F(i % 2 ? y1 : y0)}`; }
  add(`<path d="${jz}" stroke-width="0.9" stroke-opacity="0.75"/>`);
  add(`<path d="M1361 10 L1190 74 M1361 10 L1418 36" stroke-width="1.1" stroke-opacity="0.85"/>`);
  add(`<rect x="1408" y="32" width="34" height="27" rx="2" fill="#2C5B86" fill-opacity="0.7" stroke-width="1.8"/>`);
  add(`<rect x="1342" y="56" width="38" height="16" rx="2" fill="#2C5B86" fill-opacity="0.6" stroke-width="1.6"/>`);
  add(`<path d="M1166 86 L1166 118" stroke-width="1.2"/><rect x="1161" y="117" width="10" height="9" fill="#2C5B86" stroke-width="1.4"/>`);
  add(`</g>`);

  // ---------------- Дом в три уступа
  add(`<g stroke="url(#hBld)" stroke-linecap="round" fill="none" filter="url(#hGlowS)">`);
  const face = (pts, fill) => add(`<polygon points="${P(pts)}" fill="${fill}" stroke="none"/>`);
  // уступ 3 (верх), уступ 2 (основной), уступ 1 (подиум): левая грань — наклон −0,36, правая — +0,23
  const L = -0.36, R = 0.23;
  const block = (xl, xc, yTop, yBot, cols, floors) => {
    // левая грань от xl до xc, верх в точке угла xc — yTop
    const yl = (x, y) => y + (x - xc) * L;     // на левой грани
    const yr = (x, y) => y + (x - xc) * R;     // на правой грани
    face([[xl, yl(xl, yTop)], [xc, yTop], [xc, yBot], [xl, yl(xl, yBot)]], 'rgba(48,96,150,0.22)');
    face([[xc, yTop], [1540, yr(1540, yTop)], [1540, yr(1540, yBot)], [xc, yBot]], 'rgba(32,72,120,0.28)');
    add(`<path d="M${F(xl)} ${F(yl(xl, yTop))} L${xc} ${yTop} L1540 ${F(yr(1540, yTop))}" stroke-width="2.6"/>`);
    add(`<path d="M${F(xl)} ${F(yl(xl, yBot))} L${xc} ${yBot} L1540 ${F(yr(1540, yBot))}" stroke-width="2.2"/>`);
    add(`<path d="M${xl} ${F(yl(xl, yTop))} L${xl} ${F(yl(xl, yBot))} M${xc} ${yTop} L${xc} ${yBot}" stroke-width="2.6"/>`);
    for (let k = 1; k < floors; k++) { const y = yTop + (yBot - yTop) * k / floors;
      add(`<path d="M${F(xl)} ${F(yl(xl, y))} L${xc} ${F(y)} L1540 ${F(yr(1540, y))}" stroke-width="1.6" stroke-opacity="0.85"/>`); }
    for (const x of cols) add(`<path d="M${x} ${F(x < xc ? yl(x, yTop) : yr(x, yTop))} L${x} ${F(x < xc ? yl(x, yBot) : yr(x, yBot))}" stroke-width="1.7" stroke-opacity="0.9"/>`);
    // арматурные выпуски на крыше
    for (let x = xl + 8; x < 1536; x += 17) { const y0 = x < xc ? yl(x, yTop) : yr(x, yTop); add(`<path d="M${F(x)} ${F(y0)} L${F(x)} ${F(y0 - 9)}" stroke-width="0.9" stroke-opacity="0.6"/>`); }
  };
  block(1375, 1480, 112, 160, [1405, 1442, 1515], 1);
  block(1318, 1480, 145, 270, [1350, 1392, 1437, 1515], 3);
  block(1263, 1398, 272, 352, [1290, 1330, 1365, 1440, 1490], 2);
  add(`</g>`);
  // земля
  add(`<g stroke="#5E93C8" stroke-linecap="round" fill="none" stroke-opacity="0.45">
    <path d="M1180 360 L1536 338" stroke-width="1.4"/><path d="M1220 372 L1536 352" stroke-width="1.1"/><path d="M1260 386 L1536 368" stroke-width="1"/>
    <path d="M1300 400 L1536 384" stroke-width="0.9"/></g>`);
  return out.join('\n');
}
if (typeof module !== 'undefined') module.exports = { heroSvg };
