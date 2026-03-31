import type { Config } from 'tailwindcss';

/**
 * Tailwind config for the Thymeleaf explorer UI templates.
 * Separate from the React app config to preserve the original blue brand colors.
 */
export default {
  darkMode: 'class',
  content: [
    '../resources/templates/**/*.html',
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#eff6ff',
          100: '#dbeafe',
          200: '#bfdbfe',
          300: '#93c5fd',
          400: '#60a5fa',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8',
          800: '#1e40af',
          900: '#1e3a5f',
        },
        surface: {
          DEFAULT: '#f8fafc',
          dark: '#0f172a',
        },
        card: {
          DEFAULT: '#ffffff',
          dark: '#1e293b',
        },
        muted: {
          DEFAULT: '#64748b',
          dark: '#94a3b8',
        },
      },
      animation: {
        'fade-in': 'fadeIn 0.3s ease-out',
        'slide-up': 'slideUp 0.3s ease-out',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        slideUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
    },
  },
  plugins: [],
} satisfies Config;
