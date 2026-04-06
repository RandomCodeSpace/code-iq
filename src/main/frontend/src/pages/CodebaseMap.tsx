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
  value: number;
  children?: EChartsTreeNode[];
  itemStyle?: { color: string };
}

function inferLang(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() ?? '';
  return EXT_TO_LANG[ext] ?? 'other';
}

function fileTreeToECharts(nodes: FileTreeNode[]): EChartsTreeNode[] {
  return nodes
    .filter(n => n.nodeCount > 0 || (n.children && n.children.length > 0))
    .map(n => {
      if (n.type === 'directory' && n.children && n.children.length > 0) {
        const children = fileTreeToECharts(n.children);
        // Dominant language from children names
        const langCounts: Record<string, number> = {};
        function countLangs(items: FileTreeNode[]) {
          for (const item of items) {
            if (item.type === 'file') {
              const lang = inferLang(item.name);
              langCounts[lang] = (langCounts[lang] ?? 0) + (item.nodeCount || 1);
            }
            if (item.children) countLangs(item.children);
          }
        }
        countLangs(n.children);
        const dominant = Object.entries(langCounts).sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'other';

        return {
          name: n.name,
          value: n.nodeCount,
          children: children.length > 0 ? children : undefined,
          itemStyle: { color: LANG_COLORS[dominant] ?? '#666' },
        };
      }
      const lang = inferLang(n.name);
      return {
        name: n.name,
        value: Math.max(n.nodeCount, 1),
        itemStyle: { color: LANG_COLORS[lang] ?? '#666' },
      };
    });
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

  const treemapData = useMemo(() => {
    // TODO: apply langFilter if needed
    return fileTreeToECharts(tree);
  }, [tree, langFilter]);

  const chartOption = useMemo(() => ({
    tooltip: {
      formatter: (info: { name: string; value: number; treePathInfo?: Array<{ name: string }> }) => {
        const path = info.treePathInfo?.map(p => p.name).filter(Boolean).join('/') ?? info.name;
        return `<b>${path}</b><br/>Nodes: ${info.value?.toLocaleString()}`;
      },
    },
    series: [{
      type: 'treemap',
      data: treemapData,
      roam: 'move',
      leafDepth: 2,
      drillDownIcon: '▶',
      breadcrumb: {
        show: true,
        top: 4,
        left: 4,
        itemStyle: {
          color: isDark ? '#2a2a2e' : '#f5f5f5',
          borderColor: isDark ? '#3f3f46' : '#d9d9d9',
        },
        textStyle: { color: isDark ? '#e0e0e0' : '#333' },
      },
      levels: [
        {
          itemStyle: {
            borderColor: isDark ? '#444' : '#ccc',
            borderWidth: 2,
            gapWidth: 2,
          },
          upperLabel: {
            show: true,
            height: 28,
            color: isDark ? '#e0e0e0' : '#333',
            fontSize: 13,
            fontWeight: 'bold' as const,
          },
        },
        {
          itemStyle: {
            borderColor: isDark ? '#555' : '#ddd',
            borderWidth: 1,
            gapWidth: 1,
          },
          upperLabel: { show: true, height: 22, fontSize: 12 },
        },
        {
          itemStyle: {
            borderColor: isDark ? '#666' : '#eee',
            borderWidth: 0.5,
            gapWidth: 0.5,
          },
          label: { show: true, fontSize: 11 },
        },
      ],
      label: { show: true, formatter: '{b}', fontSize: 12 },
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
