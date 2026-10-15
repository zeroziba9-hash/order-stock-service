const { defineConfig } = require('@playwright/test');

const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:18083';
const target = new URL(baseURL);
const phase = process.env.E2E_AUTH_LIMITS === 'true' ? 'auth' : process.env.E2E_EXPIRATION === 'true' ? 'expiration' : 'normal';
if (process.env.E2E_DISPOSABLE !== 'true' || target.protocol !== 'http:'
    || !['127.0.0.1', 'localhost'].includes(target.hostname) || target.port !== '18083'
    || target.username || target.password || target.pathname !== '/' || target.search || target.hash) {
  throw new Error('Run only against the disposable loopback stack on port 18083 with E2E_DISPOSABLE=true.');
}

module.exports = defineConfig({
  testDir: './tests',
  testMatch: phase === 'auth' ? '**/auth-limits.spec.cjs' : phase === 'expiration' ? '**/expiration.spec.cjs' : ['**/storefront.spec.cjs','**/admin.spec.cjs','**/account.spec.cjs'],
  outputDir: phase === 'normal' ? 'test-results' : `${phase}-results`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30000,
  expect: { timeout: 10000 },
  forbidOnly: Boolean(process.env.CI),
  reporter: [['list'], ['html', { open: 'never', outputFolder: phase === 'normal' ? 'playwright-report' : `playwright-${phase}-report` }]],
  use: {
    baseURL,
    browserName: 'chromium',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    screenshot: 'only-on-failure',
    // Network traces can contain session cookies, CSRF tokens and disposable passwords.
    trace: 'off',
    video: 'off',
  },
  projects: [
    { name: 'desktop-chromium', use: { viewport: { width: 1280, height: 900 } } },
    { name: 'mobile-chromium', use: { viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true } },
  ],
});
