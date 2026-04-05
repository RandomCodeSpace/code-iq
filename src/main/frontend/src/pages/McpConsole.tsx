import { useState, useCallback, useEffect } from 'react';
import {
  Layout, Menu, Card, Form, Input, InputNumber, Select, Button, Typography,
  Space, Tag, Tooltip, Modal, Empty,
} from 'antd';
import {
  PlayCircleOutlined,
  ClockCircleOutlined,
  SearchOutlined,
  BarChartOutlined,
  BranchesOutlined,
  ApiOutlined,
  ThunderboltOutlined,
  SafetyCertificateOutlined,
  CodeOutlined,
  AppstoreOutlined,
  HistoryOutlined,
} from '@ant-design/icons';
import { TOOLS, CATEGORIES, toolsByCategory, type McpTool } from '@/lib/mcp-tools';

const { Sider, Content } = Layout;

const CATEGORY_ICONS: Record<string, React.ReactNode> = {
  stats: <BarChartOutlined />,
  query: <SearchOutlined />,
  topology: <BranchesOutlined />,
  flow: <AppstoreOutlined />,
  analysis: <ThunderboltOutlined />,
  security: <SafetyCertificateOutlined />,
  code: <CodeOutlined />,
};

interface HistoryEntry {
  toolName: string;
  status: number;
  duration: number;
  response: string;
  timestamp: number;
}

function resolveUrl(tool: McpTool, params: Record<string, string>): string {
  return typeof tool.url === 'function' ? tool.url(params) : tool.url;
}

function countResults(json: unknown): number | null {
  if (Array.isArray(json)) return json.length;
  if (json && typeof json === 'object') {
    const obj = json as Record<string, unknown>;
    if (Array.isArray(obj.nodes)) return (obj.nodes as unknown[]).length;
    if (Array.isArray(obj.services)) return (obj.services as unknown[]).length;
    if (Array.isArray(obj.kinds)) return (obj.kinds as unknown[]).length;
  }
  return null;
}

export default function McpConsole() {
  const [selectedTool, setSelectedTool] = useState<McpTool | null>(null);
  const [response, setResponse] = useState<string>('');
  const [status, setStatus] = useState<number | null>(null);
  const [duration, setDuration] = useState<number | null>(null);
  const [executing, setExecuting] = useState(false);
  const [resultCount, setResultCount] = useState<number | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [paletteQuery, setPaletteQuery] = useState('');
  const [form] = Form.useForm();
  const grouped = toolsByCategory();

  // Cmd+K shortcut
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setPaletteOpen(v => !v);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const selectTool = useCallback((tool: McpTool) => {
    setSelectedTool(tool);
    const defaults: Record<string, string> = {};
    tool.params.forEach(p => { if (p.default !== undefined) defaults[p.name] = p.default; });
    form.setFieldsValue(defaults);
  }, [form]);

  const execute = useCallback(async () => {
    if (!selectedTool) return;
    const values = form.getFieldsValue();
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(values)) {
      if (v !== undefined && v !== null && v !== '') params[k] = String(v);
    }

    // Validate required
    const missing = selectedTool.params
      .filter(p => p.required && !params[p.name]?.trim())
      .map(p => p.name);
    if (missing.length) {
      form.validateFields();
      return;
    }

    setExecuting(true);
    const start = performance.now();
    try {
      const url = resolveUrl(selectedTool, params);
      const method = selectedTool.method ?? 'GET';
      const opts: RequestInit = { method };
      if (method === 'POST') {
        opts.headers = { 'Content-Type': 'application/json' };
        opts.body = JSON.stringify(params);
      }
      const res = await fetch(url, opts);
      const elapsed = Math.round(performance.now() - start);
      setStatus(res.status);
      setDuration(elapsed);

      const ct = res.headers.get('content-type') ?? '';
      let text: string;
      if (ct.includes('json')) {
        const json = await res.json();
        text = JSON.stringify(json, null, 2);
        setResultCount(countResults(json));
      } else {
        text = await res.text();
        setResultCount(null);
      }
      setResponse(text);
      setHistory(prev => [
        { toolName: selectedTool.name, status: res.status, duration: elapsed, response: text, timestamp: Date.now() },
        ...prev.slice(0, 19),
      ]);
    } catch (err) {
      const elapsed = Math.round(performance.now() - start);
      setStatus(0);
      setDuration(elapsed);
      const text = JSON.stringify({ error: err instanceof Error ? err.message : String(err) }, null, 2);
      setResponse(text);
      setResultCount(null);
      setHistory(prev => [
        { toolName: selectedTool.name, status: 0, duration: elapsed, response: text, timestamp: Date.now() },
        ...prev.slice(0, 19),
      ]);
    } finally {
      setExecuting(false);
    }
  }, [selectedTool, form]);

  // Build menu items
  const menuItems = CATEGORIES.map(cat => ({
    key: cat.id,
    icon: CATEGORY_ICONS[cat.id] ?? <ApiOutlined />,
    label: cat.label,
    children: (grouped[cat.id] ?? []).map(tool => ({
      key: tool.name,
      label: (
        <Tooltip title={tool.description} placement="right" mouseEnterDelay={0.5}>
          <span style={{ fontSize: 12 }}>{tool.name}</span>
        </Tooltip>
      ),
    })),
  }));

  const paletteResults = paletteQuery.trim()
    ? TOOLS.filter(t =>
        t.name.includes(paletteQuery.toLowerCase()) ||
        t.description.toLowerCase().includes(paletteQuery.toLowerCase())
      )
    : TOOLS;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>MCP Console</Typography.Title>
          <Typography.Text type="secondary">
            {TOOLS.length} tools across {CATEGORIES.length} categories
          </Typography.Text>
        </div>
        <Button icon={<SearchOutlined />} onClick={() => setPaletteOpen(true)}>
          Search Tools (Ctrl+K)
        </Button>
      </div>

      <Layout style={{ background: 'transparent', minHeight: 'calc(100vh - 220px)' }}>
        <Sider width={260} style={{ background: 'transparent', marginRight: 16 }}>
          <Card styles={{ body: { padding: 0 } }} style={{ height: '100%', overflow: 'auto' }}>
            <Menu
              mode="inline"
              selectedKeys={selectedTool ? [selectedTool.name] : []}
              defaultOpenKeys={['stats']}
              items={menuItems}
              onClick={({ key }) => {
                const tool = TOOLS.find(t => t.name === key);
                if (tool) selectTool(tool);
              }}
              style={{ borderRight: 'none' }}
            />
          </Card>
        </Sider>

        <Content>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16, height: '100%' }}>
            {/* Tool form */}
            <Card
              title={selectedTool ? (
                <Space>
                  <span>{selectedTool.name}</span>
                  <Tag color={selectedTool.method === 'POST' ? 'orange' : 'green'}>
                    {selectedTool.method ?? 'GET'}
                  </Tag>
                </Space>
              ) : 'Select a tool'}
              extra={selectedTool && (
                <Button
                  type="primary"
                  icon={<PlayCircleOutlined />}
                  loading={executing}
                  onClick={execute}
                >
                  Run
                </Button>
              )}
            >
              {selectedTool ? (
                <>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
                    {selectedTool.description}
                  </Typography.Paragraph>
                  {selectedTool.params.length > 0 ? (
                    <Form form={form} layout="vertical" onFinish={execute}>
                      {selectedTool.params.map(param => (
                        <Form.Item
                          key={param.name}
                          label={
                            <Space>
                              <span>{param.name}</span>
                              {param.required && <Tag color="red">required</Tag>}
                              <Tag>{param.type}</Tag>
                            </Space>
                          }
                          name={param.name}
                          rules={param.required ? [{ required: true, message: `${param.name} is required` }] : []}
                          tooltip={param.description}
                        >
                          {param.options ? (
                            <Select
                              allowClear
                              placeholder={param.description}
                              options={param.options.filter(Boolean).map(o => ({ label: o, value: o }))}
                            />
                          ) : param.type === 'number' ? (
                            <InputNumber
                              placeholder={param.default ?? param.description}
                              style={{ width: '100%' }}
                            />
                          ) : param.type === 'boolean' ? (
                            <Select
                              options={[{ label: 'true', value: 'true' }, { label: 'false', value: 'false' }]}
                            />
                          ) : (
                            <Input
                              placeholder={param.default ?? param.description}
                              onPressEnter={execute}
                            />
                          )}
                        </Form.Item>
                      ))}
                    </Form>
                  ) : (
                    <Typography.Text type="secondary">No parameters required. Click Run to execute.</Typography.Text>
                  )}
                  <Typography.Text code style={{ fontSize: 11, wordBreak: 'break-all' }}>
                    {resolveUrl(selectedTool, form.getFieldsValue() ?? {})}
                  </Typography.Text>
                </>
              ) : (
                <Empty description="Select a tool from the left panel or use Ctrl+K to search" />
              )}
            </Card>

            {/* Response */}
            <Card
              title={
                <Space>
                  <span>Response</span>
                  {status !== null && (
                    <Tag color={status >= 200 && status < 300 ? 'green' : status >= 400 ? 'red' : 'orange'}>
                      {status}
                    </Tag>
                  )}
                  {duration !== null && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      <ClockCircleOutlined /> {duration}ms
                    </Typography.Text>
                  )}
                  {resultCount !== null && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {resultCount} results
                    </Typography.Text>
                  )}
                </Space>
              }
              style={{ flex: 1 }}
              styles={{ body: { overflow: 'auto', maxHeight: 400 } }}
            >
              {response ? (
                <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                  {response}
                </pre>
              ) : (
                <Empty description="Run a tool to see the response" />
              )}
            </Card>

            {/* History */}
            {history.length > 0 && (
              <Card
                title={<Space><HistoryOutlined /> History ({history.length})</Space>}
                styles={{ body: { padding: 0 } }}
                size="small"
              >
                {history.map((entry, i) => (
                  <div
                    key={i}
                    onClick={() => setResponse(entry.response)}
                    style={{
                      padding: '8px 16px',
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                      borderBottom: '1px solid rgba(128,128,128,0.1)',
                    }}
                  >
                    <Tag color={entry.status >= 200 && entry.status < 300 ? 'green' : 'red'}>
                      {entry.status}
                    </Tag>
                    <Typography.Text code style={{ fontSize: 11 }}>{entry.toolName}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 11, marginLeft: 'auto' }}>
                      {entry.duration}ms
                    </Typography.Text>
                  </div>
                ))}
              </Card>
            )}
          </div>
        </Content>
      </Layout>

      {/* Command Palette */}
      <Modal
        title="Search Tools"
        open={paletteOpen}
        onCancel={() => { setPaletteOpen(false); setPaletteQuery(''); }}
        footer={null}
        width={500}
      >
        <Input
          autoFocus
          placeholder="Search tools by name or description..."
          value={paletteQuery}
          onChange={e => setPaletteQuery(e.target.value)}
          prefix={<SearchOutlined />}
          style={{ marginBottom: 16 }}
          onKeyDown={e => {
            if (e.key === 'Enter' && paletteResults.length > 0) {
              selectTool(paletteResults[0]);
              setPaletteOpen(false);
              setPaletteQuery('');
            }
          }}
        />
        <div style={{ maxHeight: 400, overflow: 'auto' }}>
          {paletteResults.length === 0 ? (
            <Empty description={`No tools match "${paletteQuery}"`} />
          ) : (
            paletteResults.map(tool => {
              const cat = CATEGORIES.find(c => c.id === tool.category);
              return (
                <div
                  key={tool.name}
                  onClick={() => {
                    selectTool(tool);
                    setPaletteOpen(false);
                    setPaletteQuery('');
                  }}
                  style={{
                    padding: '8px 12px',
                    cursor: 'pointer',
                    borderBottom: '1px solid rgba(128,128,128,0.1)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                  }}
                >
                  <div style={{ flex: 1 }}>
                    <Typography.Text strong style={{ fontSize: 12 }}>{tool.name}</Typography.Text>
                    <br />
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      {tool.description}
                    </Typography.Text>
                  </div>
                  {cat && <Tag>{cat.label}</Tag>}
                </div>
              );
            })
          )}
        </div>
      </Modal>
    </div>
  );
}
