async page => {
  const base = 'https://community.yunget.yunl.us.ci';
  const checks = [], errors = [], external = [], resources = [];
  const assert = (condition, message) => {
    if (!condition) throw new Error(message);
    checks.push(message);
  };
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {
    if (!response.url().startsWith(base + '/')) external.push(new URL(response.url()).hostname);
    if (response.url().includes('/verification/') || response.url().includes('/api/verification/')) {
      resources.push({ path: new URL(response.url()).pathname, status: response.status() });
    }
  });
  await page.route('**/*', route => route.request().url().startsWith(base + '/') ? route.continue() : route.abort());
  await page.setViewportSize({ width: 320, height: 700 });
  await page.goto(base + '/', { waitUntil: 'networkidle' });
  await page.locator('#identity').click();
  await page.getByText('验证通过，可以提交', { exact: true }).waitFor({ timeout: 20000 });
  assert(await page.locator('altcha-widget').count() === 1, 'production loads one local verification widget');
  assert((await page.locator('altcha-widget input[type=hidden]').inputValue()).length > 100, 'the production widget generates a real proof');
  assert(await page.getByRole('button', { name: '保存昵称', exact: true }).isEnabled(), 'verification enables submission without developer login');
  await page.getByRole('button', { name: '重新验证', exact: true }).click();
  await page.getByText('验证通过，可以提交', { exact: true }).waitFor({ timeout: 20000 });
  assert(await page.locator('altcha-widget').count() === 1, 'retry leaves exactly one usable widget');
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '320px has no page overflow');
  await page.screenshot({ path: 'output/playwright/community-mobile/verification-production-light.png', fullPage: true });
  await page.evaluate(() => document.documentElement.dataset.theme = 'dark');
  await page.screenshot({ path: 'output/playwright/community-mobile/verification-production-dark.png', fullPage: true });
  await page.locator('#modal [data-dismiss]').first().click();
  const announcements = await page.request.get(base + '/api/announcements');
  assert(announcements.ok(), 'announcements remain available');
  const admin = await page.request.get(base + '/admin', { maxRedirects: 0 });
  assert(admin.status() === 302 && new URL(admin.headers().location).hostname === 'yuolui.cloudflareaccess.com', 'developer administration retains Access protection');
  assert(external.length === 0, 'verification works while third-party requests are blocked');
  assert(errors.length === 0, 'no uncaught application JavaScript errors');
  return { checks, count: checks.length, external, errors, resources };
}
