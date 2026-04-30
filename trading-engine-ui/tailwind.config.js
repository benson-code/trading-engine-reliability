/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{js,ts,jsx,tsx,mdx}'],
  theme: {
    extend: {
      colors: {
        'bg':      '#0B0E11',
        'card':    '#1E2026',
        'border':  '#2B2F36',
        'text':    '#EAECEF',
        'muted':   '#848E9C',
        'green':   '#02C076',
        'red':     '#F6465D',
        'yellow':  '#F0B90B',
        'blue':    '#1890FF',
      },
      fontFamily: {
        mono: ['JetBrains Mono', 'Consolas', 'ui-monospace', 'monospace'],
      },
    },
  },
  plugins: [],
};
