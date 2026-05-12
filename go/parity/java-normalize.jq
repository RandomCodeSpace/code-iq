# Project Java `codeiq graph -f json` output onto the same shape Go's
# parity.Normalize produces: array of { path, language, nodes, edges }
# grouped by file_path, sorted by path then kind+id.
#
# The Java side emits a top-level object like
#   { "nodes": [...], "edges": [...] }
# where each node has filePath / kind / id / label / properties / confidence
# and each edge has kind / sourceId / targetId / properties / confidence.
# We invert this into per-file groups so structural diff against the Go side
# (which writes per-file cache entries) is meaningful.

def sort_nodes: sort_by(.kind, .id);
def sort_edges: sort_by(.kind, .sourceId, .targetId);

# Group nodes by file_path → list of { path, nodes, edges }.
(.nodes | group_by(.filePath // "")) as $node_groups |
($node_groups | map({
    path:     (.[0].filePath // ""),
    language: (.[0].properties.language // ""),
    nodes:    ([.[] | {
                  id, kind, label,
                  fqn:        (.fqn // ""),
                  module:     (.module // ""),
                  file_path:  (.filePath // ""),
                  line_start: (.lineStart // 0),
                  line_end:   (.lineEnd // 0),
                  layer:      (.layer // "unknown"),
                  confidence: (.confidence // "LEXICAL"),
                  source:     (.source // ""),
                  annotations: (.annotations // []),
                  properties: (.properties // {})
              }] | sort_nodes),
    edges:    []
})) as $by_path |

# Attach edges to their source file's group.
reduce (.edges[]) as $e ($by_path;
    # find the path whose nodes contain $e.sourceId
    . as $groups |
    ($groups | to_entries
            | map(select(.value.nodes | any(.id == $e.sourceId)))
            | .[0].key) as $idx |
    if $idx == null then .
    else
      .[$idx].edges += [{
        id:         $e.id,
        kind:       $e.kind,
        source_id:  $e.sourceId,
        target_id:  $e.targetId,
        confidence: ($e.confidence // "LEXICAL"),
        source:     ($e.source // ""),
        properties: ($e.properties // {})
      }]
    end)
| map(.edges |= sort_edges)
| sort_by(.path)
