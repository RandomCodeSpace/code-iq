import { useState, useEffect, useRef, useCallback } from 'react';
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type G6GraphType = any;
import { api } from '@/lib/api';
import { getKindColor, getEdgeColor, KIND_COLORS } from '@/lib/graphConstants';
import type { TopologyResponse, EgoGraphResponse, NodesListResponse, NodeResponse } from '@/types/api';
import { useFileSelection } from '@/contexts/FileSelectionContext';
import { useRightPanel } from '@/components/Layout';
import NodeDetailPanel from '@/components/NodeDetailPanel';
import {
  Home,
  ChevronRight,
  ZoomIn,
  ZoomOut,
  Maximize2,
  Minimize2,
  Crosshair,
  LayoutDashboard,
  Filter,
  Loader2,
  AlertCircle,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

// ─── Types ───────────────────────────────────────────────────────────────────

type DrillLevel = 0 | 1 | 2;
type LayoutMode = 'force' | 'dagre' | 'radial' | 'circular';

interface BreadcrumbItem {
  label: string;
  level: DrillLevel;
  serviceId?: string;
  nodeId?: string;
}

interface ContextMenuState {
  x: number;
  y: number;
  nodeId: string;
  nodeLabel: string;
  nodeKind: string;
}

interface G6NodeDatum {
  id: string;
  data: {
    label: string;
    kind: string;
    filePath?: string;
    module?: string;
    layer?: string;
    nodeCount?: number;
  };
  style?: Record<string, unknown>;
  combo?: string;
  [key: string]: unknown;
}

interface G6EdgeDatum {
  id?: string;
  source: string;
  target: string;
  data?: { kind?: string };
  style?: Record<string, unknown>;
  [key: string]: unknown;
}

interface G6ComboDatum {
  id: string;
  style?: Record<string, unknown>;
  [key: string]: unknown;
}

interface G6Data {
  nodes: G6NodeDatum[];
  edges: G6EdgeDatum[];
  combos?: G6ComboDatum[];
}

// ─── Constants ───────────────────────────────────────────────────────────────

const LAYOUT_CONFIG: Record<LayoutMode, object> = {
  force: {
    type: 'd3-force',
    link: { distance: 120 },
    charge: { strength: -400 },
    collide: { radius: 28, strength: 0.8 },
  },
  dagre: {
    type: 'antv-dagre',
    rankdir: 'LR',
    nodesep: 40,
    ranksep: 80,
  },
  radial: {
    type: 'radial',
    unitRadius: 90,
    linkDistance: 130,
  },
  circular: {
    type: 'circular',
    radius: 200,
    ordering: 'degree',
  },
};

// ─── Helpers to build G6 data from API responses ─────────────────────────────

function topologyToG6Data(topo: TopologyResponse): G6Data {
  const nodes: G6NodeDatum[] = topo.services.map(svc => ({
    id: svc.name,
    data: {
      label: svc.name,
      kind: 'service',
      layer: svc.layer,
      nodeCount: svc.nodeCount,
    },
    style: {
      fill: getKindColor('service'),
      fillOpacity: 0.85,
      stroke: getKindColor('service'),
      strokeOpacity: 0.6,
      lineWidth: 1.5,
      size: Math.max(24, Math.min(56, 24 + (svc.nodeCount ?? 0) / 10)),
      labelText: svc.name,
      labelFontSize: 11,
      labelFontWeight: 500,
      labelFill: '#e2e8f0',
      labelPosition: 'bottom',
      cursor: 'pointer',
    },
  }));

  const edges: G6EdgeDatum[] = topo.dependencies.map((dep, i) => ({
    id: `edge-${i}`,
    source: dep.source,
    target: dep.target,
    data: { kind: dep.kind },
    style: {
      stroke: getEdgeColor(dep.kind ?? 'default'),
      lineWidth: 1.5,
      strokeOpacity: 0.6,
      endArrow: true,
    },
  }));

  return { nodes, edges };
}

function moduleNodesToG6Data(nodes: NodeResponse[], module: string): G6Data {
  const g6Nodes: G6NodeDatum[] = nodes.map(n => ({
    id: n.id,
    data: {
      label: n.label,
      kind: n.kind,
      filePath: n.file_path,
      module: n.module ?? module,
      layer: n.layer,
    },
    style: {
      fill: getKindColor(n.kind),
      fillOpacity: 0.8,
      stroke: getKindColor(n.kind),
      strokeOpacity: 0.5,
      lineWidth: 1.5,
      size: 22,
      labelText: n.label.length > 20 ? n.label.slice(0, 20) + '…' : n.label,
      labelFontSize: 10,
      labelFill: '#cbd5e1',
      labelPosition: 'bottom',
      cursor: 'pointer',
    },
  }));

  return { nodes: g6Nodes, edges: [] };
}

function egoToG6Data(ego: EgoGraphResponse): G6Data {
  const nodes: G6NodeDatum[] = ego.nodes.map(n => ({
    id: n.id,
    data: {
      label: n.label,
      kind: n.kind,
      filePath: n.file_path,
      module: n.module,
    },
    style: {
      fill: n.id === ego.center ? '#f59e0b' : getKindColor(n.kind),
      fillOpacity: n.id === ego.center ? 1 : 0.8,
      stroke: n.id === ego.center ? '#fbbf24' : getKindColor(n.kind),
      strokeOpacity: n.id === ego.center ? 1 : 0.5,
      lineWidth: n.id === ego.center ? 2.5 : 1.5,
      size: n.id === ego.center ? 30 : 20,
      labelText: n.label.length > 18 ? n.label.slice(0, 18) + '…' : n.label,
      labelFontSize: 10,
      labelFill: '#cbd5e1',
      labelPosition: 'bottom',
      cursor: 'pointer',
    },
  }));

  const edges: G6EdgeDatum[] = ego.edges.map((e, i) => ({
    id: e.id ?? `ego-edge-${i}`,
    source: e.source,
    target: e.target ?? ego.center,
    data: { kind: e.kind },
    style: {
      stroke: getEdgeColor(e.kind ?? 'default'),
      lineWidth: 1.5,
      strokeOpacity: 0.5,
      endArrow: true,
    },
  }));

  return { nodes, edges };
}

// ─── Context menu component ───────────────────────────────────────────────────

function ContextMenu({
  menu,
  onClose,
  onShowDetails,
  onFindCallers,
  onFindDeps,
  onDrillDown,
  level,
}: {
  menu: ContextMenuState;
  onClose: () => void;
  onShowDetails: (id: string) => void;
  onFindCallers: (id: string) => void;
  onFindDeps: (id: string) => void;
  onDrillDown: (id: string, label: string, kind: string) => void;
  level: DrillLevel;
}) {
  useEffect(() => {
    const handler = () => onClose();
    window.addEventListener('click', handler);
    return () => window.removeEventListener('click', handler);
  }, [onClose]);

  const items = [
    { label: 'Show details', action: () => onShowDetails(menu.nodeId) },
    { label: 'Find callers', action: () => onFindCallers(menu.nodeId) },
    { label: 'Find dependencies', action: () => onFindDeps(menu.nodeId) },
    ...(level < 2
      ? [{ label: 'Explore subgraph', action: () => onDrillDown(menu.nodeId, menu.nodeLabel, menu.nodeKind) }]
      : []),
  ];

  return (
    <div
      className="fixed z-50 py-1 rounded-lg border border-border bg-card shadow-xl text-sm min-w-[160px]"
      style={{ left: menu.x, top: menu.y }}
      onClick={e => e.stopPropagation()}
    >
      <div className="px-3 py-1.5 text-xs font-semibold text-muted-foreground border-b border-border mb-1 truncate max-w-[200px]">
        {menu.nodeLabel}
      </div>
      {items.map(item => (
        <button
          key={item.label}
          className="w-full text-left px-3 py-1.5 hover:bg-muted/60 text-foreground transition-colors"
          onClick={() => { item.action(); onClose(); }}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}

// ─── Node filter panel ────────────────────────────────────────────────────────

function NodeFilterPanel({
  kinds,
  hidden,
  onToggle,
}: {
  kinds: string[];
  hidden: Set<string>;
  onToggle: (kind: string) => void;
}) {
  return (
    <div className="absolute top-12 right-2 z-40 p-3 rounded-lg border border-border bg-card/95 shadow-xl backdrop-blur-sm max-h-72 overflow-y-auto">
      <p className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">Filter node types</p>
      <div className="space-y-1">
        {kinds.map(kind => (
          <label key={kind} className="flex items-center gap-2 cursor-pointer group">
            <div
              className={cn(
                'w-3 h-3 rounded-sm border transition-all shrink-0',
                !hidden.has(kind) ? 'opacity-100' : 'opacity-30',
              )}
              style={{ backgroundColor: getKindColor(kind), borderColor: getKindColor(kind) }}
            />
            <input
              type="checkbox"
              className="sr-only"
              checked={!hidden.has(kind)}
              onChange={() => onToggle(kind)}
            />
            <span className="text-xs text-foreground group-hover:text-primary transition-colors capitalize">
              {kind}
            </span>
          </label>
        ))}
      </div>
    </div>
  );
}

// ─── Legend ───────────────────────────────────────────────────────────────────

function GraphLegend({ kinds }: { kinds: string[] }) {
  if (kinds.length === 0) return null;
  const display = kinds.slice(0, 10);
  return (
    <div className="absolute bottom-3 left-3 z-30 px-3 py-2 rounded-lg border border-border bg-card/80 backdrop-blur-sm">
      <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wide mb-1.5">Legend</p>
      <div className="flex flex-wrap gap-x-3 gap-y-1 max-w-[220px]">
        {display.map(kind => (
          <div key={kind} className="flex items-center gap-1.5">
            <div className="w-2.5 h-2.5 rounded-full shrink-0" style={{ backgroundColor: getKindColor(kind) }} />
            <span className="text-[10px] text-muted-foreground capitalize">{kind}</span>
          </div>
        ))}
        {kinds.length > 10 && (
          <span className="text-[10px] text-muted-foreground">+{kinds.length - 10} more</span>
        )}
      </div>
    </div>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function CodeGraphView() {
  const containerRef = useRef<HTMLDivElement>(null);
  const minimapRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<G6GraphType | null>(null);
  const isInitializedRef = useRef(false);

  const [level, setLevel] = useState<DrillLevel>(0);
  const [breadcrumb, setBreadcrumb] = useState<BreadcrumbItem[]>([
    { label: 'Landscape', level: 0 },
  ]);

  const [layoutMode, setLayoutMode] = useState<LayoutMode>('force');
  const [hiddenKinds, setHiddenKinds] = useState<Set<string>>(new Set());
  const [availableKinds, setAvailableKinds] = useState<string[]>([]);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [showFilter, setShowFilter] = useState(false);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [nodeCount, setNodeCount] = useState(0);

  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);

  const { selectedPath, selectedType } = useFileSelection();
  const { openPanel, closePanel } = useRightPanel();

  // Stable refs so graph event handlers can call the latest callbacks without rebuild
  const openPanelRef = useRef(openPanel);
  const closePanelRef = useRef(closePanel);
  const loadLevel2Ref = useRef<(id: string) => void>(() => {});
  useEffect(() => { openPanelRef.current = openPanel; }, [openPanel]);
  useEffect(() => { closePanelRef.current = closePanel; }, [closePanel]);

  // Current drill-down ids
  const currentServiceRef = useRef<string | null>(null);
  const currentNodeIdRef = useRef<string | null>(null);

  // ── Build and render graph ──────────────────────────────────────────────────

  const renderG6 = useCallback(async (data: G6Data, layout: LayoutMode) => {
    if (!containerRef.current) return;

    const dataReceivedAt = performance.now();

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const { Graph } = await import('@antv/g6') as any;

    const container = containerRef.current;
    // Reset render state for this render pass
    container.removeAttribute('data-render-state');
    const { offsetWidth: width, offsetHeight: height } = container;

    // Destroy previous instance
    if (graphRef.current) {
      graphRef.current.destroy();
      graphRef.current = null;
    }

    // Filter hidden kinds
    const filteredNodes = data.nodes.filter(n => !hiddenKinds.has(n.data.kind));
    const filteredNodeIds = new Set(filteredNodes.map(n => n.id));
    const filteredEdges = data.edges.filter(
      e => filteredNodeIds.has(e.source) && filteredNodeIds.has(e.target),
    );

    const graph = new Graph({
      container,
      width,
      height,
      autoFit: 'view',
      animation: { duration: 400 },
      data: {
        nodes: filteredNodes,
        edges: filteredEdges,
        combos: data.combos,
      },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      layout: LAYOUT_CONFIG[layout] as any,
      behaviors: [
        'drag-canvas',
        'zoom-canvas',
        'drag-element',
        { type: 'click-select', multiple: false },
      ],
      plugins: [
        {
          type: 'minimap',
          key: 'minimap',
          container: minimapRef.current ?? undefined,
          size: [160, 100],
        },
        {
          type: 'tooltip',
          key: 'tooltip',
          trigger: 'hover',
          getContent: (_: unknown, items: G6NodeDatum[]) => {
            const item = items?.[0];
            if (!item) return '';
            const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
            const { label, kind, filePath, nodeCount: nc } = item.data;
            return `
              <div style="font-size:12px;padding:6px 8px;line-height:1.5;max-width:220px">
                <div style="font-weight:600;color:#e2e8f0;margin-bottom:2px">${esc(label ?? '')}</div>
                <div style="color:#94a3b8">Kind: <span style="color:${getKindColor(kind)}">${esc(kind ?? '')}</span></div>
                ${filePath ? `<div style="color:#94a3b8;font-size:10px;word-break:break-all">${esc(filePath)}</div>` : ''}
                ${nc !== undefined ? `<div style="color:#94a3b8">Nodes: ${nc}</div>` : ''}
                <div style="color:#64748b;font-size:10px;margin-top:2px">Double-click to drill down · Right-click for menu</div>
              </div>
            `;
          },
        },
      ],
      node: {
        state: {
          selected: {
            stroke: '#f59e0b',
            lineWidth: 2.5,
            shadowColor: '#f59e0b',
            shadowBlur: 12,
          },
          highlighted: {
            fillOpacity: 1,
            strokeOpacity: 1,
          },
          dimmed: {
            fillOpacity: 0.2,
            strokeOpacity: 0.15,
            labelOpacity: 0.2,
          },
        },
      },
      edge: {
        state: {
          highlighted: {
            strokeOpacity: 1,
            lineWidth: 2.5,
          },
          dimmed: {
            strokeOpacity: 0.1,
          },
        },
      },
      combo: {
        style: {
          fillOpacity: 0.05,
          strokeOpacity: 0.3,
          lineWidth: 1,
          labelFontSize: 11,
          labelFill: '#94a3b8',
        },
      },
    });
    graphRef.current = graph;

    // ── Node click: highlight dependencies + open right panel ─────────────
    graph.on('node:click', (evt: unknown) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const nodeId = (evt as any)?.target?.id as string | undefined;
      if (!nodeId) return;
      setContextMenu(null);

      // Highlight direct neighbors, dim others
      const allNodes = graph.getData().nodes as G6NodeDatum[];
      const allEdges = graph.getData().edges as G6EdgeDatum[];

      const connectedEdges = allEdges.filter(
        e => e.source === nodeId || e.target === nodeId,
      );
      const connectedIds = new Set<string>([nodeId]);
      connectedEdges.forEach(e => {
        connectedIds.add(e.source);
        connectedIds.add(e.target);
      });

      const stateMap: Record<string, string[]> = {};
      allNodes.forEach(n => {
        stateMap[n.id] = connectedIds.has(n.id) ? ['highlighted'] : ['dimmed'];
      });
      stateMap[nodeId] = ['selected'];
      allEdges.forEach((e, i) => {
        const eid = e.id ?? `edge-${i}`;
        stateMap[eid] = connectedEdges.includes(e) ? ['highlighted'] : ['dimmed'];
      });

      graph.setElementState(stateMap);

      // Open details panel for the selected node
      openPanelRef.current(
        <NodeDetailPanel
          nodeId={nodeId}
          onNavigateToNode={(targetId) => loadLevel2Ref.current(targetId)}
        />,
      );
    });

    // ── Canvas click: clear states + close panel ────────────────────────────
    graph.on('canvas:click', () => {
      setContextMenu(null);
      const stateMap: Record<string, string[]> = {};
      const allNodes = graph.getData().nodes as G6NodeDatum[];
      const allEdges = graph.getData().edges as G6EdgeDatum[];
      allNodes.forEach(n => { stateMap[n.id] = []; });
      allEdges.forEach((e, i) => { stateMap[e.id ?? `edge-${i}`] = []; });
      graph.setElementState(stateMap);
      closePanelRef.current();
    });

    // ── Double-click: drill down ────────────────────────────────────────────
    graph.on('node:dblclick', (evt: unknown) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const nodeId = (evt as any)?.target?.id as string | undefined;
      if (!nodeId) return;
      const nodeData = (graph.getData().nodes as G6NodeDatum[]).find(n => n.id === nodeId);
      if (nodeData) {
        handleDrillDown(nodeId, nodeData.data.label, nodeData.data.kind);
      }
    });

    // ── Right-click context menu ────────────────────────────────────────────
    graph.on('node:contextmenu', (evt: unknown) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const e = evt as any;
      if (e?.preventDefault) e.preventDefault();
      const nodeId = e?.target?.id as string | undefined;
      if (!nodeId) return;
      const nodeData = (graph.getData().nodes as G6NodeDatum[]).find(n => n.id === nodeId);
      setContextMenu({
        x: e?.client?.x ?? e?.clientX ?? 0,
        y: e?.client?.y ?? e?.clientY ?? 0,
        nodeId,
        nodeLabel: nodeData?.data.label ?? nodeId,
        nodeKind: nodeData?.data.kind ?? 'unknown',
      });
    });

    // Expose render timing + state for E2E tests
    graph.on('afterrender', () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (window as any).__graphRenderMs = performance.now() - dataReceivedAt;
      container.setAttribute('data-render-state', 'ready');
    });

    await graph.render();
  }, [hiddenKinds]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Data loaders ────────────────────────────────────────────────────────────

  const loadLevel0 = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const topo = await api.getTopology();
      const data = topologyToG6Data(topo);
      const kinds = [...new Set(data.nodes.map(n => n.data.kind))];
      setAvailableKinds(kinds);
      setNodeCount(data.nodes.length);
      await renderG6(data, layoutMode);
    } catch (e) {
      // Fallback: load kind nodes if topology not available
      try {
        const kindsRes = await api.getKinds();
        const topServices = kindsRes.kinds.filter(k =>
          ['service', 'module', 'class', 'component'].includes(k.kind),
        ).slice(0, 60);

        const nodesRes = await Promise.all(
          topServices.map(k => api.getNodesByKind(k.kind, 20, 0)),
        );
        const allNodes: NodeResponse[] = nodesRes.flatMap(r => r.nodes ?? []);
        const filteredNodes = selectedPath
          ? allNodes.filter(n => {
              if (!n.file_path) return false;
              return selectedType === 'directory'
                ? n.file_path.startsWith(selectedPath)
                : n.file_path === selectedPath;
            })
          : allNodes;

        const data = moduleNodesToG6Data(filteredNodes, 'root');
        const kinds = [...new Set(filteredNodes.map(n => n.kind))];
        setAvailableKinds(kinds);
        setNodeCount(filteredNodes.length);
        await renderG6(data, layoutMode);
      } catch (e2) {
        setError(e2 instanceof Error ? e2.message : 'Failed to load graph data');
      }
    } finally {
      setLoading(false);
    }
  }, [layoutMode, renderG6, selectedPath, selectedType]);

  const loadLevel1 = useCallback(async (serviceId: string) => {
    setLoading(true);
    setError(null);
    currentServiceRef.current = serviceId;
    try {
      const res: NodesListResponse = await api.getNodes(undefined, serviceId, 300, 0);
      const nodes = res.nodes ?? [];
      const filtered = selectedPath
        ? nodes.filter(n => {
            if (!n.file_path) return false;
            return selectedType === 'directory'
              ? n.file_path.startsWith(selectedPath)
              : n.file_path === selectedPath;
          })
        : nodes;
      const data = moduleNodesToG6Data(filtered, serviceId);
      const kinds = [...new Set(filtered.map(n => n.kind))];
      setAvailableKinds(kinds);
      setNodeCount(filtered.length);
      await renderG6(data, 'force');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load module nodes');
    } finally {
      setLoading(false);
    }
  }, [renderG6, selectedPath, selectedType]);

  const loadLevel2 = useCallback(async (nodeId: string) => {
    setLoading(true);
    setError(null);
    currentNodeIdRef.current = nodeId;
    try {
      const ego: EgoGraphResponse = await api.getEgoGraph(nodeId, 2);
      const data = egoToG6Data(ego);
      const kinds = [...new Set(data.nodes.map(n => n.data.kind))];
      setAvailableKinds(kinds);
      setNodeCount(data.nodes.length);
      await renderG6(data, 'dagre');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load component graph');
    } finally {
      setLoading(false);
    }
  }, [renderG6]);

  // Keep loadLevel2Ref in sync for stable use inside graph event handlers
  useEffect(() => { loadLevel2Ref.current = loadLevel2; }, [loadLevel2]);

  // ── Drill-down handler ──────────────────────────────────────────────────────

  const handleDrillDown = useCallback((nodeId: string, label: string, kind: string) => {
    if (level === 0) {
      setBreadcrumb(bc => [
        ...bc,
        { label, level: 1, serviceId: nodeId },
      ]);
      setLevel(1);
      setHiddenKinds(new Set());
      loadLevel1(nodeId);
    } else if (level === 1) {
      // Only drill into substantive nodes
      if (['class', 'function', 'component', 'service', 'controller', 'repository'].includes(kind)) {
        setBreadcrumb(bc => [
          ...bc,
          { label, level: 2, nodeId },
        ]);
        setLevel(2);
        setHiddenKinds(new Set());
        loadLevel2(nodeId);
      }
    }
  }, [level, loadLevel1, loadLevel2]);

  // ── Navigate breadcrumb ─────────────────────────────────────────────────────

  const navigateTo = useCallback((targetLevel: DrillLevel) => {
    if (targetLevel === level) return;
    setHiddenKinds(new Set());
    setContextMenu(null);

    if (targetLevel === 0) {
      setBreadcrumb([{ label: 'Landscape', level: 0 }]);
      setLevel(0);
      loadLevel0();
    } else if (targetLevel === 1) {
      const serviceItem = breadcrumb.find(b => b.level === 1);
      if (serviceItem?.serviceId) {
        setBreadcrumb(bc => bc.slice(0, 2));
        setLevel(1);
        loadLevel1(serviceItem.serviceId!);
      }
    }
  }, [level, breadcrumb, loadLevel0, loadLevel1]);

  // ── Initial load ────────────────────────────────────────────────────────────

  useEffect(() => {
    if (isInitializedRef.current) return;
    isInitializedRef.current = true;
    loadLevel0();

    return () => {
      if (graphRef.current) {
        graphRef.current.destroy();
        graphRef.current = null;
      }
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Re-load when file selection changes
  useEffect(() => {
    if (!isInitializedRef.current) return;
    if (level === 0) loadLevel0();
    else if (level === 1 && currentServiceRef.current) loadLevel1(currentServiceRef.current);
    else if (level === 2 && currentNodeIdRef.current) loadLevel2(currentNodeIdRef.current);
  }, [selectedPath]); // eslint-disable-line react-hooks/exhaustive-deps

  // Reload when layout changes
  useEffect(() => {
    if (!isInitializedRef.current || !graphRef.current) return;
    if (level === 0) loadLevel0();
    else if (level === 1 && currentServiceRef.current) loadLevel1(currentServiceRef.current);
    return () => {
      if (graphRef.current) {
        graphRef.current.destroy();
        graphRef.current = null;
      }
    };
  }, [layoutMode]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Controls ─────────────────────────────────────────────────────────────────

  const handleZoomIn = () => graphRef.current?.zoomBy(1.3);
  const handleZoomOut = () => graphRef.current?.zoomBy(0.77);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const handleFitView = () => (graphRef.current as any)?.fitView({ padding: [40, 40, 40, 40] });

  const handleToggleFilter = (kind: string) => {
    setHiddenKinds(prev => {
      const next = new Set(prev);
      if (next.has(kind)) next.delete(kind);
      else next.add(kind);
      return next;
    });
  };

  const handleFullscreen = () => {
    const el = containerRef.current?.closest('.graph-fullscreen-wrap') as HTMLElement | null;
    if (!el) return;
    if (!isFullscreen) {
      el.requestFullscreen?.();
      setIsFullscreen(true);
    } else {
      document.exitFullscreen?.();
      setIsFullscreen(false);
    }
  };

  // ── Context menu actions ────────────────────────────────────────────────────

  const handleShowDetails = (id: string) => {
    openPanel(
      <NodeDetailPanel
        nodeId={id}
        onNavigateToNode={(targetId) => loadLevel2(targetId)}
      />,
    );
  };

  const handleFindCallers = async (id: string) => {
    try {
      const res = await api.getCallers(id);
      const nodes = (res as { nodes?: NodeResponse[] }).nodes ?? [];
      if (nodes.length > 0) {
        const data = moduleNodesToG6Data(nodes, 'callers');
        setNodeCount(nodes.length);
        await renderG6(data, 'dagre');
      }
    } catch (err) { setError(err instanceof Error ? err.message : 'Failed to load callers'); }
  };

  const handleFindDeps = async (id: string) => {
    try {
      const res = await api.getDependencies(id);
      const nodes = (res as { nodes?: NodeResponse[] }).nodes ?? [];
      if (nodes.length > 0) {
        const data = moduleNodesToG6Data(nodes, 'dependencies');
        setNodeCount(nodes.length);
        await renderG6(data, 'dagre');
      }
    } catch (err) { setError(err instanceof Error ? err.message : 'Failed to load dependencies'); }
  };

  // ── Layout switcher labels ───────────────────────────────────────────────────

  const LAYOUT_LABELS: Record<LayoutMode, string> = {
    force: 'Force',
    dagre: 'Hierarchical',
    radial: 'Radial',
    circular: 'Circular',
  };

  const levelLabels: Record<DrillLevel, string> = {
    0: 'Landscape — service topology',
    1: 'Module — components within service',
    2: 'Component — ego subgraph',
  };

  return (
    <div className="flex flex-col h-full gap-3 graph-fullscreen-wrap">

      {/* ── Header ── */}
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div>
          <h1 className="text-2xl font-bold gradient-text">Code Graph</h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            {loading
              ? 'Loading…'
              : error
                ? 'Error loading graph'
                : `${nodeCount} nodes — ${levelLabels[level]}`}
          </p>
        </div>

        {/* Breadcrumb */}
        <nav className="flex items-center gap-1 text-sm" aria-label="Graph drill-down breadcrumb" data-testid="breadcrumb">
          {breadcrumb.map((crumb, i) => (
            <span key={i} className="flex items-center gap-1">
              {i > 0 && <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/50" />}
              <button
                onClick={() => navigateTo(crumb.level)}
                className={cn(
                  'flex items-center gap-1 px-2 py-1 rounded transition-colors text-xs',
                  i === breadcrumb.length - 1
                    ? 'text-primary font-semibold cursor-default'
                    : 'text-muted-foreground hover:text-foreground hover:bg-muted/60 cursor-pointer',
                )}
                disabled={i === breadcrumb.length - 1}
                aria-current={i === breadcrumb.length - 1 ? 'page' : undefined}
              >
                {i === 0 && <Home className="w-3 h-3" />}
                <span>{crumb.label}</span>
              </button>
            </span>
          ))}
        </nav>
      </div>

      {/* ── Graph canvas + toolbar ── */}
      <div className="relative flex-1 rounded-xl border border-border bg-card/40 overflow-hidden">

        {/* Canvas */}
        <div ref={containerRef} className="w-full h-full" data-testid="graph-container" />

        {/* Minimap */}
        <div
          ref={minimapRef}
          className="absolute bottom-3 right-3 z-30 rounded-lg border border-border bg-card/80 backdrop-blur-sm overflow-hidden"
          style={{ width: 160, height: 100 }}
          aria-label="Graph minimap"
          data-testid="graph-minimap"
        />

        {/* Legend */}
        <GraphLegend kinds={availableKinds.filter(k => !hiddenKinds.has(k))} />

        {/* Toolbar */}
        <div className="absolute top-3 right-3 z-40 flex flex-col gap-1.5" data-testid="graph-controls">
          {/* Layout selector */}
          <div className="flex gap-1 p-1 rounded-lg border border-border bg-card/90 backdrop-blur-sm">
            {(Object.keys(LAYOUT_LABELS) as LayoutMode[]).map(mode => (
              <button
                key={mode}
                onClick={() => setLayoutMode(mode)}
                title={LAYOUT_LABELS[mode]}
                className={cn(
                  'px-2 py-1 rounded text-[10px] font-medium transition-colors',
                  layoutMode === mode
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:text-foreground hover:bg-muted/60',
                )}
                aria-pressed={layoutMode === mode}
              >
                {LAYOUT_LABELS[mode]}
              </button>
            ))}
          </div>

          {/* Icon controls */}
          <div className="flex flex-col gap-1 p-1 rounded-lg border border-border bg-card/90 backdrop-blur-sm">
            <Button variant="ghost" size="icon" onClick={handleZoomIn} title="Zoom in" className="h-7 w-7">
              <ZoomIn className="w-3.5 h-3.5" />
            </Button>
            <Button variant="ghost" size="icon" onClick={handleZoomOut} title="Zoom out" className="h-7 w-7">
              <ZoomOut className="w-3.5 h-3.5" />
            </Button>
            <Button variant="ghost" size="icon" onClick={handleFitView} title="Fit to view" className="h-7 w-7">
              <Crosshair className="w-3.5 h-3.5" />
            </Button>
            <Button variant="ghost" size="icon" onClick={handleFullscreen} title={isFullscreen ? 'Exit fullscreen' : 'Fullscreen'} className="h-7 w-7">
              {isFullscreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setShowFilter(f => !f)}
              title="Filter node types"
              className={cn('h-7 w-7', showFilter && 'text-primary')}
              aria-pressed={showFilter}
            >
              <Filter className="w-3.5 h-3.5" />
            </Button>
          </div>

          {/* Layout icon reference */}
          <div className="flex flex-col gap-1 p-1 rounded-lg border border-border bg-card/90 backdrop-blur-sm">
            <Button variant="ghost" size="icon" title="Dashboard (Level 0)" onClick={() => navigateTo(0)} className="h-7 w-7">
              <LayoutDashboard className="w-3.5 h-3.5" />
            </Button>
          </div>
        </div>

        {/* Filter panel */}
        {showFilter && availableKinds.length > 0 && (
          <NodeFilterPanel
            kinds={availableKinds}
            hidden={hiddenKinds}
            onToggle={handleToggleFilter}
          />
        )}

        {/* Loading overlay */}
        {loading && (
          <div className="absolute inset-0 flex items-center justify-center bg-background/60 backdrop-blur-sm z-50">
            <div className="flex flex-col items-center gap-3">
              <Loader2 className="w-8 h-8 animate-spin text-primary" />
              <span className="text-sm text-muted-foreground">Loading graph…</span>
            </div>
          </div>
        )}

        {/* Error overlay */}
        {!loading && error && (
          <div className="absolute inset-0 flex items-center justify-center z-50">
            <div className="flex flex-col items-center gap-3 text-center max-w-sm">
              <AlertCircle className="w-10 h-10 text-destructive" />
              <p className="text-sm text-muted-foreground">{error}</p>
              <Button variant="outline" size="sm" onClick={loadLevel0}>Retry</Button>
            </div>
          </div>
        )}

        {/* Empty state */}
        {!loading && !error && nodeCount === 0 && (
          <div className="absolute inset-0 flex items-center justify-center z-50">
            <p className="text-sm text-muted-foreground">No nodes found for current view.</p>
          </div>
        )}
      </div>

      {/* ── Context menu ── */}
      {contextMenu && (
        <ContextMenu
          menu={contextMenu}
          onClose={() => setContextMenu(null)}
          onShowDetails={handleShowDetails}
          onFindCallers={handleFindCallers}
          onFindDeps={handleFindDeps}
          onDrillDown={handleDrillDown}
          level={level}
        />
      )}

      {/* ── Help text ── */}
      <p className="text-xs text-muted-foreground/60 text-center">
        Click node to highlight connections · Double-click to drill in · Right-click for actions · Scroll to zoom
      </p>
    </div>
  );
}

// Re-export KIND_COLORS for backwards compat with ExplorerView
export { KIND_COLORS };
