import { useState, useEffect, useRef, useCallback } from 'react';
import * as d3 from 'd3';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { KindEntry, NodeResponse, NodesListResponse } from '@/types/api';
import { ChevronRight, Home } from 'lucide-react';

// Reuse the kind-color mapping from ExplorerView for consistency
const KIND_COLORS: Record<string, string> = {
  class: '#8b5cf6',
  interface: '#06b6d4',
  method: '#10b981',
  endpoint: '#f59e0b',
  entity: '#f43f5e',
  module: '#7c3aed',
  function: '#14b8a6',
  database: '#eab308',
  config: '#94a3b8',
  config_key: '#64748b',
  test: '#22c55e',
  guard: '#ef4444',
  middleware: '#f97316',
  service: '#3b82f6',
  controller: '#6366f1',
  repository: '#ec4899',
  component: '#0ea5e9',
  route: '#84cc16',
  topic: '#a855f7',
  queue: '#fb923c',
  schema: '#78716c',
  field: '#a8a29e',
  enum: '#c084fc',
  annotation: '#67e8f9',
  type: '#818cf8',
  script: '#fbbf24',
  file: '#e2e8f0',
  package: '#475569',
  import: '#6b7280',
};

function getKindColor(kind: string): string {
  return KIND_COLORS[kind.toLowerCase()] ?? '#6366f1';
}

// ─── Top-level treemap: kinds sized by count ────────────────────────────────

interface KindTreemapProps {
  width: number;
  height: number;
  kinds: KindEntry[];
  onDrillDown: (kind: string) => void;
}

function KindTreemap({ width, height, kinds, onDrillDown }: KindTreemapProps) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!svgRef.current || width === 0 || height === 0 || kinds.length === 0) return;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    const root = d3.hierarchy<{ name: string; children: KindEntry[] }>({
      name: 'root',
      children: kinds,
    })
      .sum(d => (d as unknown as KindEntry).count ?? 0)
      .sort((a, b) => (b.value ?? 0) - (a.value ?? 0));

    d3.treemap<{ name: string; children: KindEntry[] }>()
      .size([width, height])
      .padding(3)
      .tile(d3.treemapResquarify)(root);

    const leaves = root.leaves() as unknown as d3.HierarchyRectangularNode<KindEntry>[];

    const g = svg.append('g');

    leaves.forEach(leaf => {
      const w = leaf.x1 - leaf.x0;
      const h = leaf.y1 - leaf.y0;
      if (w < 4 || h < 4) return;

      const cell = g.append('g')
        .attr('transform', `translate(${leaf.x0},${leaf.y0})`)
        .style('cursor', 'pointer')
        .on('click', () => onDrillDown(leaf.data.kind));

      const color = getKindColor(leaf.data.kind);

      cell.append('rect')
        .attr('width', w)
        .attr('height', h)
        .attr('rx', 4)
        .attr('fill', color)
        .attr('fill-opacity', 0.15)
        .attr('stroke', color)
        .attr('stroke-opacity', 0.5)
        .attr('stroke-width', 1)
        .on('mouseover', function () {
          d3.select(this).attr('fill-opacity', 0.3);
        })
        .on('mouseout', function () {
          d3.select(this).attr('fill-opacity', 0.15);
        });

      // Kind label
      if (w > 40 && h > 28) {
        cell.append('text')
          .attr('x', 6)
          .attr('y', 16)
          .attr('font-size', Math.min(13, w / 8))
          .attr('font-weight', '600')
          .attr('fill', color)
          .attr('pointer-events', 'none')
          .text(leaf.data.kind.toUpperCase());
      }

      // Count
      if (w > 30 && h > 44) {
        cell.append('text')
          .attr('x', 6)
          .attr('y', 32)
          .attr('font-size', Math.min(11, w / 10))
          .attr('fill', '#94a3b8')
          .attr('pointer-events', 'none')
          .text(`${leaf.data.count} nodes`);
      }

      // Tooltip title element
      cell.append('title')
        .text(`${leaf.data.kind}: ${leaf.data.count} nodes\nClick to explore`);
    });
  }, [width, height, kinds, onDrillDown]);

  return <svg ref={svgRef} width={width} height={height} />;
}

// ─── Drill-down treemap: nodes of a specific kind ───────────────────────────

interface NodeTreemapProps {
  width: number;
  height: number;
  nodes: NodeResponse[];
  kind: string;
}

function NodeTreemap({ width, height, nodes, kind }: NodeTreemapProps) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!svgRef.current || width === 0 || height === 0 || nodes.length === 0) return;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    // Size each node tile by its edge count if available; otherwise equal weight
    const root = d3.hierarchy<{ name: string; children: NodeResponse[] }>({
      name: 'root',
      children: nodes,
    })
      .sum(() => 1)
      .sort((a, b) => (b.value ?? 0) - (a.value ?? 0));

    d3.treemap<{ name: string; children: NodeResponse[] }>()
      .size([width, height])
      .padding(2)
      .tile(d3.treemapResquarify)(root);

    const leaves = root.leaves() as unknown as d3.HierarchyRectangularNode<NodeResponse>[];
    const color = getKindColor(kind);

    const g = svg.append('g');

    leaves.forEach(leaf => {
      const w = leaf.x1 - leaf.x0;
      const h = leaf.y1 - leaf.y0;
      if (w < 2 || h < 2) return;

      const cell = g.append('g')
        .attr('transform', `translate(${leaf.x0},${leaf.y0})`);

      cell.append('rect')
        .attr('width', w)
        .attr('height', h)
        .attr('rx', 3)
        .attr('fill', color)
        .attr('fill-opacity', 0.12)
        .attr('stroke', color)
        .attr('stroke-opacity', 0.4)
        .attr('stroke-width', 1)
        .on('mouseover', function () {
          d3.select(this).attr('fill-opacity', 0.28);
        })
        .on('mouseout', function () {
          d3.select(this).attr('fill-opacity', 0.12);
        });

      if (w > 30 && h > 18) {
        const label = leaf.data.label || leaf.data.id;
        cell.append('text')
          .attr('x', 4)
          .attr('y', 13)
          .attr('font-size', Math.min(11, w / 7))
          .attr('fill', color)
          .attr('font-weight', '500')
          .attr('pointer-events', 'none')
          .text(label.length > Math.floor(w / 7) ? label.slice(0, Math.floor(w / 7)) + '…' : label);
      }

      const tooltipText = [
        leaf.data.label,
        leaf.data.file_path ? `File: ${leaf.data.file_path}` : null,
        leaf.data.module ? `Module: ${leaf.data.module}` : null,
      ].filter(Boolean).join('\n');

      cell.append('title').text(tooltipText);
    });
  }, [width, height, nodes, kind]);

  return <svg ref={svgRef} width={width} height={height} />;
}

// ─── Main view ───────────────────────────────────────────────────────────────

export default function CodeGraphView() {
  const containerRef = useRef<HTMLDivElement>(null);
  const [dimensions, setDimensions] = useState({ width: 0, height: 0 });
  const [selectedKind, setSelectedKind] = useState<string | null>(null);
  const [drillNodes, setDrillNodes] = useState<NodeResponse[]>([]);
  const [drillTotal, setDrillTotal] = useState(0);
  const [drillLoading, setDrillLoading] = useState(false);

  const { data: kindsData, loading: kindsLoading } = useApi(() => api.getKinds(), []);

  // Observe container size
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const observer = new ResizeObserver(entries => {
      const entry = entries[0];
      if (entry) {
        setDimensions({
          width: entry.contentRect.width,
          height: entry.contentRect.height,
        });
      }
    });
    observer.observe(el);

    // Initial size
    setDimensions({ width: el.clientWidth, height: el.clientHeight });

    return () => observer.disconnect();
  }, []);

  const handleDrillDown = useCallback(async (kind: string) => {
    setSelectedKind(kind);
    setDrillLoading(true);
    setDrillNodes([]);
    setDrillTotal(0);
    try {
      const result: NodesListResponse = await api.getNodesByKind(kind, 200, 0);
      setDrillNodes(result.nodes ?? []);
      setDrillTotal(result.total ?? result.count ?? (result.nodes ?? []).length);
    } catch {
      setDrillNodes([]);
      setDrillTotal(0);
    } finally {
      setDrillLoading(false);
    }
  }, []);

  const handleDrillUp = useCallback(() => {
    setSelectedKind(null);
    setDrillNodes([]);
    setDrillTotal(0);
  }, []);

  const kinds: KindEntry[] = kindsData?.kinds ?? [];

  return (
    <div className="flex flex-col h-full space-y-3">
      {/* Header + breadcrumb */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold gradient-text">Code Graph</h1>
          <p className="text-sm text-surface-400 mt-0.5">
            {selectedKind
              ? drillTotal > drillNodes.length
                ? `Showing ${drillNodes.length} of ${drillTotal} "${selectedKind}" nodes`
                : `${drillNodes.length} nodes of kind "${selectedKind}"`
              : `${kinds.length} node kinds — click a tile to explore`}
          </p>
        </div>

        {/* Breadcrumb */}
        <nav className="flex items-center gap-1 text-sm">
          <button
            onClick={handleDrillUp}
            className={`flex items-center gap-1 px-2 py-1 rounded transition-colors ${
              selectedKind
                ? 'text-brand-400 hover:text-brand-300 hover:bg-surface-800/50 cursor-pointer'
                : 'text-surface-500 cursor-default'
            }`}
            disabled={!selectedKind}
          >
            <Home className="w-3.5 h-3.5" />
            <span>All Kinds</span>
          </button>
          {selectedKind && (
            <>
              <ChevronRight className="w-3.5 h-3.5 text-surface-600" />
              <span
                className="px-2 py-1 rounded text-brand-300 font-medium"
                style={{ color: getKindColor(selectedKind) }}
              >
                {selectedKind.toUpperCase()}
              </span>
            </>
          )}
        </nav>
      </div>

      {/* Treemap container */}
      <div
        ref={containerRef}
        className="flex-1 rounded-xl border border-surface-800/50 bg-surface-900/40 overflow-hidden"
      >
        {(kindsLoading || drillLoading) && (
          <div className="flex items-center justify-center h-full">
            <div className="w-8 h-8 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
          </div>
        )}

        {!kindsLoading && !drillLoading && !selectedKind && dimensions.width > 0 && (
          <KindTreemap
            width={dimensions.width}
            height={dimensions.height}
            kinds={kinds}
            onDrillDown={handleDrillDown}
          />
        )}

        {!kindsLoading && !drillLoading && selectedKind && dimensions.width > 0 && (
          <NodeTreemap
            width={dimensions.width}
            height={dimensions.height}
            nodes={drillNodes}
            kind={selectedKind}
          />
        )}
      </div>
    </div>
  );
}
