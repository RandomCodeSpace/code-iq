import { useEffect, useRef, useState, useCallback } from 'react';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { TopologyResponse } from '@/types/api';
import { Maximize2, ZoomIn, ZoomOut, RefreshCw, AlertCircle } from 'lucide-react';
import cytoscape from 'cytoscape';
import dagre from 'cytoscape-dagre';

// Register dagre layout
cytoscape.use(dagre);

const nodeShapes: Record<string, string> = {
  service: 'round-rectangle',
  database: 'barrel',
  queue: 'diamond',
  cache: 'round-diamond',
  external: 'pentagon',
  gateway: 'hexagon',
  default: 'ellipse',
};

const nodeColors: Record<string, string> = {
  service: '#6366f1',
  database: '#f59e0b',
  queue: '#10b981',
  cache: '#ef4444',
  external: '#64748b',
  gateway: '#8b5cf6',
  default: '#6366f1',
};

export default function TopologyView() {
  const { data, loading, error, refetch } = useApi(() => api.getTopology(), []);
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);
  const [selectedNode, setSelectedNode] = useState<string | null>(null);
  const [nodeDetail, setNodeDetail] = useState<Record<string, unknown> | null>(null);

  const initGraph = useCallback((topo: TopologyResponse) => {
    if (!containerRef.current) return;

    // Destroy previous instance
    if (cyRef.current) {
      cyRef.current.destroy();
    }

    const elements: cytoscape.ElementDefinition[] = [];

    // Nodes
    for (const node of topo.services || []) {
      elements.push({
        data: {
          id: node.id,
          label: node.name,
          type: node.type || 'service',
          nodeCount: node.nodeCount,
          ...node.properties,
        },
      });
    }

    // Edges
    for (const edge of topo.connections || []) {
      elements.push({
        data: {
          source: edge.source,
          target: edge.target,
          label: edge.label || edge.type || '',
          type: edge.type || 'depends_on',
        },
      });
    }

    const cy = cytoscape({
      container: containerRef.current,
      elements,
      style: [
        {
          selector: 'node',
          style: {
            label: 'data(label)',
            'text-valign': 'center',
            'text-halign': 'center',
            color: '#e2e8f0',
            'font-size': '11px',
            'font-family': 'Inter, system-ui, sans-serif',
            'text-wrap': 'wrap',
            'text-max-width': '80px',
            width: 50,
            height: 50,
            'background-opacity': 0.9,
            'border-width': 2,
            'border-opacity': 0.6,
            'overlay-opacity': 0,
          },
        },
        ...Object.entries(nodeColors).map(([type, color]) => ({
          selector: `node[type="${type}"]`,
          style: {
            'background-color': color,
            'border-color': color,
            shape: (nodeShapes[type] || 'ellipse') as cytoscape.Css.NodeShape,
          },
        })),
        {
          selector: 'edge',
          style: {
            width: 1.5,
            'line-color': '#475569',
            'target-arrow-color': '#475569',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            label: 'data(label)',
            'font-size': '9px',
            color: '#64748b',
            'text-rotation': 'autorotate',
            'text-margin-y': -8,
            'overlay-opacity': 0,
          },
        },
        {
          selector: 'node:selected',
          style: {
            'border-width': 3,
            'border-color': '#818cf8',
            'background-color': '#6366f1',
          },
        },
        {
          selector: 'edge:selected',
          style: {
            'line-color': '#818cf8',
            'target-arrow-color': '#818cf8',
            width: 3,
          },
        },
      ],
      layout: {
        name: 'dagre',
        rankDir: 'TB',
        padding: 40,
        spacingFactor: 1.4,
        nodeSep: 60,
        rankSep: 80,
      } as cytoscape.LayoutOptions,
      minZoom: 0.2,
      maxZoom: 4,
      wheelSensitivity: 0.3,
    });

    cy.on('tap', 'node', (e) => {
      const id = e.target.id();
      setSelectedNode(id);
      api.getTopologyServiceDetail(id)
        .then(setNodeDetail)
        .catch(() => setNodeDetail(null));
    });

    cy.on('tap', (e) => {
      if (e.target === cy) {
        setSelectedNode(null);
        setNodeDetail(null);
      }
    });

    cyRef.current = cy;
  }, []);

  useEffect(() => {
    if (data) initGraph(data);
    return () => { cyRef.current?.destroy(); };
  }, [data, initGraph]);

  const fit = () => cyRef.current?.fit(undefined, 40);
  const zoomIn = () => {
    const cy = cyRef.current;
    if (cy) cy.zoom({ level: cy.zoom() * 1.3, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  };
  const zoomOut = () => {
    const cy = cyRef.current;
    if (cy) cy.zoom({ level: cy.zoom() / 1.3, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="w-8 h-8 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="glass-card p-8 max-w-md text-center space-y-4">
          <AlertCircle className="w-12 h-12 text-amber-400 mx-auto" />
          <p className="text-surface-400 text-sm">{error}</p>
          <button onClick={refetch} className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-brand-500/10 text-brand-400 border border-brand-500/20 hover:bg-brand-500/20 transition-colors text-sm">
            <RefreshCw className="w-4 h-4" /> Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold gradient-text">Topology</h1>
          <p className="text-sm text-surface-400 mt-1">Service dependency map</p>
        </div>
        <button onClick={refetch} className="p-2 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/50 transition-colors">
          <RefreshCw className="w-4 h-4" />
        </button>
      </div>

      <div className="relative glass-card overflow-hidden" style={{ height: 'calc(100vh - 200px)' }}>
        <div ref={containerRef} className="w-full h-full" />

        {/* Controls */}
        <div className="absolute top-4 right-4 flex flex-col gap-1">
          {[
            { icon: ZoomIn, action: zoomIn, label: 'Zoom in' },
            { icon: ZoomOut, action: zoomOut, label: 'Zoom out' },
            { icon: Maximize2, action: fit, label: 'Fit' },
          ].map(({ icon: Icon, action, label }) => (
            <button
              key={label}
              onClick={action}
              title={label}
              className="p-2 rounded-lg bg-surface-900/80 border border-surface-700/50 text-surface-400 hover:text-surface-200 hover:bg-surface-800 transition-colors"
            >
              <Icon className="w-4 h-4" />
            </button>
          ))}
        </div>

        {/* Detail panel */}
        {selectedNode && nodeDetail && (
          <div className="absolute bottom-4 left-4 w-80 glass-card p-4 space-y-2 max-h-64 overflow-y-auto">
            <h4 className="text-sm font-semibold text-surface-100">{selectedNode}</h4>
            {Object.entries(nodeDetail).map(([k, v]) => (
              <div key={k} className="flex gap-2 text-xs">
                <span className="text-surface-400 font-mono">{k}:</span>
                <span className="text-surface-200 break-all">
                  {typeof v === 'object' ? JSON.stringify(v, null, 1) : String(v)}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
