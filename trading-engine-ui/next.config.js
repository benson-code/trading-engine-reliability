/** @type {import('next').NextConfig} */
const nextConfig = {
  // Allow connections from Tailscale IPs (not just localhost)
  allowedDevOrigins: ['*'],

  // The e2e suite builds into its own directory so a test run cannot clobber the
  // .next cache of a dev server someone has left running.
  distDir: process.env.NEXT_DIST_DIR || '.next',
};

module.exports = nextConfig;
