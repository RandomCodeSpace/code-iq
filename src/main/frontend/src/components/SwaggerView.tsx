import { useState } from "react";
import { BookOpen, Terminal } from "lucide-react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ScrollArea } from "@/components/ui/scroll-area";
import { TOOLS, CATEGORIES, COLOR_MAP } from "@/lib/mcp-tools";

function McpToolsReference() {
  return (
    <ScrollArea style={{ height: "calc(100vh - 240px)" }}>
      <div className="p-4 space-y-6">
        {CATEGORIES.map((cat) => {
          const catTools = TOOLS.filter((t) => t.category === cat.id);
          if (catTools.length === 0) return null;
          return (
            <section key={cat.id}>
              <div className="flex items-center gap-2 mb-3">
                <cat.icon className="w-4 h-4" />
                <h2 className="text-sm font-semibold text-surface-200">{cat.label}</h2>
                <span className={`text-[10px] px-1.5 py-0.5 rounded border ${COLOR_MAP[cat.color]}`}>
                  {catTools.length} tools
                </span>
              </div>
              <div className="space-y-2">
                {catTools.map((tool) => (
                  <div key={tool.name} className="glass-card p-3 rounded-lg">
                    <div className="flex items-start gap-2 mb-1.5">
                      <span className={`text-[9px] font-mono font-bold px-1.5 py-0.5 rounded flex-shrink-0 mt-0.5 ${
                        (tool.method ?? "GET") === "GET"
                          ? "bg-emerald-500/10 text-emerald-400"
                          : "bg-amber-500/10 text-amber-400"
                      }`}>
                        {tool.method ?? "GET"}
                      </span>
                      <span className="text-xs font-mono font-semibold text-surface-100">{tool.name}</span>
                    </div>
                    <p className="text-[10px] text-surface-400 mb-2 ml-9">{tool.description}</p>
                    {tool.params.length > 0 ? (
                      <div className="ml-9 space-y-1">
                        {tool.params.map((param) => (
                          <div key={param.name} className="flex flex-wrap items-baseline gap-1.5 text-[10px]">
                            <span className="font-mono text-brand-300">{param.name}</span>
                            {param.required && <span className="text-red-400">required</span>}
                            <span className="text-surface-600">{param.type}</span>
                            <span className="text-surface-500">— {param.description}</span>
                            {param.default && (
                              <span className="text-surface-600 font-mono">default: {param.default}</span>
                            )}
                            {param.options && param.options.filter(Boolean).length > 0 && (
                              <span className="text-surface-600">
                                [{param.options.filter(Boolean).join(", ")}]
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="ml-9 text-[10px] text-surface-600 italic">No parameters</p>
                    )}
                  </div>
                ))}
              </div>
            </section>
          );
        })}
      </div>
    </ScrollArea>
  );
}

export default function SwaggerView() {
  const [iframeLoaded, setIframeLoaded] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <BookOpen className="w-5 h-5 text-brand-400" />
        <div>
          <h1 className="text-xl font-bold gradient-text">API Documentation</h1>
          <p className="text-xs text-surface-400 mt-0.5">OpenAPI / Swagger UI + MCP Tools Reference</p>
        </div>
      </div>

      <Tabs defaultValue="swagger">
        <TabsList>
          <TabsTrigger value="swagger">
            <BookOpen className="w-3.5 h-3.5 mr-1.5" />
            Swagger UI
          </TabsTrigger>
          <TabsTrigger value="mcp-ref">
            <Terminal className="w-3.5 h-3.5 mr-1.5" />
            MCP Tools Reference
          </TabsTrigger>
        </TabsList>

        <TabsContent value="swagger" className="mt-3">
          <div className="glass-card overflow-hidden relative" style={{ height: "calc(100vh - 220px)" }}>
            {!iframeLoaded && (
              <div className="absolute inset-0 flex items-center justify-center bg-surface-900/80 z-10">
                <div className="w-8 h-8 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
              </div>
            )}
            <iframe
              src="/swagger-ui/index.html"
              className="w-full h-full border-0"
              title="Swagger UI"
              sandbox="allow-scripts allow-same-origin allow-forms"
              onLoad={() => setIframeLoaded(true)}
            />
          </div>
        </TabsContent>

        <TabsContent value="mcp-ref" className="mt-3">
          <div className="glass-card overflow-hidden">
            <div className="px-4 py-2.5 border-b border-surface-800/50 flex items-center gap-2">
              <Terminal className="w-4 h-4 text-brand-400" />
              <span className="text-xs font-medium text-surface-300">
                {TOOLS.length} MCP Tools — {CATEGORIES.length} Categories
              </span>
            </div>
            <McpToolsReference />
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}
