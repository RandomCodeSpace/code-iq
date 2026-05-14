package base

import (
	"testing"
)

func TestFindLineNumber(t *testing.T) {
	text := "line1\nline2\nline3\n"
	cases := map[int]int{
		0:  1,
		5:  1, // newline at index 5 still on line 1
		6:  2,
		11: 2,
		12: 3,
		17: 3,
	}
	for offset, want := range cases {
		if got := FindLineNumber(text, offset); got != want {
			t.Errorf("FindLineNumber(_, %d) = %d, want %d", offset, got, want)
		}
	}
}

func TestFindLineNumberEmpty(t *testing.T) {
	if got := FindLineNumber("", 0); got != 1 {
		t.Fatalf("empty input: got %d, want 1", got)
	}
}

func TestFindLineNumberPastEnd(t *testing.T) {
	// Out-of-range offsets clamp to last line — safer than panicking.
	if got := FindLineNumber("a\nb", 99); got != 2 {
		t.Fatalf("past-end: got %d, want 2", got)
	}
}
