async page => {
  const base = 'http://127.0.0.1:8804/admin/analytics';
  const checks = [];
  const failures = [];
  const check = (ok, label) => { if (!ok) throw new Error(label); checks.push(label); };
  await page.route('**/*', route => route.continue());
  page.on('pageerror', error => failures.push(error.message));
  for (const [width, dark] of [[1440, false], [390, false], [320, true]]) {
    await page.setViewportSize({width, height: 900});
    await page.emulateMedia({colorScheme: dark ? 'dark' : 'light'});
    await page.goto(base);
    await page.locator('#updated').filter({hasText: '更新于'}).waitFor();
    const fit = await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
    check(fit, `${width}px page fits`);
    check(await page.locator('#errors tr').count() > 0, `${width}px failures visible`);
    if (width < 720) {
      check(await page.locator('#errors tr').first().locator('td').last().evaluate(cell => cell.getBoundingClientRect().right <= innerWidth), 'affected installs visible without horizontal scrolling');
    }
    check((await page.locator('#methodology').textContent()).includes('不是文件下载成功率'), 'metric limitation visible');
    await page.getByRole('button', {name: '今天', exact: true}).click();
    await page.waitForFunction(() => document.querySelector('#daily').children.length === 1);
    check(await page.locator('#daily .bar').count() === 1, 'calendar filter works');
    await page.getByRole('combobox', {name: '应用版本'}).selectOption('2.7.32');
    await page.waitForFunction(() => document.querySelector('#versions').textContent.includes('2.7.32') && !document.querySelector('#versions').textContent.includes('2.7.31'));
    check(true, 'version filter only shows selected version');
    await page.screenshot({path: `output/playwright/analytics-20261112/${width}-${dark ? 'dark' : 'light'}.png`, fullPage: true});
  }
  check(failures.length === 0, 'no browser JS errors');
  return {checks: checks.length, failures};
}
