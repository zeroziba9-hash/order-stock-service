const { defineConfig } = require('@playwright/test');

const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:18083';
const target = new URL(baseURL);
const phase = process.env.E2E_TOSS === 'true' ? 'toss' : process.env.E2E_AUTH_LIMITS === 'true' ? 'auth' : process.env.E2E_EXPIRATION === 'true' ? 'expiration' : 'normal';
const engine = process.env.E2E_ENGINE || 'chromium';
if (!['chromium','firefox','webkit'].includes(engine)) throw new Error('Unsupported browser engine');
if (process.env.E2E_DISPOSABLE !== 'true' || target.protocol !== 'http:'
    || !['127.0.0.1', 'localhost'].includes(target.hostname) || target.port !== '18083'
    || target.username || target.password || target.pathname !== '/' || target.search || target.hash) {
  throw new Error('Run only against the disposable loopback stack on port 18083 with E2E_DISPOSABLE=true.');
}

module.exports = defineConfig({
  testDir: './tests',
  testMatch: phase === 'toss' ? '**/toss.spec.cjs' : phase === 'auth' ? '**/auth-limits.spec.cjs' : phase === 'expiration' ? '**/expiration.spec.cjs' : ['**/accessibility.spec.cjs','**/storefront.spec.cjs','**/admin.spec.cjs','**/account.spec.cjs','**/expansion.spec.cjs','**/checkout.spec.cjs','**/product-detail.spec.cjs','**/media-recovery.spec.cjs','**/password-reset.spec.cjs'],
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
    browserName: engine,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    screenshot: 'only-on-failure',
    // Network traces can contain session cookies, CSRF tokens and disposable passwords.
    trace: 'off',
    video: 'off',
  },
  projects: [
    { name: `desktop-${engine}`, use: { viewport: { width: 1280, height: 900 } } },
    { name: `mobile-${engine}`, use: { viewport: { width: 375, height: 812 }, ...(engine==='firefox'?{}:{isMobile:true,hasTouch:true}) } },
  ],
});
