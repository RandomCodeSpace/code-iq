import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider, theme, App as AntApp } from 'antd';
import AppRoot from './App';
import { ThemeProvider, useTheme } from './context/ThemeContext';
import './index.css';

function ThemedApp() {
  const { isDark } = useTheme();
  return (
    <ConfigProvider theme={{
      algorithm: isDark
        ? [theme.darkAlgorithm]
        : [theme.defaultAlgorithm],
      token: {
        // Clean blue primary — no purple
        colorPrimary: '#2563eb',
        colorSuccess: '#10b981',
        colorWarning: '#f59e0b',
        colorError: '#ef4444',
        colorInfo: '#3b82f6',
        // Typography
        fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
        fontSize: 14,
        // Refined spacing
        borderRadius: 6,
        wireframe: false,
        // Dark mode refinements
        ...(isDark ? {
          colorBgContainer: '#141414',
          colorBgElevated: '#1c1c1c',
          colorBgLayout: '#0a0a0a',
          colorBorder: '#303030',
          colorBorderSecondary: '#262626',
          colorText: '#e8e8e8',
          colorTextSecondary: '#a0a0a0',
        } : {
          colorBgContainer: '#ffffff',
          colorBgElevated: '#ffffff',
          colorBgLayout: '#f7f7f8',
        }),
      },
      components: {
        Table: {
          headerBg: isDark ? '#1c1c1c' : '#fafafa',
          rowHoverBg: isDark ? '#1f1f1f' : '#f5f5ff',
        },
        Card: {
          paddingLG: 20,
        },
        Menu: {
          itemBg: 'transparent',
          darkItemBg: 'transparent',
        },
      },
    }}>
      <AntApp>
        <BrowserRouter>
          <AppRoot />
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeProvider>
      <ThemedApp />
    </ThemeProvider>
  </React.StrictMode>
);
