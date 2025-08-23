package main

import (
	"compiler/src/lexer"
	"compiler/src/tokens"
	"fmt"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("Usage: loom <file>")
		return
	}
	path := os.Args[1]
	data, err := os.ReadFile(path)
	if err != nil {
		fmt.Printf("failed to read %s: %v\n", path, err)
		return
	}
	lx := lexer.New(string(data), path)
	i := 1
	for tok := lx.Next(); tok.Type != tokens.TokenEndOfFile; tok = lx.Next() {
		fmt.Printf("\n[#%d]\n", i)
		fmt.Printf("%s\n", tok)
		i++
	}
}
