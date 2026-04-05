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
        ? [theme.darkAlgorithm, theme.compactAlgorithm]
        : [theme.defaultAlgorithm, theme.compactAlgorithm],
      token: {
        // Premium deep indigo palette
        colorPrimary: '#4f46e5',
        colorSuccess: '#10b981',
        colorWarning: '#f59e0b',
        colorError: '#ef4444',
        colorInfo: '#6366f1',
        // Typography
        fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
        fontSize: 13,
        // Refined spacing
        borderRadius: 6,
        wireframe: false,
        // Dark mode refinements
        ...(isDark ? {
          colorBgContainer: '#1a1a2e',
          colorBgElevated: '#1f1f38',
          colorBgLayout: '#0f0f1a',
          colorBorder: '#2d2d4a',
          colorBorderSecondary: '#252542',
        } : {
          colorBgContainer: '#ffffff',
          colorBgElevated: '#ffffff',
          colorBgLayout: '#f5f5f9',
        }),
      },
      components: {
        Table: {
          headerBg: isDark ? '#1f1f38' : '#fafafa',
          rowHoverBg: isDark ? '#252542' : '#f0f0ff',
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
