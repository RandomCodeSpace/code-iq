import { Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import Dashboard from './components/Dashboard';
import TopologyView from './components/TopologyView';
import ExplorerView from './components/ExplorerView';
import FlowView from './components/FlowView';
import McpConsole from './components/McpConsole';
import SwaggerView from './components/SwaggerView';

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/topology" element={<TopologyView />} />
        <Route path="/explorer" element={<ExplorerView />} />
        <Route path="/explorer/:kind" element={<ExplorerView />} />
        <Route path="/flow" element={<FlowView />} />
        <Route path="/console" element={<McpConsole />} />
        <Route path="/api-docs" element={<SwaggerView />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
