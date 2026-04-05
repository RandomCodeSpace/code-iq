import { useState, useCallback, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Tabs, Table, Tag, Input, Drawer, Descriptions, Spin, Alert, Typography, Space, List } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { KindsResponse, NodeResponse, NodesListResponse, SearchResult } from '@/types/api';

const KIND_COLORS: Record<string, string> = {
  endpoint: 'green', entity: 'blue', class: 'purple', method: 'cyan',
  module: 'orange', guard: 'red', config_key: 'gold', infra_resource: 'volcano',
  component: 'geekblue', service: 'magenta', interface: 'lime', function: 'cyan',
  enum: 'gold', field: 'default', route: 'green', middleware: 'orange',
  producer: 'volcano', consumer: 'blue', topic: 'purple', schema: 'geekblue',
};

export default function Explorer() {
  const { kind: urlKind } = useParams<{ kind?: string }>();
  const navigate = useNavigate();
  const [activeKind, setActiveKind] = useState<string>(urlKind ?? '');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<SearchResult[] | null>(null);
  const [searchLoading, setSearchLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selectedNode, setSelectedNode] = useState<NodeResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [neighbors, setNeighbors] = useState<{ incoming: Array<{ edge: { kind: string }; node: NodeResponse }>; outgoing: Array<{ edge: { kind: string }; node: NodeResponse }> } | null>(null);

  const { data: kinds, loading: kindsLoading } = useApi<KindsResponse>(() => api.getKinds(), []);

  const { data: nodesData, loading: nodesLoading } = useApi<NodesListResponse>(
    () => activeKind
      ? api.getNodesByKind(activeKind, pageSize, (page - 1) * pageSize)
      : api.getNodes(undefined, undefined, pageSize, (page - 1) * pageSize),
    [activeKind, page, pageSize]
  );

  // Sync URL with active kind
  useEffect(() => {
    if (urlKind && urlKind !== activeKind) {
      setActiveKind(urlKind);
      setPage(1);
    }
  }, [urlKind]);

  const handleKindChange = useCallback((kind: string) => {
    setActiveKind(kind);
    setPage(1);
    setSearchResults(null);
    setSearchQuery('');
    navigate(kind ? `/explorer/${kind}` : '/explorer', { replace: true });
  }, [navigate]);

  const handleSearch = useCallback(async (value: string) => {
    if (!value.trim()) {
      setSearchResults(null);
      return;
    }
    setSearchLoading(true);
    try {
      const results = await api.search(value, 50);
      setSearchResults(results);
    } catch {
      setSearchResults([]);
    } finally {
      setSearchLoading(false);
    }
  }, []);

  const openDetail = useCallback(async (nodeId: string) => {
    setDrawerOpen(true);
    setDetailLoading(true);
    setNeighbors(null);
    try {
      const [detail, nbrs] = await Promise.all([
        api.getNodeDetail(nodeId),
        api.getNodeNeighbors(nodeId).catch(() => null),
      ]);
      setSelectedNode(detail);
      if (nbrs && typeof nbrs === 'object') {
        setNeighbors(nbrs as typeof neighbors);
      }
    } catch {
      setSelectedNode(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const columns: ColumnsType<NodeResponse> = [
    {
      title: 'Label',
      dataIndex: 'label',
      key: 'label',
      ellipsis: true,
      render: (text: string, record: NodeResponse) => (
        <a onClick={() => openDetail(record.id)}>{text}</a>
      ),
    },
    {
      title: 'Kind',
      dataIndex: 'kind',
      key: 'kind',
      width: 130,
      render: (kind: string) => <Tag color={KIND_COLORS[kind] ?? 'default'}>{kind}</Tag>,
    },
    {
      title: 'Module',
      dataIndex: 'module',
      key: 'module',
      width: 180,
      ellipsis: true,
    },
    {
      title: 'File Path',
      dataIndex: 'file_path',
      key: 'file_path',
      ellipsis: true,
      width: 300,
    },
    {
      title: 'Layer',
      dataIndex: 'layer',
      key: 'layer',
      width: 100,
      render: (layer: string) => layer ? <Tag>{layer}</Tag> : null,
    },
  ];

  const searchColumns: ColumnsType<SearchResult> = [
    {
      title: 'Label',
      key: 'label',
      ellipsis: true,
      render: (_: unknown, record: SearchResult) => (
        <a onClick={() => openDetail(record.id)}>{record.label ?? record.name ?? record.id}</a>
      ),
    },
    {
      title: 'Kind',
      dataIndex: 'kind',
      key: 'kind',
      width: 130,
      render: (kind: string) => <Tag color={KIND_COLORS[kind] ?? 'default'}>{kind}</Tag>,
    },
    {
      title: 'File',
      key: 'file',
      ellipsis: true,
      width: 300,
      render: (_: unknown, record: SearchResult) => record.file_path ?? record.filePath ?? '',
    },
    {
      title: 'Score',
      dataIndex: 'score',
      key: 'score',
      width: 80,
      render: (score: number) => score !== undefined ? score.toFixed(2) : '',
    },
  ];

  if (kindsLoading) {
    return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  }

  const kindTabs = [
    { key: '', label: `All (${kinds?.total ?? 0})` },
    ...(kinds?.kinds ?? [])
      .sort((a, b) => b.count - a.count)
      .map(k => ({
        key: k.kind,
        label: `${k.kind} (${k.count})`,
      })),
  ];

  return (
    <div>
      <Typography.Title level={3} style={{ marginBottom: 16 }}>Explorer</Typography.Title>

      <Input.Search
        placeholder="Search nodes by name, ID, or property..."
        allowClear
        enterButton
        size="large"
        style={{ marginBottom: 16, maxWidth: 600 }}
        value={searchQuery}
        onChange={e => setSearchQuery(e.target.value)}
        onSearch={handleSearch}
        loading={searchLoading}
      />

      {searchResults ? (
        <div>
          <div style={{ marginBottom: 8 }}>
            <Typography.Text type="secondary">
              {searchResults.length} search result{searchResults.length !== 1 ? 's' : ''}
            </Typography.Text>
            <a onClick={() => { setSearchResults(null); setSearchQuery(''); }} style={{ marginLeft: 12 }}>
              Clear search
            </a>
          </div>
          <Table
            dataSource={searchResults}
            columns={searchColumns}
            rowKey="id"
            size="small"
            pagination={{ pageSize: 50 }}
          />
        </div>
      ) : (
        <>
          <Tabs
            activeKey={activeKind}
            onChange={handleKindChange}
            items={kindTabs}
            type="card"
            style={{ marginBottom: 0 }}
            tabBarStyle={{ marginBottom: 0 }}
          />
          <Table
            dataSource={nodesData?.nodes ?? []}
            columns={columns}
            rowKey="id"
            size="small"
            loading={nodesLoading}
            pagination={{
              current: page,
              pageSize,
              total: nodesData?.total ?? nodesData?.count ?? (nodesData?.nodes?.length ?? 0),
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100'],
              onChange: (p, ps) => { setPage(p); setPageSize(ps); },
            }}
            onRow={(record) => ({
              style: { cursor: 'pointer' },
              onClick: () => openDetail(record.id),
            })}
          />
        </>
      )}

      <Drawer
        title={selectedNode?.label ?? 'Node Detail'}
        placement="right"
        width={520}
        open={drawerOpen}
        onClose={() => { setDrawerOpen(false); setSelectedNode(null); setNeighbors(null); }}
      >
        {detailLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
        ) : selectedNode ? (
          <div>
            <Descriptions column={1} bordered size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="ID">
                <Typography.Text code copyable style={{ fontSize: 12, wordBreak: 'break-all' }}>
                  {selectedNode.id}
                </Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="Kind">
                <Tag color={KIND_COLORS[selectedNode.kind] ?? 'default'}>{selectedNode.kind}</Tag>
              </Descriptions.Item>
              {selectedNode.fqn && (
                <Descriptions.Item label="FQN">{selectedNode.fqn}</Descriptions.Item>
              )}
              {selectedNode.module && (
                <Descriptions.Item label="Module">{selectedNode.module}</Descriptions.Item>
              )}
              {selectedNode.file_path && (
                <Descriptions.Item label="File">{selectedNode.file_path}</Descriptions.Item>
              )}
              {selectedNode.line_start != null && (
                <Descriptions.Item label="Lines">
                  {selectedNode.line_start}{selectedNode.line_end ? ` - ${selectedNode.line_end}` : ''}
                </Descriptions.Item>
              )}
              {selectedNode.layer && (
                <Descriptions.Item label="Layer"><Tag>{selectedNode.layer}</Tag></Descriptions.Item>
              )}
              {selectedNode.annotations && selectedNode.annotations.length > 0 && (
                <Descriptions.Item label="Annotations">
                  <Space wrap>
                    {selectedNode.annotations.map((a, i) => <Tag key={i}>{a}</Tag>)}
                  </Space>
                </Descriptions.Item>
              )}
            </Descriptions>

            {/* Properties */}
            {selectedNode.properties && Object.keys(selectedNode.properties).length > 0 && (
              <>
                <Typography.Title level={5}>Properties</Typography.Title>
                <Descriptions column={1} bordered size="small" style={{ marginBottom: 16 }}>
                  {Object.entries(selectedNode.properties).map(([key, val]) => (
                    <Descriptions.Item key={key} label={key}>
                      <Typography.Text code style={{ fontSize: 11 }}>
                        {typeof val === 'object' ? JSON.stringify(val) : String(val)}
                      </Typography.Text>
                    </Descriptions.Item>
                  ))}
                </Descriptions>
              </>
            )}

            {/* Neighbors */}
            {neighbors && (
              <>
                {neighbors.incoming && neighbors.incoming.length > 0 && (
                  <>
                    <Typography.Title level={5}>Incoming ({neighbors.incoming.length})</Typography.Title>
                    <List
                      size="small"
                      dataSource={neighbors.incoming}
                      renderItem={(item) => (
                        <List.Item>
                          <Space>
                            <Tag color={KIND_COLORS[item.node.kind] ?? 'default'}>{item.node.kind}</Tag>
                            <a onClick={() => openDetail(item.node.id)}>{item.node.label}</a>
                            <Tag>{item.edge.kind}</Tag>
                          </Space>
                        </List.Item>
                      )}
                    />
                  </>
                )}
                {neighbors.outgoing && neighbors.outgoing.length > 0 && (
                  <>
                    <Typography.Title level={5}>Outgoing ({neighbors.outgoing.length})</Typography.Title>
                    <List
                      size="small"
                      dataSource={neighbors.outgoing}
                      renderItem={(item) => (
                        <List.Item>
                          <Space>
                            <Tag>{item.edge.kind}</Tag>
                            <a onClick={() => openDetail(item.node.id)}>{item.node.label}</a>
                            <Tag color={KIND_COLORS[item.node.kind] ?? 'default'}>{item.node.kind}</Tag>
                          </Space>
                        </List.Item>
                      )}
                    />
                  </>
                )}
              </>
            )}
          </div>
        ) : (
          <Alert type="warning" message="Node not found" />
        )}
      </Drawer>
    </div>
  );
}
