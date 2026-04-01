import { lazy, Suspense } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import Dashboard from './components/Dashboard';
import ExplorerView from './components/ExplorerView';
import McpConsole from './components/McpConsole';
import SwaggerView from './components/SwaggerView';

const CodeGraphView = lazy(() => import('./components/CodeGraphView'));

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/graph" element={
          <Suspense fallback={<div className="flex items-center justify-center h-full text-muted-foreground">Loading graph…</div>}>
            <CodeGraphView />
          </Suspense>
        } />
        <Route path="/explorer" element={<ExplorerView />} />
        <Route path="/explorer/:kind" element={<ExplorerView />} />
        <Route path="/console" element={<McpConsole />} />
        <Route path="/api-docs" element={<SwaggerView />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
