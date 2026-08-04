import { defineConfig, devices } from '@playwright/test';

/**
 * Dedicated port and build directory.
 *
 * A `next dev` left running on the default 3000 will happily answer the suite's requests,
 * and a test that silently measures somebody else's server reports numbers that mean
 * nothing. The Java side learned this as BUG-02 (test port collided with the production
 * server) and fixed it with findFreePort(); this is the same defect and the same fix.
 */
const PORT = 3100;
const DIST_DIR = '.next-e2e';

/**
 * Mobile-web test configuration.
 *
 * The default project emulates a Pixel 7 — viewport, device scale factor, touch and
 * user agent. That is Chromium pretending to be a phone, not a phone: it shares the
 * engine with Android Chrome, so engine-level behaviour (JS heap, main-thread cost)
 * carries over, while GPU compositing, thermal throttling and OS-level memory
 * pressure do not. Findings here are a fast signal, not a substitute for a device.
 */
export default defineConfig({
  testDir: './tests',

  // Endurance tests measure time and memory. Anything sharing the box distorts them.
  fullyParallel: false,
  workers: 1,

  // A retry that turns a red measurement green is hiding instability, not fixing it.
  retries: 0,

  forbidOnly: !!process.env.CI,
  timeout: 180_000,

  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    trace: 'retain-on-failure',
    video: 'retain-on-failure',

    // performance.memory is quantised by default; this makes it usable for assertions.
    launchOptions: { args: ['--enable-precise-memory-info'] },
  },

  projects: [
    { name: 'Pixel 7', use: { ...devices['Pixel 7'] } },
  ],

  webServer: {
    // Production build, not dev: dev-mode React adds per-render overhead that would sit
    // on top of the very cost this suite measures, and its HMR socket is a second
    // WebSocket the stub would have to disambiguate.
    command:
      `NEXT_DIST_DIR=${DIST_DIR} npm run build && ` +
      `NEXT_DIST_DIR=${DIST_DIR} npx next start -H 127.0.0.1 -p ${PORT}`,
    url: `http://127.0.0.1:${PORT}`,
    // Never reuse: a server we did not start is a server we cannot vouch for.
    reuseExistingServer: false,
    timeout: 300_000,
  },
});
