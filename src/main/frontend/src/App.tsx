import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import Dashboard from './pages/Dashboard';
import CodebaseMap from './pages/CodebaseMap';
import Explorer from './pages/Explorer';
import McpConsole from './pages/McpConsole';

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/map" element={<CodebaseMap />} />
        <Route path="/explorer" element={<Explorer />} />
        <Route path="/explorer/:kind" element={<Explorer />} />
        <Route path="/console" element={<McpConsole />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
