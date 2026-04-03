import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import './index.css';

interface ErrorBoundaryState {
  hasError: boolean;
  message: string;
}

class ErrorBoundary extends React.Component<React.PropsWithChildren, ErrorBoundaryState> {
  constructor(props: React.PropsWithChildren) {
    super(props);
    this.state = { hasError: false, message: '' };
  }

  static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
    return {
      hasError: true,
      message: error instanceof Error ? error.message : String(error),
    };
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', gap: '1rem', fontFamily: 'sans-serif', padding: '2rem' }}>
          <h1 style={{ fontSize: '1.5rem', fontWeight: 'bold' }}>Something went wrong</h1>
          <p style={{ color: '#666', maxWidth: '480px', textAlign: 'center' }}>{this.state.message}</p>
          <button
            onClick={() => window.location.reload()}
            style={{ padding: '0.5rem 1.5rem', borderRadius: '0.375rem', background: '#3b82f6', color: '#fff', border: 'none', cursor: 'pointer', fontSize: '0.875rem' }}
          >
            Reload
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ErrorBoundary>
  </React.StrictMode>,
);
