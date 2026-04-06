import { useState, useMemo } from 'react';
import { Select, Typography, Space, Spin, Alert } from 'antd';
import ReactECharts from 'echarts-for-react';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import { useTheme } from '@/context/ThemeContext';
import type { FileTreeResponse, FileTreeNode } from '@/types/api';

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

interface EChartsTreeNode {
  name: string;
  value?: number;
  children?: EChartsTreeNode[];
  itemStyle?: { color: string };
}

function inferLang(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() ?? '';
  return EXT_TO_LANG[ext] ?? 'other';
}

function dominantLang(nodes: FileTreeNode[]): string {
  const counts: Record<string, number> = {};
  function walk(items: FileTreeNode[]) {
    for (const item of items) {
      if (item.type === 'file') {
        const lang = inferLang(item.name);
        counts[lang] = (counts[lang] ?? 0) + (item.nodeCount || 1);
      }
      if (item.children) walk(item.children);
    }
  }
  walk(nodes);
  return Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'other';
}

/**
 * Collapse single-child directory chains into one node.
 * e.g., src → main → java → io → github becomes "src/main/java/io/github"
 * This avoids 10+ clicks through single-child directories (common in Java packages).
 */
function collapseTree(nodes: FileTreeNode[]): FileTreeNode[] {
  return nodes.map(n => {
    if (n.type !== 'directory' || !n.children || n.children.length === 0) return n;

    // Collapse: if this directory has exactly 1 child that is also a directory, merge names
    let current = n;
    let collapsedName = n.name;
    while (
      current.type === 'directory' &&
      current.children &&
      current.children.length === 1 &&
      current.children[0].type === 'directory' &&
      current.children[0].children &&
      current.children[0].children.length > 0
    ) {
      current = current.children[0];
      collapsedName += '/' + current.name;
    }

    const collapsedChildren = collapseTree(current.children ?? []);
    return { ...current, name: collapsedName, children: collapsedChildren, nodeCount: n.nodeCount };
  });
}

function toEChartsNodes(nodes: FileTreeNode[]): EChartsTreeNode[] {
  const result: EChartsTreeNode[] = [];
  for (const n of nodes) {
    if (n.nodeCount <= 0 && (!n.children || n.children.length === 0)) continue;

    if (n.type === 'directory' && n.children && n.children.length > 0) {
      const children = toEChartsNodes(n.children);
      if (children.length === 0) continue;
      const lang = dominantLang(n.children);
      // Directory nodes: NO value — ECharts sums from children for correct proportions
      result.push({
        name: n.name,
        children,
        itemStyle: { color: LANG_COLORS[lang] ?? '#666' },
      });
    } else {
      // Leaf file node: value = nodeCount (determines rectangle size)
      const lang = inferLang(n.name);
      result.push({
        name: n.name,
        value: Math.max(n.nodeCount, 1),
        itemStyle: { color: LANG_COLORS[lang] ?? '#666' },
      });
    }
  }
  return result;
}

function fileTreeToECharts(nodes: FileTreeNode[]): EChartsTreeNode[] {
  return toEChartsNodes(collapseTree(nodes));
}

function collectLanguages(nodes: FileTreeNode[]): string[] {
  const langs = new Set<string>();
  function walk(items: FileTreeNode[]) {
    for (const item of items) {
      if (item.type === 'file') {
        const lang = inferLang(item.name);
        if (lang !== 'other') langs.add(lang);
      }
      if (item.children) walk(item.children);
    }
  }
  walk(nodes);
  return Array.from(langs).sort();
}

export default function CodebaseMap() {
  const { isDark } = useTheme();
  const [langFilter, setLangFilter] = useState<string | undefined>(undefined);

  const { data: treeData, loading, error } = useApi<FileTreeResponse>(
    () => api.getFileTree(), []
  );

  const tree = treeData?.tree ?? [];
  const totalFiles = treeData?.total_files ?? 0;
  const uniqueLangs = useMemo(() => collectLanguages(tree), [tree]);
  const treemapData = useMemo(() => fileTreeToECharts(tree), [tree]);

  const chartOption = useMemo(() => ({
    tooltip: {
      formatter: (info: { name: string; value: number; treePathInfo?: Array<{ name: string }> }) => {
        const path = info.treePathInfo?.map(p => p.name).filter(Boolean).join('/') ?? info.name;
        return `<b>${path}</b><br/>Nodes: ${(info.value ?? 0).toLocaleString()}`;
      },
    },
    series: [{
      type: 'treemap',
      data: treemapData,
      leafDepth: 2,
      drillDownIcon: '▶ ',
      roam: false,
      nodeClick: 'zoomToNode',
      breadcrumb: {
        show: true,
        top: 4,
        left: 4,
        itemStyle: {
          color: isDark ? '#1a1a1a' : '#f5f5f5',
          borderColor: isDark ? '#303030' : '#d9d9d9',
        },
        textStyle: {
          color: isDark ? '#e0e0e0' : '#333',
          fontSize: 13,
        },
      },
      levels: [
        {
          itemStyle: {
            borderColor: isDark ? '#303030' : '#bbb',
            borderWidth: 3,
            gapWidth: 3,
          },
          upperLabel: {
            show: true,
            height: 30,
            color: isDark ? '#e0e0e0' : '#333',
            fontSize: 14,
            fontWeight: 'bold' as const,
          },
        },
        {
          itemStyle: {
            borderColor: isDark ? '#404040' : '#ccc',
            borderWidth: 2,
            gapWidth: 2,
          },
          upperLabel: {
            show: true,
            height: 24,
            fontSize: 12,
            color: isDark ? '#ccc' : '#555',
          },
        },
        {
          itemStyle: {
            borderColor: isDark ? '#4a4a4a' : '#ddd',
            borderWidth: 1,
            gapWidth: 1,
          },
          label: { show: true, fontSize: 11 },
        },
      ],
      label: {
        show: true,
        formatter: '{b}',
        fontSize: 12,
        color: isDark ? '#ddd' : '#333',
      },
    }],
  }), [treemapData, isDark]);

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  }

  if (error) {
    return <Alert type="error" message="Failed to load codebase data" description={error} showIcon style={{ margin: 24 }} />;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 96px)', margin: '-16px -24px', padding: '8px 16px 0' }}>
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 4,
        flexShrink: 0,
      }}>
        <Space>
          <Typography.Title level={4} style={{ margin: 0 }}>Codebase Map</Typography.Title>
          <Typography.Text type="secondary">
            {totalFiles.toLocaleString()} files · {uniqueLangs.length} languages
          </Typography.Text>
        </Space>
        <Select
          allowClear
          placeholder="Filter by language"
          style={{ width: 180 }}
          value={langFilter}
          onChange={setLangFilter}
          options={uniqueLangs.map(l => ({ label: l.charAt(0).toUpperCase() + l.slice(1), value: l }))}
        />
      </div>

      <div style={{ flex: 1, minHeight: 0 }}>
        {treemapData.length > 0 ? (
          <ReactECharts
            option={chartOption}
            style={{ height: '100%', width: '100%' }}
            theme={isDark ? 'dark' : undefined}
            opts={{ renderer: 'canvas' }}
          />
        ) : (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <Typography.Text type="secondary">No file data available. Run index + enrich first.</Typography.Text>
          </div>
        )}
      </div>
    </div>
  );
}
