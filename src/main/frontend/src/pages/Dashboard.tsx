import { useState, useMemo, useCallback, useEffect, Fragment } from 'react';
import {
  Card, Spin, Alert, Modal, Drawer, Stat, Table, ScrollDiv, Space,
} from '@ossrandom/design-system';
import { Treemap } from '@ossrandom/design-system/charts';
import type { TreemapNode } from '@ossrandom/design-system/charts';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { StatsResponse, FileTreeResponse, FileTreeNode } from '@/types/api';
import { Icon } from '@/components/Icons';

// ── Stats helpers ──

function flattenToRecord(val: unknown): Record<string, number> {
  if (!val || typeof val !== 'object') return {};
  const result: Record<string, number> = {};
  for (const [k, v] of Object.entries(val as Record<string, unknown>)) {
    if (typeof v === 'number') result[k] = v;
    else if (typeof v === 'object' && v !== null && !Array.isArray(v))
      for (const [k2, v2] of Object.entries(v as Record<string, unknown>))
        if (typeof v2 === 'number') result[`${k}/${k2}`] = v2;
  }
  return result;
}

function sumValues(rec: Record<string, number>): number {
  return Object.values(rec).reduce((a, b) => a + b, 0);
}

function isComputedStats(s: StatsResponse): s is StatsResponse & {
  graph: { nodes: number; edges: number; files: number };
  languages: Record<string, number>; frameworks: Record<string, number>;
  connections?: unknown; auth?: unknown; architecture?: unknown;
} { return 'graph' in s; }

interface BreakdownRow { key: string; name: string; count: number }

function StatCard({ title, value, icon, detail, detailTitle }: {
  title: string; value: number | string; icon: React.ReactNode;
  detail?: Record<string, number>; detailTitle?: string;
}) {
  const [open, setOpen] = useState(false);
  const hasDetail = detail && Object.keys(detail).length > 0 && sumValues(detail) > 0;
  const tableData: BreakdownRow[] = hasDetail
    ? Object.entries(detail!).sort((a, b) => b[1] - a[1]).map(([name, count]) => ({ key: name, name, count }))
    : [];

  const cardEl = (
    <Card padding="sm" hoverable={!!hasDetail} style={{ height: '100%' }}>
      <Stat
        label={<Space size="xs" align="center">{icon}<span>{title}</span></Space>}
        value={value}
      />
    </Card>
  );

  return (
    <>
      {hasDetail ? (
        <div role="button" tabIndex={0}
          onClick={() => setOpen(true)}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen(true); } }}
          style={{ cursor: 'pointer', height: '100%' }}>
          {cardEl}
        </div>
      ) : cardEl}
      <Modal open={open} onClose={() => setOpen(false)} title={detailTitle ?? title} size="md">
        <ScrollDiv maxHeight={420} thin>
          <Table<BreakdownRow>
            rowKey="key"
            density="compact"
            data={tableData}
            columns={[
              { key: 'name', title: 'Name', dataKey: 'name' },
              { key: 'count', title: 'Count', dataKey: 'count', align: 'right',
                render: (v) => (typeof v === 'number' ? v.toLocaleString() : String(v)) },
            ]}
          />
        </ScrollDiv>
      </Modal>
    </>
  );
}

// ── Treemap data ──

const LANG_COLORS: Record<string, string> = {
  java: '#b07219', python: '#3572A5', typescript: '#3178c6', javascript: '#f1e05a',
  go: '#00ADD8', csharp: '#178600', rust: '#dea584', kotlin: '#A97BFF',
  yaml: '#cb171e', json: '#555', ruby: '#701516', scala: '#c22d40',
  cpp: '#f34b7d', shell: '#89e051', markdown: '#083fa1', html: '#e34c26',
  css: '#563d7c', sql: '#e38c00', proto: '#60a0b0', dockerfile: '#384d54',
  other: '#888',
};
const EXT_TO_LANG: Record<string, string> = {
  java: 'java', py: 'python', ts: 'typescript', tsx: 'typescript',
  js: 'javascript', jsx: 'javascript', go: 'go', cs: 'csharp',
  rs: 'rust', kt: 'kotlin', yml: 'yaml', yaml: 'yaml',
  json: 'json', rb: 'ruby', scala: 'scala', cpp: 'cpp',
  h: 'cpp', sh: 'shell', md: 'markdown', html: 'html',
  css: 'css', sql: 'sql', proto: 'proto',
};

function inferLang(name: string): string {
  return EXT_TO_LANG[name.split('.').pop()?.toLowerCase() ?? ''] ?? 'other';
}

function dominantLang(nodes: FileTreeNode[]): string {
  const counts: Record<string, number> = {};
  (function walk(items: FileTreeNode[]) {
    for (const item of items) {
      if (item.type === 'file') { const l = inferLang(item.name); counts[l] = (counts[l] ?? 0) + (item.nodeCount || 1); }
      if (item.children) walk(item.children);
    }
  })(nodes);
  return Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'other';
}

function collapseTree(nodes: FileTreeNode[]): FileTreeNode[] {
  return nodes.map(n => {
    if (n.type !== 'directory' || !n.children || n.children.length === 0) return n;
    let current = n, collapsedName = n.name;
    while (current.type === 'directory' && current.children && current.children.length === 1
      && current.children[0].type === 'directory' && current.children[0].children && current.children[0].children.length > 0) {
      current = current.children[0]; collapsedName += '/' + current.name;
    }
    return { ...current, name: collapsedName, children: collapseTree(current.children ?? []), nodeCount: n.nodeCount };
  });
}

function buildTreemapTree(
  nodes: FileTreeNode[],
  parentPath: string,
  pathMap: WeakMap<TreemapNode, string>,
): TreemapNode[] {
  // Sort children by name so the treemap layout is stable across page
  // loads regardless of API result ordering. d3-hierarchy's squarified
  // layout is deterministic in input order; so deterministic input ⇒
  // deterministic visual.
  const sorted = [...nodes].sort((a, b) => a.name.localeCompare(b.name));
  const out: TreemapNode[] = [];
  for (const n of sorted) {
    const fullPath = parentPath ? `${parentPath}/${n.name}` : n.name;
    if (n.nodeCount <= 0 && (!n.children || n.children.length === 0)) continue;
    if (n.type === 'directory' && n.children && n.children.length > 0) {
      const children = buildTreemapTree(n.children, fullPath, pathMap);
      if (children.length === 0) continue;
      const node: TreemapNode = {
        name: n.name,
        children,
        color: LANG_COLORS[dominantLang(n.children)] ?? '#666',
      };
      pathMap.set(node, fullPath);
      out.push(node);
    } else {
      const node: TreemapNode = {
        name: n.name,
        value: Math.max(n.nodeCount, 1),
        color: LANG_COLORS[inferLang(n.name)] ?? '#666',
      };
      pathMap.set(node, fullPath);
      out.push(node);
    }
  }
  return out;
}

function useViewportHeight(offset: number): number {
  const [h, setH] = useState(() => (typeof window === 'undefined' ? 600 : window.innerHeight - offset));
  useEffect(() => {
    const onResize = () => setH(window.innerHeight - offset);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [offset]);
  return Math.max(360, h);
}

// ── Main ──

export default function Dashboard() {
  // Stats
  const { data: stats, loading: statsLoading, error: statsError } = useApi(() => api.getStats(), []);
  const { data: kinds } = useApi(() => api.getKinds(), []);
  const { data: detailed } = useApi(() => api.getDetailedStats('all'), []);
  const computed = stats && isComputedStats(stats) ? stats : null;
  const qs = stats && !isComputedStats(stats) ? stats as { node_count: number; edge_count: number } : null;
  const nodeCount = computed?.graph?.nodes ?? qs?.node_count ?? 0;
  const edgeCount = computed?.graph?.edges ?? qs?.edge_count ?? 0;
  const fileCount = computed?.graph?.files ?? 0;
  const languages = flattenToRecord(computed?.languages);
  const frameworks = flattenToRecord(computed?.frameworks);
  const connections = flattenToRecord(computed?.connections);
  const auth = flattenToRecord(computed?.auth);
  const architecture = flattenToRecord(computed?.architecture);
  const nodeKindBreakdown: Record<string, number> = {};
  if (kinds?.kinds) for (const k of kinds.kinds) nodeKindBreakdown[k.kind] = k.count;
  const edgeKindBreakdown: Record<string, number> = {};
  if (computed) {
    const g = (computed as unknown as Record<string, unknown>).graph as Record<string, unknown> | undefined;
    if (g?.edges_by_kind && typeof g.edges_by_kind === 'object') Object.assign(edgeKindBreakdown, flattenToRecord(g.edges_by_kind));
  }
  if (detailed && typeof detailed === 'object' && Object.keys(edgeKindBreakdown).length === 0) {
    const g = (detailed as Record<string, unknown>).graph as Record<string, unknown> | undefined;
    if (g?.edges_by_kind && typeof g.edges_by_kind === 'object') Object.assign(edgeKindBreakdown, flattenToRecord(g.edges_by_kind));
  }

  // Treemap height — header(56) + content padding(32) + stats row(~110) +
  // breadcrumb(38) + gaps(24)
  const treemapHeight = useViewportHeight(56 + 32 + 110 + 38 + 24);

  // Treemap
  const { data: treeData, loading: treeLoading } = useApi<FileTreeResponse>(() => api.getFileTree(), []);
  const { treemapRoot, pathMap } = useMemo(() => {
    const map = new WeakMap<TreemapNode, string>();
    const children = buildTreemapTree(collapseTree(treeData?.tree ?? []), '', map);
    const root: TreemapNode = { name: 'root', children };
    return { treemapRoot: root, pathMap: map };
  }, [treeData]);

  // Drill state — names of the directories we've drilled into, in order.
  // Empty = full tree. Single-click on a directory pushes; clicking a
  // breadcrumb segment slices back to that depth.
  const [focusPath, setFocusPath] = useState<string[]>([]);

  // Reset focus when the underlying tree changes (e.g., re-fetch after enrich).
  useEffect(() => { setFocusPath([]); }, [treemapRoot]);

  // Walk treemapRoot along focusPath. Falls back to root if any segment is
  // missing (defensive — shouldn't happen since focusPath only ever holds
  // names we just clicked).
  const focusedRoot = useMemo(() => {
    let cur: TreemapNode = treemapRoot;
    for (const name of focusPath) {
      const child = cur.children?.find(c => c.name === name);
      if (!child) return treemapRoot;
      cur = child;
    }
    return cur;
  }, [treemapRoot, focusPath]);

  // File viewer
  const [fileDrawer, setFileDrawer] = useState<{ path: string; content: string } | null>(null);
  const [fileLoading, setFileLoading] = useState(false);
  const onTreemapNodeClick = useCallback(async (node: TreemapNode) => {
    // Directory — drill down one level.
    if (node.children && node.children.length > 0) {
      setFocusPath(prev => [...prev, node.name]);
      return;
    }
    // Leaf — open file in drawer.
    const filePath = pathMap.get(node);
    if (!filePath) return;
    setFileLoading(true);
    setFileDrawer({ path: filePath, content: '' });
    try { setFileDrawer({ path: filePath, content: await api.readFile(filePath) }); }
    catch { setFileDrawer({ path: filePath, content: '// Could not load file' }); }
    finally { setFileLoading(false); }
  }, [pathMap]);

  if (statsLoading || treeLoading) {
    return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="lg" /></div>;
  }
  if (statsError) {
    return <Alert severity="danger" title="Failed to load stats">{statsError}</Alert>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div className="codeiq-stats-grid">
        {[
          { t: 'Nodes', v: nodeCount.toLocaleString(), i: <Icon.Nodes />, d: nodeKindBreakdown, dt: 'Nodes by Kind' },
          { t: 'Edges', v: edgeCount.toLocaleString(), i: <Icon.Branches />, d: edgeKindBreakdown, dt: 'Edges by Kind' },
          { t: 'Files', v: fileCount.toLocaleString(), i: <Icon.File /> },
          { t: 'Languages', v: Object.keys(languages).length, i: <Icon.Code />, d: languages, dt: 'Languages' },
          { t: 'Frameworks', v: Object.keys(frameworks).length, i: <Icon.Build />, d: frameworks, dt: 'Frameworks' },
          { t: 'Connections', v: sumValues(connections), i: <Icon.Api />, d: connections, dt: 'Connections' },
          { t: 'Security', v: sumValues(auth), i: <Icon.Safety />, d: auth, dt: 'Auth Patterns' },
          { t: 'Code Structure', v: sumValues(architecture), i: <Icon.Appstore />, d: architecture, dt: 'Code Structure' },
        ].map(s => (
          <StatCard key={s.t} title={s.t} value={s.v} icon={s.i} detail={s.d} detailTitle={s.dt} />
        ))}
      </div>

      <div className="codeiq-breadcrumb" aria-label="Drill path">
        <button
          type="button"
          onClick={() => setFocusPath([])}
          disabled={focusPath.length === 0}
          aria-label="Back to root"
        >
          root
        </button>
        {focusPath.map((seg, i) => (
          <Fragment key={`${i}-${seg}`}>
            <span className="codeiq-breadcrumb-sep">/</span>
            <button
              type="button"
              onClick={() => setFocusPath(focusPath.slice(0, i + 1))}
              disabled={i === focusPath.length - 1}
            >
              {seg}
            </button>
          </Fragment>
        ))}
      </div>

      <div>
        {focusedRoot.children && focusedRoot.children.length > 0 ? (
          <Treemap
            // key forces a remount when the focused subtree changes — the
            // design-system Treemap caches layout on `data` identity, and a
            // remount is the simplest way to ensure a clean redraw.
            key={focusPath.join('/') || 'root'}
            data={focusedRoot}
            height={treemapHeight}
            engine="canvas"
            // One level at a time — each cell maps 1:1 to a direct child of
            // the focused subtree, so a click is always unambiguous (drill
            // into a directory or open a file). With deeper maxDepth the
            // user's click would pick the deepest leaf under the cursor,
            // breaking drill-down.
            maxDepth={1}
            padding={2}
            onNodeClick={onTreemapNodeClick}
            valueFormat={(v) => v.toLocaleString()}
          />
        ) : (
          <Card padding="lg" style={{ minHeight: 360 }}>
            <div style={{ textAlign: 'center', padding: 60, opacity: 0.7 }}>
              {treemapRoot.children && treemapRoot.children.length > 0
                ? 'This folder is empty. Use the breadcrumb above to go back.'
                : 'No file data. Run index + enrich first.'}
            </div>
          </Card>
        )}
      </div>

      <Drawer
        open={!!fileDrawer}
        onClose={() => setFileDrawer(null)}
        placement="right"
        width="60vw"
        title={fileDrawer?.path}
      >
        {fileLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
        ) : (
          <pre style={{ margin: 0, padding: 0, fontSize: 13, lineHeight: 1.5, fontFamily: 'var(--font-mono)' }}>
            {fileDrawer?.content}
          </pre>
        )}
      </Drawer>
    </div>
  );
}
