const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport:{width:1080,height:1400} });
  await p.goto('file://' + __dirname + '/arte.html'); await p.evaluate(() => document.fonts.ready);
  for (const [id, name] of [['avatar','avatar-1080'],['p1','post-1-apresentacao'],['p2','post-2-unusual-stickers'],['p3','post-3-na-web']])
    await (await p.$('#' + id)).screenshot({ path: __dirname + '/' + name + '.png' });
  await b.close();
})();
