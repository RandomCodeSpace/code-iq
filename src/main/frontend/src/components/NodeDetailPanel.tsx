import { useEffect, useState } from 'react';
import Editor from '@monaco-editor/react';
import {
  FileCode2,
  MapPin,
  Layers,
  Tag,
  ArrowDownToLine,
  ArrowUpFromLine,
  ChevronDown,
  ChevronRight,
  AlertTriangle,
  Package,
  Hash,
} from 'lucide-react';
import { api } from '@/lib/api';
import { getKindColor } from '@/lib/graphConstants';
import type { NodeResponse, NeighborsResponse } from '@/types/api';
import { cn } from '@/lib/utils';
import { useTheme } from '@/hooks/useTheme';

// ─── Types ───────────────────────────────────────────────────────────────────

interface NodeDetailPanelProps {
  nodeId: string;
  onNavigateToNode: (nodeId: string) => void;
}

interface SectionProps {
  title: string;
  icon: React.ReactNode;
  children: React.ReactNode;
  defaultOpen?: boolean;
}

// ─── Collapsible Section ──────────────────────────────────────────────────────

function Section({ title, icon, children, defaultOpen = true }: SectionProps) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <div className="border border-border rounded-lg overflow-hidden">
      <button
        className="w-full flex items-center gap-2 px-3 py-2 bg-muted/30 hover:bg-muted/60 transition-colors text-left"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
      >
        <span className="text-muted-foreground">{icon}</span>
        <span className="text-xs font-semibold text-foreground uppercase tracking-wide flex-1">{title}</span>
        {open
          ? <ChevronDown className="w-3.5 h-3.5 text-muted-foreground" />
          : <ChevronRight className="w-3.5 h-3.5 text-muted-foreground" />
        }
      </button>
      {open && (
        <div className="px-3 py-2.5 space-y-1.5">
          {children}
        </div>
      )}
    </div>
  );
}

// ─── Kind Badge ───────────────────────────────────────────────────────────────

function KindBadge({ kind }: { kind: string }) {
  const color = getKindColor(kind);
  return (
    <span
      className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium border"
      style={{
        color,
        borderColor: `${color}40`,
        backgroundColor: `${color}15`,
      }}
    >
      <Tag className="w-2.5 h-2.5" />
      {kind}
    </span>
  );
}

// ─── Layer Badge ──────────────────────────────────────────────────────────────

const LAYER_COLORS: Record<string, string> = {
  frontend: '#06b6d4',
  backend:  '#8b5cf6',
  infra:    '#f59e0b',
  shared:   '#10b981',
  unknown:  '#6b7280',
};

function LayerBadge({ layer }: { layer: string }) {
  const color = LAYER_COLORS[layer] ?? LAYER_COLORS.unknown;
  return (
    <span
      className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium border"
      style={{ color, borderColor: `${color}40`, backgroundColor: `${color}15` }}
    >
      <Layers className="w-2.5 h-2.5" />
      {layer}
    </span>
  );
}

// ─── Dependency item ──────────────────────────────────────────────────────────

function DepItem({
  node,
  edgeKind,
  onNavigate,
}: {
  node: { id: string; label: string; kind: string; file_path?: string };
  edgeKind: string;
  onNavigate: (id: string) => void;
}) {
  return (
    <button
      className="w-full text-left flex items-start gap-2 px-2 py-1.5 rounded-md hover:bg-muted/60 transition-colors group"
      onClick={() => onNavigate(node.id)}
      title={`Navigate to ${node.label}`}
    >
      <div
        className="w-2 h-2 rounded-full mt-1 shrink-0"
        style={{ backgroundColor: getKindColor(node.kind) }}
      />
      <div className="min-w-0 flex-1">
        <span className="text-xs text-foreground group-hover:text-primary transition-colors truncate block">
          {node.label}
        </span>
        <span className="text-[10px] text-muted-foreground font-mono truncate block">
          {edgeKind} · {node.kind}
        </span>
      </div>
    </button>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function NodeDetailPanel({ nodeId, onNavigateToNode }: NodeDetailPanelProps) {
  const [node, setNode] = useState<NodeResponse | null>(null);
  const [neighbors, setNeighbors] = useState<NeighborsResponse | null>(null);
  const [sourceCode, setSourceCode] = useState<string | null>(null);
  const [sourceStartLine, setSourceStartLine] = useState<number>(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { theme } = useTheme();

  useEffect(() => {
    let isCancelled = false;

    const load = async () => {
      setLoading(true);
      setError(null);
      setNode(null);
      setNeighbors(null);
      setSourceCode(null);

      try {
        const [detail, nbrsRaw] = await Promise.all([
          api.getNodeDetail(nodeId),
          api.getNodeNeighbors(nodeId, 'both'),
        ]);
        if (isCancelled) return;
        const nbrs = nbrsRaw as unknown as NeighborsResponse;
        setNode(detail);
        setNeighbors(nbrs);

        if (detail.file_path) {
          const start = detail.line_start ? Math.max(1, detail.line_start - 2) : 1;
          const end = detail.line_end ? detail.line_end + 4 : start + 40;
          setSourceStartLine(start);
          const code = await api.readFile(detail.file_path, start, end);
          if (!isCancelled) setSourceCode(code);
        }
      } catch (err) {
        if (!isCancelled) setError(err instanceof Error ? err.message : 'Failed to load node details');
      } finally {
        if (!isCancelled) setLoading(false);
      }
    };

    load();
    return () => { isCancelled = true; };
  }, [nodeId]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="w-5 h-5 border-2 border-primary border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="text-destructive text-sm bg-destructive/10 border border-destructive/20 rounded-lg p-3">
        {error}
      </div>
    );
  }

  if (!node) return null;

  // Group incoming/outgoing by edge kind
  const incomingByKind = groupByEdgeKind(neighbors?.incoming ?? []);
  const outgoingByKind = groupByEdgeKind(neighbors?.outgoing ?? []);
  const inboundCount = neighbors?.incoming?.length ?? 0;
  const outboundCount = neighbors?.outgoing?.length ?? 0;

  // Detect language for Monaco from file extension
  const monacoLang = detectLanguage(node.file_path);
  const monacoTheme = theme === 'dark' ? 'vs-dark' : 'vs';

  return (
    <div className="space-y-2.5 pb-4">

      {/* ── Node Header ── */}
      <div className="space-y-1.5">
        <h3 className="text-sm font-semibold text-foreground leading-tight break-words">
          {node.label}
        </h3>
        <div className="flex flex-wrap gap-1.5">
          <KindBadge kind={node.kind} />
          {node.layer && <LayerBadge layer={node.layer} />}
        </div>
        {node.fqn && node.fqn !== node.label && (
          <code className="text-[10px] font-mono text-muted-foreground bg-muted/40 px-2 py-1 rounded block break-all leading-relaxed">
            {node.fqn}
          </code>
        )}
      </div>

      {/* ── Location ── */}
      {node.file_path && (
        <Section title="Location" icon={<MapPin className="w-3.5 h-3.5" />}>
          <button
            className="w-full text-left text-[11px] font-mono text-primary hover:underline break-all leading-relaxed"
            onClick={() => {/* source preview already shown below */}}
            title={node.file_path}
          >
            {node.file_path}
          </button>
          {node.line_start && (
            <div className="flex items-center gap-1 text-[11px] text-muted-foreground">
              <Hash className="w-3 h-3" />
              <span>
                Line {node.line_start}
                {node.line_end && node.line_end !== node.line_start ? `–${node.line_end}` : ''}
              </span>
            </div>
          )}
        </Section>
      )}

      {/* ── Classification ── */}
      {(node.annotations?.length || node.properties?.framework || node.module) && (
        <Section title="Classification" icon={<Layers className="w-3.5 h-3.5" />}>
          {node.module && (
            <div className="flex items-center gap-1.5 text-[11px]">
              <Package className="w-3 h-3 text-muted-foreground" />
              <span className="text-muted-foreground">Module:</span>
              <span className="text-foreground font-medium truncate">{node.module}</span>
            </div>
          )}
          {node.properties?.framework != null && (
            <div className="flex items-center gap-1.5 text-[11px]">
              <Tag className="w-3 h-3 text-muted-foreground" />
              <span className="text-muted-foreground">Framework:</span>
              <span className="text-foreground font-medium">{String(node.properties.framework)}</span>
            </div>
          )}
          {node.annotations && node.annotations.length > 0 && (
            <div className="flex flex-wrap gap-1 mt-0.5">
              {node.annotations.map(a => (
                <code
                  key={a}
                  className="text-[10px] font-mono text-amber-500 dark:text-amber-400 bg-amber-500/10 border border-amber-500/20 px-1.5 py-0.5 rounded"
                >
                  @{a}
                </code>
              ))}
            </div>
          )}
        </Section>
      )}

      {/* ── Statistics ── */}
      <Section title="Statistics" icon={<Hash className="w-3.5 h-3.5" />} defaultOpen={false}>
        <div className="grid grid-cols-2 gap-1.5">
          <Stat label="Inbound" value={inboundCount} icon={<ArrowDownToLine className="w-3 h-3" />} />
          <Stat label="Outbound" value={outboundCount} icon={<ArrowUpFromLine className="w-3 h-3" />} />
        </div>
      </Section>

      {/* ── Inbound Dependencies ── */}
      {inboundCount > 0 && (
        <Section
          title={`Inbound (${inboundCount})`}
          icon={<ArrowDownToLine className="w-3.5 h-3.5" />}
          defaultOpen={inboundCount <= 10}
        >
          {Object.entries(incomingByKind).map(([kind, items]) => (
            <div key={kind} className="space-y-0.5">
              <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wide px-1 pt-1">
                {kind}
              </p>
              {items.map(({ edge, node: n }) => (
                <DepItem
                  key={edge.id ?? n.id}
                  node={n}
                  edgeKind={edge.kind}
                  onNavigate={onNavigateToNode}
                />
              ))}
            </div>
          ))}
        </Section>
      )}

      {/* ── Outbound Dependencies ── */}
      {outboundCount > 0 && (
        <Section
          title={`Outbound (${outboundCount})`}
          icon={<ArrowUpFromLine className="w-3.5 h-3.5" />}
          defaultOpen={outboundCount <= 10}
        >
          {Object.entries(outgoingByKind).map(([kind, items]) => (
            <div key={kind} className="space-y-0.5">
              <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wide px-1 pt-1">
                {kind}
              </p>
              {items.map(({ edge, node: n }) => (
                <DepItem
                  key={edge.id ?? n.id}
                  node={n}
                  edgeKind={edge.kind}
                  onNavigate={onNavigateToNode}
                />
              ))}
            </div>
          ))}
        </Section>
      )}

      {/* ── Source Preview ── */}
      {sourceCode && (
        <Section title="Source" icon={<FileCode2 className="w-3.5 h-3.5" />}>
          <div className="rounded-md overflow-hidden border border-border -mx-1">
            <Editor
              height={Math.min(300, Math.max(120, sourceCode.split('\n').length * 18))}
              language={monacoLang}
              value={sourceCode}
              theme={monacoTheme}
              options={{
                readOnly: true,
                minimap: { enabled: false },
                scrollBeyondLastLine: false,
                lineNumbers: (n: number) => String(n + sourceStartLine - 1),
                fontSize: 11,
                folding: false,
                glyphMargin: false,
                lineDecorationsWidth: 0,
                lineNumbersMinChars: 4,
                renderLineHighlight: 'none',
                overviewRulerLanes: 0,
                hideCursorInOverviewRuler: true,
                scrollbar: { vertical: 'auto', horizontal: 'auto', verticalScrollbarSize: 6 },
                wordWrap: 'off',
              }}
              loading={<div className="h-20 flex items-center justify-center text-xs text-muted-foreground">Loading editor…</div>}
            />
          </div>
        </Section>
      )}

      {/* ── Warnings (placeholder) ── */}
      <Section
        title="Warnings"
        icon={<AlertTriangle className="w-3.5 h-3.5" />}
        defaultOpen={false}
      >
        <p className="text-[11px] text-muted-foreground italic">
          Static analysis warnings will appear here in a future release.
        </p>
      </Section>

    </div>
  );
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function Stat({ label, value, icon }: { label: string; value: number; icon: React.ReactNode }) {
  return (
    <div className={cn(
      'flex flex-col items-center justify-center gap-0.5 rounded-md py-2 px-1',
      'bg-muted/30 border border-border',
    )}>
      <div className="flex items-center gap-1 text-muted-foreground">{icon}</div>
      <span className="text-base font-bold text-foreground tabular-nums">{value}</span>
      <span className="text-[10px] text-muted-foreground">{label}</span>
    </div>
  );
}

function groupByEdgeKind(
  items: Array<{ edge: { id?: string; kind: string; source: string; target?: string }; node: NodeResponse }>,
): Record<string, typeof items> {
  const out: Record<string, typeof items> = {};
  for (const item of items) {
    const k = item.edge.kind ?? 'unknown';
    if (!out[k]) out[k] = [];
    out[k].push(item);
  }
  return out;
}

const EXT_LANG: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript', js: 'javascript', jsx: 'javascript',
  java: 'java', kt: 'kotlin', py: 'python', go: 'go', rs: 'rust',
  cs: 'csharp', cpp: 'cpp', c: 'c', h: 'cpp',
  yaml: 'yaml', yml: 'yaml', json: 'json', xml: 'xml',
  md: 'markdown', sh: 'shell', sql: 'sql', proto: 'protobuf',
  tf: 'hcl', dockerfile: 'dockerfile',
};

function detectLanguage(filePath?: string): string {
  if (!filePath) return 'plaintext';
  const lower = filePath.toLowerCase();
  const base = lower.split('/').pop() ?? '';
  if (base === 'dockerfile') return 'dockerfile';
  const ext = base.split('.').pop() ?? '';
  return EXT_LANG[ext] ?? 'plaintext';
}
