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
        Layout: {
          headerBg: isDark ? '#141414' : '#ffffff',
          bodyBg: isDark ? '#0a0a0a' : '#f7f7f8',
          siderBg: isDark ? '#141414' : '#ffffff',
        },
        Table: {
          headerBg: isDark ? '#1a1a1a' : '#fafafa',
          rowHoverBg: isDark ? '#1f1f1f' : '#f5f5ff',
        },
        Card: {
          paddingLG: 20,
          colorBgContainer: isDark ? '#141414' : '#ffffff',
        },
        Menu: {
          itemBg: 'transparent',
          darkItemBg: 'transparent',
          horizontalItemSelectedBg: isDark ? '#1a1a1a' : '#e6f4ff',
          horizontalItemSelectedColor: isDark ? '#60a5fa' : '#2563eb',
          itemColor: isDark ? '#a0a0a0' : '#595959',
          itemHoverColor: isDark ? '#e0e0e0' : '#1d1d1d',
          itemSelectedColor: isDark ? '#60a5fa' : '#2563eb',
        },
        Modal: {
          contentBg: isDark ? '#1a1a1a' : '#ffffff',
          headerBg: isDark ? '#1a1a1a' : '#ffffff',
        },
        Drawer: {
          colorBgElevated: isDark ? '#1a1a1a' : '#ffffff',
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
