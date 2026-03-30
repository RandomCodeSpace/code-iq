import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { StatsResponse } from '@/types/api';
import { isComputedStats } from '@/types/api';
import StatsCards from './StatsCards';
import FrameworkBadges from './FrameworkBadges';
import {
  Shield, Database, Server, Layers, Globe, Code2,
  BarChart3, RefreshCw, AlertCircle, ArrowRightLeft
} from 'lucide-react';

export default function Dashboard() {
  const { data: stats, loading, error, refetch } = useApi(() => api.getStats(), []);
  const { data: kinds } = useApi(() => api.getKinds(), []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="flex flex-col items-center gap-4">
          <div className="w-8 h-8 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-surface-400 text-sm">Loading analysis data...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="glass-card p-8 max-w-md text-center space-y-4">
          <AlertCircle className="w-12 h-12 text-amber-400 mx-auto" />
          <h2 className="text-lg font-semibold text-surface-100">No Analysis Data</h2>
          <p className="text-surface-400 text-sm">
            Run an analysis first, or check that the server is connected to an analyzed codebase.
          </p>
          <button
            onClick={refetch}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-brand-500/10 text-brand-400 border border-brand-500/20 hover:bg-brand-500/20 transition-colors text-sm"
          >
            <RefreshCw className="w-4 h-4" />
            Retry
          </button>
        </div>
      </div>
    );
  }

  if (!stats) return null;

  // Extract data depending on which API format we got
  let totalNodes = 0;
  let totalEdges = 0;
  let totalFiles = 0;
  let languages: Record<string, number> = {};
  let frameworks: Record<string, number> = {};
  let infra: { databases: Record<string, number>; messaging: Record<string, number>; cloud: Record<string, number> } = { databases: {}, messaging: {}, cloud: {} };
  let connections: { rest: { total: number; by_method: Record<string, number> }; grpc: number; websocket: number; producers: number; consumers: number } = { rest: { total: 0, by_method: {} }, grpc: 0, websocket: 0, producers: 0, consumers: 0 };
  let auth: Record<string, number> = {};
  let architecture: Record<string, number> = {};
  let nodeKinds: Record<string, number> = {};
  let layers: Record<string, number> = {};

  if (isComputedStats(stats)) {
    // Primary format from StatsService.computeStats()
    totalNodes = stats.graph?.nodes || 0;
    totalEdges = stats.graph?.edges || 0;
    totalFiles = stats.graph?.files || 0;
    languages = stats.languages || {};
    frameworks = stats.frameworks || {};
    infra = stats.infra || { databases: {}, messaging: {}, cloud: {} };
    connections = stats.connections || { rest: { total: 0, by_method: {} }, grpc: 0, websocket: 0, producers: 0, consumers: 0 };
    auth = stats.auth || {};
    architecture = stats.architecture || {};
  } else {
    // Fallback format from QueryService.getStats()
    totalNodes = stats.node_count || 0;
    totalEdges = stats.edge_count || 0;
    nodeKinds = stats.nodes_by_kind || {};
    layers = stats.nodes_by_layer || {};
  }

  // Build nodeKinds from kinds endpoint if available (more reliable)
  if (kinds?.kinds) {
    nodeKinds = {};
    for (const k of kinds.kinds) {
      nodeKinds[k.kind] = k.count;
    }
  }

  // Build layers from architecture if we got computeStats format
  // (architecture contains classes, interfaces, etc. but not layers directly)
  // Layers come from the node data itself -- use kinds endpoint nodes or architecture data

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold gradient-text">Dashboard</h1>
          <p className="text-sm text-surface-400 mt-1">Code knowledge graph overview</p>
        </div>
        <button
          onClick={refetch}
          className="p-2 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/50 transition-colors"
          title="Refresh"
        >
          <RefreshCw className="w-4 h-4" />
        </button>
      </div>

      {/* Hero stats */}
      <StatsCards
        totalNodes={totalNodes}
        totalEdges={totalEdges}
        totalFiles={totalFiles}
        totalLanguages={Object.keys(languages).length}
      />

      {/* Frameworks */}
      {Object.keys(frameworks).length > 0 && <FrameworkBadges frameworks={frameworks} />}

      {/* Grid: Node Kinds + Languages + Architecture */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Node Kinds breakdown */}
        {Object.keys(nodeKinds).length > 0 && (
          <div className="glass-card p-5 animate-fade-in">
            <div className="flex items-center gap-2 mb-4">
              <BarChart3 className="w-4 h-4 text-brand-400" />
              <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Node Kinds</h3>
            </div>
            <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
              {Object.entries(nodeKinds)
                .sort(([, a], [, b]) => b - a)
                .slice(0, 12)
                .map(([kind, count]) => {
                  const pct = (count / (totalNodes || 1)) * 100;
                  return (
                    <div key={kind} className="group">
                      <div className="flex items-center justify-between text-sm mb-1">
                        <span className="text-surface-300 group-hover:text-surface-100 transition-colors">{kind}</span>
                        <span className="text-surface-500 font-mono text-xs">{count.toLocaleString()}</span>
                      </div>
                      <div className="h-1 bg-surface-800 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-gradient-to-r from-brand-500 to-purple-500 rounded-full transition-all duration-700"
                          style={{ width: `${Math.max(pct, 1)}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
            </div>
          </div>
        )}

        {/* Languages */}
        {Object.keys(languages).length > 0 && (
          <div className="glass-card p-5 animate-fade-in">
            <div className="flex items-center gap-2 mb-4">
              <Server className="w-4 h-4 text-emerald-400" />
              <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Languages</h3>
            </div>
            <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
              {Object.entries(languages)
                .sort(([, a], [, b]) => b - a)
                .map(([lang, count]) => {
                  const total = Object.values(languages).reduce((s, v) => s + v, 0);
                  const pct = (count / (total || 1)) * 100;
                  return (
                    <div key={lang} className="group">
                      <div className="flex items-center justify-between text-sm mb-1">
                        <span className="text-surface-300 group-hover:text-surface-100 transition-colors">{lang}</span>
                        <span className="text-surface-500 font-mono text-xs">{count.toLocaleString()}</span>
                      </div>
                      <div className="h-1 bg-surface-800 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-gradient-to-r from-emerald-500 to-cyan-500 rounded-full transition-all duration-700"
                          style={{ width: `${Math.max(pct, 1)}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
            </div>
          </div>
        )}

        {/* Architecture */}
        {Object.keys(architecture).length > 0 && (
          <div className="glass-card p-5 animate-fade-in">
            <div className="flex items-center gap-2 mb-4">
              <Code2 className="w-4 h-4 text-amber-400" />
              <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Architecture</h3>
            </div>
            <div className="space-y-3">
              {Object.entries(architecture)
                .sort(([, a], [, b]) => b - a)
                .map(([item, count]) => {
                  const total = Object.values(architecture).reduce((s, v) => s + v, 0);
                  const pct = (count / (total || 1)) * 100;
                  return (
                    <div key={item}>
                      <div className="flex items-center justify-between text-sm mb-1">
                        <span className="text-surface-300 capitalize">{item.replace(/_/g, ' ')}</span>
                        <span className="text-surface-500 font-mono text-xs">
                          {count.toLocaleString()} ({pct.toFixed(0)}%)
                        </span>
                      </div>
                      <div className="h-2 bg-surface-800 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-gradient-to-r from-amber-500 to-orange-500 rounded-full transition-all duration-700"
                          style={{ width: `${Math.max(pct, 1)}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
            </div>
          </div>
        )}

        {/* Layers fallback (from QueryService format) */}
        {Object.keys(layers).length > 0 && (
          <div className="glass-card p-5 animate-fade-in">
            <div className="flex items-center gap-2 mb-4">
              <Layers className="w-4 h-4 text-amber-400" />
              <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Architecture Layers</h3>
            </div>
            <div className="space-y-3">
              {Object.entries(layers)
                .sort(([, a], [, b]) => b - a)
                .map(([layer, count]) => {
                  const colors: Record<string, string> = {
                    frontend: 'from-cyan-500 to-blue-500',
                    backend: 'from-brand-500 to-purple-500',
                    infra: 'from-amber-500 to-orange-500',
                    shared: 'from-emerald-500 to-green-500',
                    unknown: 'from-surface-600 to-surface-500',
                  };
                  const total = Object.values(layers).reduce((s, v) => s + v, 0);
                  const pct = (count / (total || 1)) * 100;
                  return (
                    <div key={layer}>
                      <div className="flex items-center justify-between text-sm mb-1">
                        <span className="text-surface-300 capitalize">{layer}</span>
                        <span className="text-surface-500 font-mono text-xs">
                          {count.toLocaleString()} ({pct.toFixed(0)}%)
                        </span>
                      </div>
                      <div className="h-2 bg-surface-800 rounded-full overflow-hidden">
                        <div
                          className={`h-full bg-gradient-to-r ${colors[layer] || colors.unknown} rounded-full transition-all duration-700`}
                          style={{ width: `${Math.max(pct, 1)}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
            </div>
          </div>
        )}
      </div>

      {/* Connections section -- properly render nested structure */}
      {(connections.rest.total > 0 || connections.grpc > 0 || connections.websocket > 0 || connections.producers > 0 || connections.consumers > 0) && (
        <div className="glass-card p-5 animate-fade-in">
          <div className="flex items-center gap-2 mb-4">
            <ArrowRightLeft className="w-4 h-4 text-cyan-400" />
            <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Connections</h3>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {/* REST endpoints */}
            {connections.rest.total > 0 && (
              <div>
                <p className="text-xs text-surface-400 mb-2">REST Endpoints</p>
                <p className="text-2xl font-bold text-cyan-400">{connections.rest.total.toLocaleString()}</p>
                {Object.keys(connections.rest.by_method || {}).length > 0 && (
                  <div className="mt-2 space-y-1">
                    {Object.entries(connections.rest.by_method)
                      .sort(([, a], [, b]) => b - a)
                      .map(([method, count]) => (
                        <div key={method} className="flex items-center justify-between text-xs">
                          <span className="text-surface-400 font-mono">{method}</span>
                          <span className="text-surface-500 font-mono">{count.toLocaleString()}</span>
                        </div>
                      ))}
                  </div>
                )}
              </div>
            )}
            {/* gRPC */}
            {connections.grpc > 0 && (
              <div>
                <p className="text-xs text-surface-400 mb-2">gRPC Services</p>
                <p className="text-2xl font-bold text-blue-400">{connections.grpc.toLocaleString()}</p>
              </div>
            )}
            {/* WebSocket */}
            {connections.websocket > 0 && (
              <div>
                <p className="text-xs text-surface-400 mb-2">WebSocket</p>
                <p className="text-2xl font-bold text-indigo-400">{connections.websocket.toLocaleString()}</p>
              </div>
            )}
            {/* Producers */}
            {connections.producers > 0 && (
              <div>
                <p className="text-xs text-surface-400 mb-2">Producers</p>
                <p className="text-2xl font-bold text-emerald-400">{connections.producers.toLocaleString()}</p>
              </div>
            )}
            {/* Consumers */}
            {connections.consumers > 0 && (
              <div>
                <p className="text-xs text-surface-400 mb-2">Consumers</p>
                <p className="text-2xl font-bold text-amber-400">{connections.consumers.toLocaleString()}</p>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Infrastructure + Auth side by side */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Infrastructure -- nested with sub-categories */}
        {(Object.keys(infra.databases || {}).length > 0 ||
          Object.keys(infra.messaging || {}).length > 0 ||
          Object.keys(infra.cloud || {}).length > 0) && (
          <div className="glass-card p-5 animate-fade-in">
            <div className="flex items-center gap-2 mb-4">
              <Database className="w-4 h-4 text-purple-400" />
              <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Infrastructure</h3>
            </div>
            <div className="space-y-4">
              <InfraSubSection title="Databases" items={infra.databases} icon={Database} />
              <InfraSubSection title="Messaging" items={infra.messaging} icon={Globe} />
              <InfraSubSection title="Cloud" items={infra.cloud} icon={Server} />
            </div>
          </div>
        )}

        {/* Authentication */}
        {Object.keys(auth).length > 0 && (
          <div className="glass-card p-5 animate-fade-in">
            <div className="flex items-center gap-2 mb-4">
              <Shield className="w-4 h-4 text-amber-400" />
              <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Authentication</h3>
            </div>
            <div className="space-y-1.5">
              {Object.entries(auth)
                .sort(([, a], [, b]) => b - a)
                .map(([k, v]) => (
                  <div key={k} className="flex items-center justify-between text-sm">
                    <span className="text-surface-300 capitalize">{k.replace(/_/g, ' ')}</span>
                    <span className="text-surface-500 font-mono text-xs">{v.toLocaleString()}</span>
                  </div>
                ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/** Renders a sub-section of infrastructure (databases, messaging, cloud) */
function InfraSubSection({
  title,
  items,
  icon: Icon,
}: {
  title: string;
  items: Record<string, number> | undefined;
  icon: React.ComponentType<{ className?: string }>;
}) {
  const entries = Object.entries(items || {});
  if (entries.length === 0) return null;

  return (
    <div>
      <div className="flex items-center gap-1.5 mb-1.5">
        <Icon className="w-3 h-3 text-surface-500" />
        <p className="text-xs font-medium text-surface-400">{title}</p>
      </div>
      <div className="space-y-1 pl-4">
        {entries
          .sort(([, a], [, b]) => b - a)
          .map(([k, v]) => (
            <div key={k} className="flex items-center justify-between text-sm">
              <span className="text-surface-300">{k}</span>
              <span className="text-surface-500 font-mono text-xs">{v.toLocaleString()}</span>
            </div>
          ))}
      </div>
    </div>
  );
}
