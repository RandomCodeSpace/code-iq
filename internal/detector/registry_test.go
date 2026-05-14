package detector

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/model"
)

type fakeDetector struct {
	name string
	lang string
}

func (f fakeDetector) Name() string                        { return f.name }
func (f fakeDetector) SupportedLanguages() []string        { return []string{f.lang} }
func (f fakeDetector) DefaultConfidence() model.Confidence { return model.ConfidenceLexical }
func (f fakeDetector) Detect(*Context) *Result             { return EmptyResult() }

func TestRegistryRegisterAndFor(t *testing.T) {
	r := NewRegistry()
	a := fakeDetector{"a", "java"}
	b := fakeDetector{"b", "python"}
	c := fakeDetector{"c", "java"}
	r.Register(a)
	r.Register(b)
	r.Register(c)

	java := r.For("java")
	if len(java) != 2 {
		t.Fatalf("For(\"java\") len = %d, want 2", len(java))
	}
	py := r.For("python")
	if len(py) != 1 || py[0].Name() != "b" {
		t.Fatalf("For(\"python\") = %+v", py)
	}
	if r.For("rust") != nil {
		t.Fatal("For(\"rust\") should be nil")
	}
}

func TestRegistryDeterministicOrder(t *testing.T) {
	r := NewRegistry()
	r.Register(fakeDetector{"zeta", "java"})
	r.Register(fakeDetector{"alpha", "java"})
	r.Register(fakeDetector{"middle", "java"})
	got := r.For("java")
	want := []string{"alpha", "middle", "zeta"}
	for i, d := range got {
		if d.Name() != want[i] {
			t.Errorf("order[%d] = %q, want %q", i, d.Name(), want[i])
		}
	}
}

func TestRegistryDuplicateNameRejected(t *testing.T) {
	defer func() {
		if r := recover(); r == nil {
			t.Fatal("expected panic on duplicate registration")
		}
	}()
	r := NewRegistry()
	r.Register(fakeDetector{"dup", "java"})
	r.Register(fakeDetector{"dup", "python"})
}

func TestRegistryAll(t *testing.T) {
	r := NewRegistry()
	r.Register(fakeDetector{"d2", "java"})
	r.Register(fakeDetector{"d1", "java"})
	all := r.All()
	if len(all) != 2 || all[0].Name() != "d1" || all[1].Name() != "d2" {
		t.Fatalf("All() order = %+v", all)
	}
}
