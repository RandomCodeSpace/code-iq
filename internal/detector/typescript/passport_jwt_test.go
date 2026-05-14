package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const passportSource = `import passport from 'passport';
import { Strategy as JwtStrategy } from 'passport-jwt';
import jwt from 'jsonwebtoken';
import { expressjwt } from 'express-jwt';

passport.use(new JwtStrategy(opts, verify));
passport.use(new GoogleStrategy(opts, verify));

app.get('/protected', passport.authenticate('jwt'), handler);

function verify(token) {
    return jwt.verify(token, secret);
}
`

func TestPassportJwtPositive(t *testing.T) {
	d := NewPassportJwtDetector()
	ctx := &detector.Context{
		FilePath: "src/auth.ts",
		Language: "typescript",
		Content:  passportSource,
	}
	r := d.Detect(ctx)
	var guards, middleware int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeGuard:
			guards++
		case model.NodeMiddleware:
			middleware++
		}
	}
	if guards != 2 {
		t.Errorf("expected 2 guards, got %d", guards)
	}
	if middleware < 3 {
		t.Errorf("expected at least 3 middleware nodes, got %d", middleware)
	}
}

func TestPassportJwtNegative(t *testing.T) {
	d := NewPassportJwtDetector()
	ctx := &detector.Context{
		FilePath: "src/x.ts",
		Language: "typescript",
		Content:  "const x = 1;",
	}
	if len(d.Detect(ctx).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestPassportJwtDeterminism(t *testing.T) {
	d := NewPassportJwtDetector()
	ctx := &detector.Context{
		FilePath: "src/auth.ts",
		Language: "typescript",
		Content:  passportSource,
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic count")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic at %d", i)
		}
	}
}
