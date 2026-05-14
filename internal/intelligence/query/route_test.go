package query

import "testing"

func TestQueryRouteString(t *testing.T) {
	cases := []struct {
		route QueryRoute
		want  string
	}{
		{QueryRouteGraphFirst, "GRAPH_FIRST"},
		{QueryRouteLexicalFirst, "LEXICAL_FIRST"},
		{QueryRouteMerged, "MERGED"},
		{QueryRouteDegraded, "DEGRADED"},
	}
	for _, c := range cases {
		if got := c.route.String(); got != c.want {
			t.Errorf("QueryRoute(%v).String() = %q, want %q", c.route, got, c.want)
		}
	}
}

func TestQueryRouteEmptyStringNotAllowed(t *testing.T) {
	// All declared routes must round-trip to a non-empty identifier so the JSON
	// envelope downstream can be read by humans without a separate lookup.
	for _, r := range []QueryRoute{
		QueryRouteGraphFirst, QueryRouteLexicalFirst, QueryRouteMerged, QueryRouteDegraded,
	} {
		if r.String() == "" {
			t.Fatalf("route %v has empty String", r)
		}
	}
}

func TestQueryTypeIdentifiers(t *testing.T) {
	// Every supported QueryType must carry the exact Java-side identifier so
	// the planner's degradation-note text matches across the two ports.
	cases := map[QueryType]string{
		QueryFindSymbol:       "FIND_SYMBOL",
		QueryFindReferences:   "FIND_REFERENCES",
		QueryFindCallers:      "FIND_CALLERS",
		QueryFindDependencies: "FIND_DEPENDENCIES",
		QuerySearchText:       "SEARCH_TEXT",
		QueryFindConfig:       "FIND_CONFIG",
	}
	for qt, want := range cases {
		if string(qt) != want {
			t.Errorf("QueryType(%v) = %q, want %q", qt, string(qt), want)
		}
	}
}
