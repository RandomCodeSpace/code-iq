package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const sequelizeSource = `const { Sequelize, Model, DataTypes } = require('sequelize');
const sequelize = new Sequelize('sqlite::memory:');

const User = sequelize.define('User', {
    name: DataTypes.STRING,
});

class Post extends Model {}
Post.init({ title: DataTypes.STRING }, { sequelize });

User.hasMany(Post);
Post.belongsTo(User);

async function findUsers() {
    return User.findAll({ where: { active: true } });
}
`

func TestSequelizeORMPositive(t *testing.T) {
	d := NewSequelizeORMDetector()
	ctx := &detector.Context{
		FilePath: "src/models.js",
		Language: "javascript",
		Content:  sequelizeSource,
	}
	r := d.Detect(ctx)
	var conn, entities int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeDatabaseConnection:
			conn++
		case model.NodeEntity:
			entities++
		}
	}
	if conn != 1 {
		t.Errorf("expected 1 connection, got %d", conn)
	}
	if entities != 2 {
		t.Errorf("expected 2 entities (User, Post), got %d", entities)
	}
	if len(r.Edges) < 3 {
		t.Errorf("expected at least 3 edges (assoc + query), got %d", len(r.Edges))
	}
}

func TestSequelizeORMNegative(t *testing.T) {
	d := NewSequelizeORMDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.js", Content: "var x = 1;"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestSequelizeORMDeterminism(t *testing.T) {
	d := NewSequelizeORMDetector()
	ctx := &detector.Context{FilePath: "src/x.js", Language: "javascript", Content: sequelizeSource}
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
