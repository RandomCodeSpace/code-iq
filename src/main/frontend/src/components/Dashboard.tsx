import { useNavigate } from 'react-router-dom';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import { isComputedStats } from '@/types/api';
import StatsCards from './StatsCards';
import FrameworkBadges from './FrameworkBadges';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import {
  Shield, Database, Layers, Code2,
  BarChart3, RefreshCw, AlertCircle,
  Cpu, Network,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/* ------------------------------------------------------------------ */
/* Clickable stat row — navigates to explorer filtered by kind        */
/* ------------------------------------------------------------------ */
interface StatRowProps {
  label: string;
  value: number;
  total: number;
  colorClass: string;
  href?: string;
}

function StatRow({ label, value, total, colorClass, href }: StatRowProps) {
  const navigate = useNavigate();
  const pct = total > 0 ? (value / total) * 100 : 0;

  return (
    <div
      className={cn(
        'group',
        href && 'cursor-pointer',
      )}
      onClick={href ? () => navigate(href) : undefined}
      role={href ? 'button' : undefined}
      tabIndex={href ? 0 : undefined}
      onKeyDown={href ? (e) => { if (e.key === 'Enter' || e.key === ' ') navigate(href); } : undefined}
    >
      <div className="flex items-center justify-between text-sm mb-1">
        <span className={cn(
          'text-muted-foreground text-xs transition-colors',
          href && 'group-hover:text-foreground',
        )}>
          {label}
        </span>
        <span className="text-xs font-mono text-muted-foreground/70 tabular-nums">
          {value.toLocaleString()}
        </span>
      </div>
      <Progress
        value={pct}
        indicatorClassName={cn('transition-all duration-700', colorClass)}
        className="h-1"
      />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Section card wrapper                                                 */
/* ------------------------------------------------------------------ */
interface SectionCardProps {
  icon: React.ComponentType<{ className?: string }>;
  iconClass: string;
  title: string;
  children: React.ReactNode;
  className?: string;
}

function SectionCard({ icon: Icon, iconClass, title, children, className }: SectionCardProps) {
  return (
    <Card className={cn('border-border/60 bg-card/80 backdrop-blur-sm', className)}>
      <CardHeader className="pb-3 px-5 pt-4">
        <CardTitle className="flex items-center gap-2 text-xs font-medium text-muted-foreground uppercase tracking-widest">
          <Icon className={cn('w-3.5 h-3.5', iconClass)} />
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent className="px-5 pb-4 pt-0">
        {children}
      </CardContent>
    </Card>
  );
}

/* ------------------------------------------------------------------ */
/* Clickable connection metric                                          */
/* ------------------------------------------------------------------ */
interface ConnectionMetricProps {
  label: string;
  value: number;
  colorClass: string;
  href?: string;
  sub?: Record<string, number>;
}

function ConnectionMetric({ label, value, colorClass, href, sub }: ConnectionMetricProps) {
  const navigate = useNavigate();
  return (
    <div
      className={cn(
        'rounded-lg border border-border/40 p-3 space-y-1',
        href && 'cursor-pointer hover:border-border hover:bg-accent/30 transition-all duration-150',
      )}
      onClick={href ? () => navigate(href) : undefined}
      role={href ? 'button' : undefined}
      tabIndex={href ? 0 : undefined}
      onKeyDown={href ? (e) => { if (e.key === 'Enter' || e.key === ' ') navigate(href); } : undefined}
    >
      <p className="text-[10px] text-muted-foreground uppercase tracking-widest">{label}</p>
      <p className={cn('text-xl font-bold tabular-nums bg-gradient-to-r bg-clip-text text-transparent', colorClass)}>
        {value.toLocaleString()}
      </p>
      {sub && Object.keys(sub).length > 0 && (
        <div className="space-y-0.5 pt-1 border-t border-border/30">
          {Object.entries(sub)
            .sort(([, a], [, b]) => b - a)
            .map(([k, v]) => (
              <div key={k} className="flex justify-between text-[10px]">
                <span className="text-muted-foreground/60 font-mono uppercase">{k}</span>
                <span className="text-muted-foreground/80 font-mono tabular-nums">{v.toLocaleString()}</span>
              </div>
            ))}
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Main Dashboard                                                       */
/* ------------------------------------------------------------------ */
export default function Dashboard() {
  const { data: stats, loading, error, refetch } = useApi(() => api.getStats(), []);
  const { data: kinds } = useApi(() => api.getKinds(), []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="flex flex-col items-center gap-3">
          <div className="w-7 h-7 border-2 border-primary border-t-transparent rounded-full animate-spin" />
          <p className="text-muted-foreground text-xs">Loading analysis data…</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-96">
        <Card className="p-8 max-w-sm text-center space-y-4 border-border/60">
          <AlertCircle className="w-10 h-10 text-amber-400 mx-auto" />
          <div>
            <h2 className="text-sm font-semibold">No Analysis Data</h2>
            <p className="text-xs text-muted-foreground mt-1">
              Run an analysis first, or check that the server is connected to an analyzed codebase.
            </p>
          </div>
          <Button variant="outline" size="sm" onClick={refetch} className="gap-2">
            <RefreshCw className="w-3.5 h-3.5" />
            Retry
          </Button>
        </Card>
      </div>
    );
  }

  if (!stats) return null;

  // --- Extract data from whichever API format we received ---
  let totalNodes = 0;
  let totalEdges = 0;
  let totalFiles = 0;
  let languages: Record<string, number> = {};
  let frameworks: Record<string, number> = {};
  let infra: { databases: Record<string, number>; messaging: Record<string, number>; cloud: Record<string, number> } =
    { databases: {}, messaging: {}, cloud: {} };
  let connections: {
    rest: { total: number; by_method: Record<string, number> };
    grpc: number;
    websocket: number;
    producers: number;
    consumers: number;
  } = { rest: { total: 0, by_method: {} }, grpc: 0, websocket: 0, producers: 0, consumers: 0 };
  let auth: Record<string, number> = {};
  let architecture: Record<string, number> = {};
  let nodeKinds: Record<string, number> = {};
  let layers: Record<string, number> = {};

  if (isComputedStats(stats)) {
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
    totalNodes = stats.node_count || 0;
    totalEdges = stats.edge_count || 0;
    nodeKinds = stats.nodes_by_kind || {};
    layers = stats.nodes_by_layer || {};
  }

  // Prefer kinds endpoint data for nodeKinds (more reliable)
  if (kinds?.kinds) {
    nodeKinds = {};
    for (const k of kinds.kinds) {
      nodeKinds[k.kind] = k.count;
    }
  }

  const totalLangFiles = Object.values(languages).reduce((s, v) => s + v, 0);
  const totalArchItems = Object.values(architecture).reduce((s, v) => s + v, 0);
  const hasConnections =
    connections.rest.total > 0 || connections.grpc > 0 ||
    connections.websocket > 0 || connections.producers > 0 || connections.consumers > 0;
  const hasInfra =
    Object.keys(infra.databases || {}).length > 0 ||
    Object.keys(infra.messaging || {}).length > 0 ||
    Object.keys(infra.cloud || {}).length > 0;

  return (
    <div className="space-y-5 max-w-7xl mx-auto">

      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold bg-gradient-to-r from-primary to-purple-400 bg-clip-text text-transparent">
            Dashboard
          </h1>
          <p className="text-xs text-muted-foreground mt-0.5">Code knowledge graph overview</p>
        </div>
        <Button
          variant="ghost"
          size="icon"
          onClick={refetch}
          title="Refresh stats"
          className="text-muted-foreground hover:text-foreground"
        >
          <RefreshCw className="w-4 h-4" />
        </Button>
      </div>

      {/* Hero stat cards */}
      <StatsCards
        totalNodes={totalNodes}
        totalEdges={totalEdges}
        totalFiles={totalFiles}
        totalLanguages={Object.keys(languages).length}
      />

      {/* Frameworks row */}
      {Object.keys(frameworks).length > 0 && (
        <FrameworkBadges frameworks={frameworks} />
      )}

      {/* --- Primary grid: Node Kinds | Languages | Architecture --- */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">

        {/* Node Kinds */}
        {Object.keys(nodeKinds).length > 0 && (
          <SectionCard icon={BarChart3} iconClass="text-primary" title="Node Kinds">
            <div className="space-y-2.5 max-h-60 overflow-y-auto pr-1" role="list">
              {Object.entries(nodeKinds)
                .sort(([, a], [, b]) => b - a)
                .slice(0, 14)
                .map(([kind, count]) => (
                  <StatRow
                    key={kind}
                    label={kind}
                    value={count}
                    total={totalNodes}
                    colorClass="bg-gradient-to-r from-indigo-500 to-purple-500"
                    href={`/explorer/${encodeURIComponent(kind)}`}
                  />
                ))}
            </div>
          </SectionCard>
        )}

        {/* Languages */}
        {Object.keys(languages).length > 0 && (
          <SectionCard icon={Code2} iconClass="text-emerald-400" title="Languages">
            <div className="space-y-2.5 max-h-60 overflow-y-auto pr-1" role="list">
              {Object.entries(languages)
                .sort(([, a], [, b]) => b - a)
                .map(([lang, count]) => (
                  <StatRow
                    key={lang}
                    label={lang}
                    value={count}
                    total={totalLangFiles}
                    colorClass="bg-gradient-to-r from-emerald-500 to-cyan-500"
                  />
                ))}
            </div>
          </SectionCard>
        )}

        {/* Architecture */}
        {Object.keys(architecture).length > 0 && (
          <SectionCard icon={Cpu} iconClass="text-amber-400" title="Architecture">
            <div className="space-y-2.5" role="list">
              {Object.entries(architecture)
                .sort(([, a], [, b]) => b - a)
                .map(([item, count]) => (
                  <StatRow
                    key={item}
                    label={item.replace(/_/g, ' ')}
                    value={count}
                    total={totalArchItems}
                    colorClass="bg-gradient-to-r from-amber-500 to-orange-500"
                  />
                ))}
            </div>
          </SectionCard>
        )}

        {/* Architecture Layers (fallback from QueryService format) */}
        {Object.keys(layers).length > 0 && (
          <SectionCard icon={Layers} iconClass="text-amber-400" title="Architecture Layers">
            <div className="space-y-2.5" role="list">
              {(() => {
                const layerColors: Record<string, string> = {
                  frontend: 'bg-gradient-to-r from-cyan-500 to-blue-500',
                  backend: 'bg-gradient-to-r from-indigo-500 to-purple-500',
                  infra: 'bg-gradient-to-r from-amber-500 to-orange-500',
                  shared: 'bg-gradient-to-r from-emerald-500 to-green-500',
                  unknown: 'bg-gradient-to-r from-muted to-muted-foreground/30',
                };
                const total = Object.values(layers).reduce((s, v) => s + v, 0);
                return Object.entries(layers)
                  .sort(([, a], [, b]) => b - a)
                  .map(([layer, count]) => (
                    <StatRow
                      key={layer}
                      label={layer}
                      value={count}
                      total={total}
                      colorClass={layerColors[layer] || layerColors.unknown}
                    />
                  ));
              })()}
            </div>
          </SectionCard>
        )}
      </div>

      {/* --- Connections section --- */}
      {hasConnections && (
        <SectionCard icon={Network} iconClass="text-cyan-400" title="Connections">
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-5 gap-3">
            {connections.rest.total > 0 && (
              <ConnectionMetric
                label="REST Endpoints"
                value={connections.rest.total}
                colorClass="from-cyan-400 to-blue-400"
                href="/explorer/endpoint"
                sub={connections.rest.by_method}
              />
            )}
            {connections.grpc > 0 && (
              <ConnectionMetric
                label="gRPC Services"
                value={connections.grpc}
                colorClass="from-blue-400 to-indigo-400"
                href="/explorer/grpc_service"
              />
            )}
            {connections.websocket > 0 && (
              <ConnectionMetric
                label="WebSocket"
                value={connections.websocket}
                colorClass="from-indigo-400 to-violet-400"
              />
            )}
            {connections.producers > 0 && (
              <ConnectionMetric
                label="Producers"
                value={connections.producers}
                colorClass="from-emerald-400 to-green-400"
                href="/explorer/producer"
              />
            )}
            {connections.consumers > 0 && (
              <ConnectionMetric
                label="Consumers"
                value={connections.consumers}
                colorClass="from-amber-400 to-orange-400"
                href="/explorer/consumer"
              />
            )}
          </div>
        </SectionCard>
      )}

      {/* --- Infrastructure + Auth side by side --- */}
      {(hasInfra || Object.keys(auth).length > 0) && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">

          {/* Infrastructure */}
          {hasInfra && (
            <SectionCard icon={Database} iconClass="text-purple-400" title="Infrastructure">
              <div className="space-y-4">
                <InfraSubSection title="Databases" items={infra.databases} colorClass="bg-gradient-to-r from-purple-500 to-indigo-500" />
                <InfraSubSection title="Messaging" items={infra.messaging} colorClass="bg-gradient-to-r from-rose-500 to-pink-500" />
                <InfraSubSection title="Cloud" items={infra.cloud} colorClass="bg-gradient-to-r from-sky-500 to-cyan-500" />
              </div>
            </SectionCard>
          )}

          {/* Authentication */}
          {Object.keys(auth).length > 0 && (
            <SectionCard icon={Shield} iconClass="text-amber-400" title="Authentication">
              <div className="space-y-1" role="list">
                {(() => {
                  const authTotal = Object.values(auth).reduce((s, n) => s + n, 0);
                  return Object.entries(auth)
                  .sort(([, a], [, b]) => b - a)
                  .map(([k, v]) => (
                    <div key={k} className="flex items-center justify-between py-1" role="listitem">
                      <div className="flex items-center gap-2">
                        <span className="w-1.5 h-1.5 rounded-full bg-amber-400/70 shrink-0" />
                        <span className="text-xs text-muted-foreground capitalize">
                          {k.replace(/_/g, ' ')}
                        </span>
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="text-[10px] text-muted-foreground/50 tabular-nums font-mono">
                          {((v / authTotal) * 100).toFixed(0)}%
                        </span>
                        <Badge variant="muted" className="text-[10px] font-mono py-0 px-1.5">
                          {v.toLocaleString()}
                        </Badge>
                      </div>
                    </div>
                  ));
                })()}
              </div>
            </SectionCard>
          )}
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Infrastructure sub-section                                          */
/* ------------------------------------------------------------------ */
interface InfraSubSectionProps {
  title: string;
  items: Record<string, number> | undefined;
  colorClass: string;
}

function InfraSubSection({ title, items, colorClass }: InfraSubSectionProps) {
  const entries = Object.entries(items || {});
  if (entries.length === 0) return null;
  const total = entries.reduce((s, [, v]) => s + v, 0);

  return (
    <div>
      <p className="text-[10px] font-medium text-muted-foreground/60 uppercase tracking-widest mb-2">
        {title}
      </p>
      <div className="space-y-2">
        {entries
          .sort(([, a], [, b]) => b - a)
          .map(([k, v]) => (
            <StatRow
              key={k}
              label={k}
              value={v}
              total={total}
              colorClass={colorClass}
            />
          ))}
      </div>
    </div>
  );
}
