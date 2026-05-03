import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ToastRegion } from '@ossrandom/design-system';
import AppRoot from './App';
import { ThemeProvider } from './context/ThemeContext';
import '@ossrandom/design-system/styles.css';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeProvider>
      <BrowserRouter>
        <AppRoot />
      </BrowserRouter>
      <ToastRegion />
    </ThemeProvider>
  </React.StrictMode>
);
