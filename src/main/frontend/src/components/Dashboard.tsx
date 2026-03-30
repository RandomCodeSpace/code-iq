import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { StatsResponse, KindsResponse } from '@/types/api';
import StatsCards from './StatsCards';
import FrameworkBadges from './FrameworkBadges';
import {
  Shield, Plug, Database, Server, Layers,
  BarChart3, RefreshCw, AlertCircle
} from 'lucide-react';

export default function Dashboard() {
  const { data: stats, loading, error, refetch } = useApi(() => api.getStats(), []);
  const { data: kinds } = useApi(() => api.getKinds(), []);
  const { data: detailed } = useApi(() => api.getDetailedStats('all').catch(() => null), []);

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

  const nodeKinds = stats.node_kinds || {};
  const edgeKinds = stats.edge_kinds || {};
  const layers = stats.layers || {};
  const languages = stats.languages || {};
  const frameworks = stats.frameworks || [];
  const infraMap = stats.infrastructure || {};
  const authMap = stats.auth || {};
  const connMap = stats.connections || {};

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
        totalNodes={stats.total_nodes || 0}
        totalEdges={stats.total_edges || 0}
        totalFiles={stats.total_files || 0}
        totalLanguages={Object.keys(languages).length}
      />

      {/* Frameworks */}
      {frameworks.length > 0 && <FrameworkBadges frameworks={frameworks} />}

      {/* Grid: Architecture + Languages + Layers */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Architecture breakdown */}
        <div className="glass-card p-5 animate-fade-in">
          <div className="flex items-center gap-2 mb-4">
            <BarChart3 className="w-4 h-4 text-brand-400" />
            <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Node Kinds</h3>
          </div>
          <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
            {Object.entries(nodeKinds)
              .sort(([, a], [, b]) => (b as number) - (a as number))
              .slice(0, 12)
              .map(([kind, count]) => {
                const pct = ((count as number) / (stats.total_nodes || 1)) * 100;
                return (
                  <div key={kind} className="group">
                    <div className="flex items-center justify-between text-sm mb-1">
                      <span className="text-surface-300 group-hover:text-surface-100 transition-colors">{kind}</span>
                      <span className="text-surface-500 font-mono text-xs">{(count as number).toLocaleString()}</span>
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

        {/* Languages */}
        <div className="glass-card p-5 animate-fade-in">
          <div className="flex items-center gap-2 mb-4">
            <Server className="w-4 h-4 text-emerald-400" />
            <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Languages</h3>
          </div>
          <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
            {Object.entries(languages)
              .sort(([, a], [, b]) => (b as number) - (a as number))
              .map(([lang, count]) => {
                const total = Object.values(languages).reduce((s, v) => s + (v as number), 0);
                const pct = ((count as number) / (total || 1)) * 100;
                return (
                  <div key={lang} className="group">
                    <div className="flex items-center justify-between text-sm mb-1">
                      <span className="text-surface-300 group-hover:text-surface-100 transition-colors">{lang}</span>
                      <span className="text-surface-500 font-mono text-xs">{(count as number).toLocaleString()}</span>
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

        {/* Layers */}
        <div className="glass-card p-5 animate-fade-in">
          <div className="flex items-center gap-2 mb-4">
            <Layers className="w-4 h-4 text-amber-400" />
            <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">Architecture Layers</h3>
          </div>
          <div className="space-y-3">
            {Object.entries(layers)
              .sort(([, a], [, b]) => (b as number) - (a as number))
              .map(([layer, count]) => {
                const colors: Record<string, string> = {
                  frontend: 'from-cyan-500 to-blue-500',
                  backend: 'from-brand-500 to-purple-500',
                  infra: 'from-amber-500 to-orange-500',
                  shared: 'from-emerald-500 to-green-500',
                  unknown: 'from-surface-600 to-surface-500',
                };
                const total = Object.values(layers).reduce((s, v) => s + (v as number), 0);
                const pct = ((count as number) / (total || 1)) * 100;
                return (
                  <div key={layer}>
                    <div className="flex items-center justify-between text-sm mb-1">
                      <span className="text-surface-300 capitalize">{layer}</span>
                      <span className="text-surface-500 font-mono text-xs">
                        {(count as number).toLocaleString()} ({pct.toFixed(0)}%)
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
      </div>

      {/* Infrastructure + Auth + Connections */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {Object.keys(infraMap).length > 0 && (
          <SummaryCard title="Infrastructure" icon={Database} items={infraMap} color="purple" />
        )}
        {Object.keys(authMap).length > 0 && (
          <SummaryCard title="Authentication" icon={Shield} items={authMap} color="amber" />
        )}
        {Object.keys(connMap).length > 0 && (
          <SummaryCard title="Connections" icon={Plug} items={connMap} color="cyan" />
        )}
      </div>

      {/* Edge kinds summary */}
      {Object.keys(edgeKinds).length > 0 && (
        <div className="glass-card p-5 animate-fade-in">
          <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider mb-3">Edge Types</h3>
          <div className="flex flex-wrap gap-2">
            {Object.entries(edgeKinds)
              .sort(([, a], [, b]) => (b as number) - (a as number))
              .map(([kind, count]) => (
                <span
                  key={kind}
                  className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium
                             bg-surface-800/50 text-surface-300 border border-surface-700/30 hover:border-brand-500/30 transition-colors"
                >
                  {kind}
                  <span className="text-surface-500 font-mono">{(count as number).toLocaleString()}</span>
                </span>
              ))}
          </div>
        </div>
      )}
    </div>
  );
}

function SummaryCard({
  title,
  icon: Icon,
  items,
  color,
}: {
  title: string;
  icon: React.ComponentType<{ className?: string }>;
  items: Record<string, unknown>;
  color: string;
}) {
  const colorMap: Record<string, string> = {
    purple: 'text-purple-400',
    amber: 'text-amber-400',
    cyan: 'text-cyan-400',
  };

  return (
    <div className="glass-card p-5 animate-fade-in">
      <div className="flex items-center gap-2 mb-3">
        <Icon className={`w-4 h-4 ${colorMap[color] || 'text-brand-400'}`} />
        <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">{title}</h3>
      </div>
      <div className="space-y-1.5">
        {Object.entries(items).map(([k, v]) => (
          <div key={k} className="flex items-center justify-between text-sm">
            <span className="text-surface-300">{k}</span>
            <span className="text-surface-500 font-mono text-xs">{String(v)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
