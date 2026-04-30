/** @type {import('next').NextConfig} */
const nextConfig = {
  // Allow connections from Tailscale IPs (not just localhost)
  allowedDevOrigins: ['*'],
};

module.exports = nextConfig;
