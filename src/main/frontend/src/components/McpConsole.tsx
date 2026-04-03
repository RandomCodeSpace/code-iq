import { useState, useCallback, useEffect, useRef } from "react";
import { Play, Clock, Terminal, Search, Command } from "lucide-react";
import {
  Accordion, AccordionContent, AccordionItem, AccordionTrigger,
} from "@/components/ui/accordion";
import { Input } from "@/components/ui/input";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ScrollArea } from "@/components/ui/scroll-area";
import { TOOLS, CATEGORIES, COLOR_MAP, toolsByCategory, type McpTool } from "@/lib/mcp-tools";

// ── Types ─────────────────────────────────────────────────────────────────────

interface HistoryEntry {
  toolName: string;
  status: number;
  duration: number;
  response: string;
  timestamp: number;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function resolveUrl(tool: McpTool, params: Record<string, string>): string {
  return typeof tool.url === "function" ? tool.url(params) : tool.url;
}

function validateParams(tool: McpTool, params: Record<string, string>): string[] {
  return tool.params
    .filter((p) => p.required && !params[p.name]?.trim())
    .map((p) => `"${p.name}" is required`);
}

function countResults(json: unknown): number | null {
  if (Array.isArray(json)) return json.length;
  if (json && typeof json === "object") {
    const obj = json as Record<string, unknown>;
    if (Array.isArray(obj.nodes)) return (obj.nodes as unknown[]).length;
    if (Array.isArray(obj.services)) return (obj.services as unknown[]).length;
    if (Array.isArray(obj.kinds)) return (obj.kinds as unknown[]).length;
  }
  return null;
}

// ── ToolItem ──────────────────────────────────────────────────────────────────

interface ToolItemProps {
  tool: McpTool;
  onExecute: (tool: McpTool, params: Record<string, string>) => void;
  executing: boolean;
  activeTool: string | null;
}

function ToolItem({ tool, onExecute, executing, activeTool }: ToolItemProps) {
  const [params, setParams] = useState<Record<string, string>>(() => {
    const defaults: Record<string, string> = {};
    tool.params.forEach((p) => { if (p.default !== undefined) defaults[p.name] = p.default; });
    return defaults;
  });
  const [errors, setErrors] = useState<string[]>([]);
  const isActive = activeTool === tool.name;
  const isRunning = executing && isActive;

  const handleRun = () => {
    const errs = validateParams(tool, params);
    if (errs.length) { setErrors(errs); return; }
    setErrors([]);
    onExecute(tool, params);
  };

  const cat = CATEGORIES.find((c) => c.id === tool.category);

  return (
    <div
      data-testid="tool-item"
      data-tool-name={tool.name}
      className={`px-3 py-2.5 border-b border-surface-800/20 last:border-0 transition-colors ${
        isActive ? "bg-brand-500/5 border-l-2 border-l-brand-500" : "hover:bg-surface-800/20"
      }`}
    >
      {/* Header row */}
      <div className="flex items-center justify-between gap-2 mb-1">
        <div className="flex items-center gap-2 min-w-0">
          {cat && (
            <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${COLOR_MAP[cat.color].split(" ")[0]}`} />
          )}
          <span className="text-xs font-mono font-semibold text-surface-100 truncate">{tool.name}</span>
          <span className={`text-[9px] font-mono font-bold px-1 py-0.5 rounded flex-shrink-0 ${
            (tool.method ?? "GET") === "GET"
              ? "bg-emerald-500/10 text-emerald-400"
              : "bg-amber-500/10 text-amber-400"
          }`}>
            {tool.method ?? "GET"}
          </span>
        </div>
        <button
          onClick={handleRun}
          disabled={isRunning}
          className="flex-shrink-0 flex items-center gap-1 px-2.5 py-1 rounded bg-brand-600 hover:bg-brand-500 text-white text-[10px] font-medium transition-colors disabled:opacity-50"
          aria-label={`Run ${tool.name}`}
        >
          {isRunning ? (
            <div className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
          ) : (
            <Play className="w-3 h-3" />
          )}
          Run
        </button>
      </div>

      {/* Description */}
      <p className="text-[10px] text-surface-500 mb-1.5 pl-3.5 leading-relaxed">{tool.description}</p>

      {/* URL preview */}
      <div className="pl-3.5 mb-2">
        <span className="text-[9px] font-mono text-surface-600 break-all">{resolveUrl(tool, params)}</span>
      </div>

      {/* Params */}
      {tool.params.length > 0 && (
        <div className="pl-3.5 space-y-2">
          {tool.params.map((param) => (
            <div key={param.name}>
              <label className="flex items-center gap-1.5 mb-1">
                <span className="text-[10px] font-mono text-surface-400">{param.name}</span>
                {param.required && <span className="text-[9px] text-red-400">required</span>}
                {param.type === "number" && <span className="text-[9px] text-surface-600">number</span>}
              </label>
              {param.options ? (
                <select
                  name={param.name}
                  value={params[param.name] ?? param.default ?? ""}
                  onChange={(e) => setParams((prev) => ({ ...prev, [param.name]: e.target.value }))}
                  className="w-full px-2.5 py-1 rounded bg-surface-800 border border-surface-700/50 text-xs font-mono text-surface-200 focus:outline-none focus:border-brand-500/50 focus:ring-1 focus:ring-brand-500/30"
                >
                  <option value="">— select —</option>
                  {param.options.filter(Boolean).map((opt) => (
                    <option key={opt} value={opt}>{opt}</option>
                  ))}
                </select>
              ) : param.type === "boolean" ? (
                <select
                  name={param.name}
                  value={params[param.name] ?? param.default ?? "true"}
                  onChange={(e) => setParams((prev) => ({ ...prev, [param.name]: e.target.value }))}
                  className="w-full px-2.5 py-1 rounded bg-surface-800 border border-surface-700/50 text-xs font-mono text-surface-200 focus:outline-none focus:border-brand-500/50"
                >
                  <option value="true">true</option>
                  <option value="false">false</option>
                </select>
              ) : (
                <Input
                  name={param.name}
                  type={param.type === "number" ? "number" : "text"}
                  value={params[param.name] ?? ""}
                  onChange={(e) => setParams((prev) => ({ ...prev, [param.name]: e.target.value }))}
                  onKeyDown={(e) => e.key === "Enter" && handleRun()}
                  placeholder={param.default ?? param.description}
                />
              )}
            </div>
          ))}
        </div>
      )}

      {/* Validation errors */}
      {errors.length > 0 && (
        <div data-testid="param-error" role="alert" className="mt-2 pl-3.5 space-y-0.5">
          {errors.map((err) => (
            <p key={err} className="text-[10px] text-red-400">{err}</p>
          ))}
        </div>
      )}
    </div>
  );
}

// ── Command Palette ───────────────────────────────────────────────────────────

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
  onSelectTool: (toolName: string) => void;
}

function CommandPalette({ open, onClose, onSelectTool }: CommandPaletteProps) {
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const filtered = q
    ? TOOLS.filter((t) => t.name.includes(q) || t.description.toLowerCase().includes(q))
    : TOOLS;

  useEffect(() => { if (!open) setQuery(""); }, [open]);

  const handleSelect = (name: string) => {
    onSelectTool(name);
    onClose();
  };

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent data-testid="command-palette" className="p-0 overflow-hidden max-w-md">
        <DialogHeader className="sr-only">
          <DialogTitle>Command Palette — Search MCP Tools</DialogTitle>
        </DialogHeader>
        <div className="flex items-center gap-2 px-3 py-2.5 border-b border-surface-700/50">
          <Search className="w-4 h-4 text-surface-400 flex-shrink-0" />
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search tools by name or description…"
            className="flex-1 bg-transparent text-sm text-surface-200 placeholder:text-surface-500 focus:outline-none font-mono"
            onKeyDown={(e) => {
              if (e.key === "Enter" && filtered.length > 0) handleSelect(filtered[0].name);
            }}
          />
          <kbd className="text-[9px] text-surface-500 border border-surface-700 rounded px-1 py-0.5">Esc</kbd>
        </div>
        <ScrollArea className="max-h-80">
          {filtered.length === 0 ? (
            <p className="px-4 py-8 text-xs text-surface-500 text-center">No tools match "{query}"</p>
          ) : (
            filtered.map((tool) => {
              const cat = CATEGORIES.find((c) => c.id === tool.category);
              return (
                <button
                  key={tool.name}
                  onClick={() => handleSelect(tool.name)}
                  className="w-full flex items-start gap-2.5 px-3 py-2.5 hover:bg-surface-800/60 transition-colors text-left border-b border-surface-800/20 last:border-0"
                >
                  {cat && (
                    <span className={`mt-1 w-1.5 h-1.5 rounded-full flex-shrink-0 ${COLOR_MAP[cat.color].split(" ")[0]}`} />
                  )}
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-mono font-semibold text-surface-100">{tool.name}</p>
                    <p className="text-[10px] text-surface-500 line-clamp-1 mt-0.5">{tool.description}</p>
                  </div>
                  {cat && (
                    <span className={`ml-auto flex-shrink-0 text-[9px] px-1.5 py-0.5 rounded border ${COLOR_MAP[cat.color]}`}>
                      {cat.label}
                    </span>
                  )}
                </button>
              );
            })
          )}
        </ScrollArea>
      </DialogContent>
    </Dialog>
  );
}

// ── Main Component ────────────────────────────────────────────────────────────

export default function McpConsole() {
  const [response, setResponse] = useState<string>("");
  const [status, setStatus] = useState<number | null>(null);
  const [duration, setDuration] = useState<number | null>(null);
  const [executing, setExecuting] = useState(false);
  const [activeTool, setActiveTool] = useState<string | null>(null);
  const [resultCount, setResultCount] = useState<number | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [openCategories, setOpenCategories] = useState<string[]>(["stats"]);
  const toolRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const grouped = toolsByCategory();

  // Global Cmd+K / Ctrl+K shortcut
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

  const execute = useCallback(async (tool: McpTool, params: Record<string, string>) => {
    setExecuting(true);
    setActiveTool(tool.name);
    const start = performance.now();
    try {
      const url = resolveUrl(tool, params);
      const method = tool.method ?? "GET";
      const opts: RequestInit = { method };
      if (method === "POST") {
        opts.headers = { "Content-Type": "application/json" };
        opts.body = JSON.stringify(params);
      }
      const res = await fetch(url, opts);
      const elapsed = Math.round(performance.now() - start);
      setStatus(res.status);
      setDuration(elapsed);

      const ct = res.headers.get("content-type") ?? "";
      let text: string;
      if (ct.includes("json")) {
        const json = await res.json();
        text = JSON.stringify(json, null, 2);
        setResultCount(countResults(json));
      } else {
        text = await res.text();
        setResultCount(null);
      }
      setResponse(text);
      setHistory((prev) => [
        { toolName: tool.name, status: res.status, duration: elapsed, response: text, timestamp: Date.now() },
        ...prev.slice(0, 9),
      ]);
    } catch (err) {
      const elapsed = Math.round(performance.now() - start);
      setStatus(0);
      setDuration(elapsed);
      const text = JSON.stringify({ error: err instanceof Error ? err.message : String(err) }, null, 2);
      setResponse(text);
      setResultCount(null);
      setHistory((prev) => [
        { toolName: tool.name, status: 0, duration: elapsed, response: text, timestamp: Date.now() },
        ...prev.slice(0, 9),
      ]);
    } finally {
      setExecuting(false);
    }
  }, []);

  const handlePaletteSelect = (toolName: string) => {
    const tool = TOOLS.find((t) => t.name === toolName);
    if (!tool) return;
    setOpenCategories((prev) =>
      prev.includes(tool.category) ? prev : [...prev, tool.category]
    );
    setTimeout(() => {
      toolRefs.current[toolName]?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }, 250);
  };

  return (
    <div className="h-full max-w-[1600px] mx-auto flex flex-col gap-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Terminal className="w-5 h-5 text-brand-400" />
          <div>
            <h1 className="text-xl font-bold gradient-text">MCP Inspector</h1>
            <p className="text-[10px] text-surface-400">{TOOLS.length} tools across {CATEGORIES.length} categories</p>
          </div>
        </div>
        <button
          onClick={() => setPaletteOpen(true)}
          className="flex items-center gap-2 px-3 py-1.5 rounded-lg glass-card hover:bg-surface-800/60 text-xs text-surface-400 hover:text-surface-200 transition-colors"
          aria-label="Open command palette"
        >
          <Command className="w-3.5 h-3.5" />
          <span>Search tools</span>
          <kbd className="text-[9px] border border-surface-700 rounded px-1 py-0.5 text-surface-500">⌘K</kbd>
        </button>
      </div>

      {/* Main grid */}
      <div className="grid grid-cols-12 gap-3 flex-1 overflow-hidden" style={{ minHeight: 0 }}>
        {/* Left: Accordion tool browser */}
        <div className="col-span-5 glass-card overflow-hidden flex flex-col" style={{ maxHeight: "calc(100vh - 160px)" }}>
          <ScrollArea className="flex-1">
            <Accordion
              type="multiple"
              value={openCategories}
              onValueChange={setOpenCategories}
            >
              {CATEGORIES.map((cat) => {
                const catTools = grouped[cat.id] ?? [];
                return (
                  <AccordionItem key={cat.id} value={cat.id}>
                    <AccordionTrigger
                      data-testid="tool-category-header"
                    >
                      <div className="flex items-center gap-2">
                        <cat.icon className="w-3.5 h-3.5" />
                        <span>{cat.label}</span>
                        <span className={`text-[9px] px-1.5 py-0.5 rounded border ${COLOR_MAP[cat.color]}`}>
                          {catTools.length}
                        </span>
                      </div>
                    </AccordionTrigger>
                    <AccordionContent>
                      {catTools.map((tool) => (
                        <div
                          key={tool.name}
                          ref={(el) => { toolRefs.current[tool.name] = el; }}
                        >
                          <ToolItem
                            tool={tool}
                            onExecute={execute}
                            executing={executing}
                            activeTool={activeTool}
                          />
                        </div>
                      ))}
                    </AccordionContent>
                  </AccordionItem>
                );
              })}
            </Accordion>
          </ScrollArea>
        </div>

        {/* Right: Response + history */}
        <div className="col-span-7 flex flex-col gap-3 overflow-hidden">
          {/* Response panel */}
          <div className="glass-card flex flex-col overflow-hidden" style={{ flex: history.length ? "1 1 60%" : "1 1 100%", minHeight: 0 }}>
            <div className="px-4 py-2.5 border-b border-surface-800/50 flex items-center gap-3 flex-shrink-0">
              <span className="text-xs font-medium text-surface-400">Response</span>
              {activeTool && (
                <span className="text-[10px] font-mono text-brand-400">{activeTool}</span>
              )}
              {status !== null && (
                <span className={`text-xs font-mono px-2 py-0.5 rounded ${
                  status >= 200 && status < 300
                    ? "bg-emerald-500/10 text-emerald-400"
                    : status >= 400
                    ? "bg-red-500/10 text-red-400"
                    : "bg-amber-500/10 text-amber-400"
                }`}>
                  {status} {status >= 200 && status < 300 ? "OK" : status >= 400 ? "Error" : ""}
                </span>
              )}
              {duration !== null && (
                <span className="text-[10px] text-surface-500 font-mono flex items-center gap-1">
                  <Clock className="w-3 h-3" /> {duration}ms
                </span>
              )}
              {resultCount !== null && (
                <span className="text-[10px] text-surface-500 font-mono">{resultCount} results</span>
              )}
            </div>
            <div data-testid="tool-response" className="flex-1 overflow-auto p-4">
              {!response ? (
                <div className="h-full flex items-center justify-center text-surface-600">
                  <div className="text-center">
                    <Terminal className="w-10 h-10 mx-auto mb-3 opacity-20" />
                    <p className="text-sm">Open a category, fill parameters, click Run</p>
                    <p className="text-xs mt-1 text-surface-700">Or press ⌘K to search tools</p>
                  </div>
                </div>
              ) : (
                <pre className="text-xs font-mono text-surface-300 whitespace-pre-wrap leading-relaxed">{response}</pre>
              )}
            </div>
          </div>

          {/* Execution history */}
          {history.length > 0 && (
            <div className="glass-card overflow-hidden flex-shrink-0" style={{ maxHeight: "180px" }}>
              <div className="px-4 py-2 border-b border-surface-800/50 flex-shrink-0">
                <span className="text-[10px] font-medium text-surface-500 uppercase tracking-wider">
                  History ({history.length})
                </span>
              </div>
              <ScrollArea className="h-32">
                {history.map((entry, i) => (
                  <button
                    key={i}
                    onClick={() => setResponse(entry.response)}
                    className="w-full flex items-center gap-2.5 px-4 py-1.5 hover:bg-surface-800/40 transition-colors text-left border-b border-surface-800/20 last:border-0"
                  >
                    <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded ${
                      entry.status >= 200 && entry.status < 300
                        ? "bg-emerald-500/10 text-emerald-400"
                        : "bg-red-500/10 text-red-400"
                    }`}>{entry.status}</span>
                    <span className="text-[10px] font-mono text-surface-300">{entry.toolName}</span>
                    <span className="text-[10px] text-surface-600 ml-auto font-mono">{entry.duration}ms</span>
                  </button>
                ))}
              </ScrollArea>
            </div>
          )}
        </div>
      </div>

      {/* Command palette */}
      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        onSelectTool={handlePaletteSelect}
      />
    </div>
  );
}
