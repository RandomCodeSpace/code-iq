package main

import (
	"fmt"
	"os"

	"github.com/randomcodespace/codeiq/go/internal/analyzer"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: extcheck <path>")
		return
	}
	d := analyzer.NewFileDiscovery()
	files, err := d.Discover(os.Args[1])
	if err != nil {
		fmt.Println("err:", err)
		return
	}
	fmt.Printf("discovered %d files\n", len(files))
	counts := map[string]int{}
	for _, f := range files {
		counts[f.Language.String()]++
	}
	for k, v := range counts {
		fmt.Printf("  %-15s %d\n", k, v)
	}
}
