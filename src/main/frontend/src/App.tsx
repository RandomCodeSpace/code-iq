import { Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import Dashboard from './components/Dashboard';
import CodeGraphView from './components/CodeGraphView';
import ExplorerView from './components/ExplorerView';
import McpConsole from './components/McpConsole';
import SwaggerView from './components/SwaggerView';

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/graph" element={<CodeGraphView />} />
        <Route path="/explorer" element={<ExplorerView />} />
        <Route path="/explorer/:kind" element={<ExplorerView />} />
        <Route path="/console" element={<McpConsole />} />
        <Route path="/api-docs" element={<SwaggerView />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
