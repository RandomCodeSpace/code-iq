package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const mongooseSource = `const mongoose = require('mongoose');
mongoose.connect('mongodb://localhost/test');

const userSchema = new mongoose.Schema({ name: String, email: String });

userSchema.virtual('displayName').get(function() { return this.name; });
userSchema.pre('save', function(next) { next(); });

const User = mongoose.model('User', userSchema);

async function find() {
    return User.findOne({ email: 'x' });
}
`

func TestMongooseORMPositive(t *testing.T) {
	d := NewMongooseORMDetector()
	ctx := &detector.Context{
		FilePath: "src/user.js",
		Language: "javascript",
		Content:  mongooseSource,
	}
	r := d.Detect(ctx)
	var conn, entities, events int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeDatabaseConnection:
			conn++
		case model.NodeEntity:
			entities++
		case model.NodeEvent:
			events++
		}
	}
	if conn != 1 {
		t.Errorf("expected 1 connection, got %d", conn)
	}
	if entities != 2 { // schema + model
		t.Errorf("expected 2 entities, got %d", entities)
	}
	if events != 1 {
		t.Errorf("expected 1 hook event, got %d", events)
	}
}

func TestMongooseORMNegative(t *testing.T) {
	d := NewMongooseORMDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.js", Content: "var x;"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestMongooseORMDeterminism(t *testing.T) {
	d := NewMongooseORMDetector()
	ctx := &detector.Context{FilePath: "src/x.js", Language: "javascript", Content: mongooseSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic at %d", i)
		}
	}
}
