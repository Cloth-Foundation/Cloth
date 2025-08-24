package diagnostics

import (
	"compiler/src/parser"
	"compiler/src/tokens"
	"fmt"
	"strconv"
	"strings"
)

const (
	clrRed   = "\x1b[31m"
	clrBold  = "\x1b[1m"
	clrDim   = "\x1b[2m"
	clrBlue  = "\x1b[34m"
	clrMag   = "\x1b[35m"
	clrGray  = "\x1b[90m"
	clrUnder = "\x1b[4m"
	clrReset = "\x1b[0m"
)

var (
	vRed   = clrRed
	vBold  = clrBold
	vDim   = clrDim
	vBlue  = clrBlue
	vMag   = clrMag
	vGray  = clrGray
	vUnder = clrUnder
	vReset = clrReset
)

func SetColorsEnabled(enabled bool) {
	if enabled {
		vRed, vBold, vDim, vBlue, vMag, vGray, vUnder, vReset = clrRed, clrBold, clrDim, clrBlue, clrMag, clrGray, clrUnder, clrReset
		return
	}
	vRed, vBold, vDim, vBlue, vMag, vGray, vUnder, vReset = "", "", "", "", "", "", "", ""
}

func RenderParseErrors(path string, source string, errs []error) {
	lines := splitLines(source)
	for _, e := range errs {
		if pe, ok := e.(parser.ParseError); ok {
			printParseError(pe, lines)
		} else {
			fmt.Println("error:", e.Error())
		}
	}
}

func RenderMessages(title string, msgs []string) {
	if len(msgs) == 0 {
		return
	}
	fmt.Println(title + ":")
	for _, m := range msgs {
		fmt.Println(" -", m)
	}
}

func printParseError(pe parser.ParseError, lines []string) {
	sp := pe.Span
	// heading
	fmt.Printf("%s%sx%s %s\n", vRed, vBold, vReset, pe.Message)
	// link line in blue and underlined, bracketed
	link := fmt.Sprintf("[%s:%d:%d]", sp.File, sp.StartLine, sp.StartColumn)
	fmt.Printf("  %s%s%s%s\n", vBlue, vUnder, link, vReset)
	printSpanStyled(lines, sp, pe.Message)
	if strings.TrimSpace(pe.Hint) != "" {
		fmt.Printf("\n%shelp:%s %s\n\n", vBlue, vReset, pe.Hint)
	}
}

func formatFileLoc(sp tokens.TokenSpan) string {
	return fmt.Sprintf("%s:%d:%d", sp.File, sp.StartLine, sp.StartColumn)
}

func underline(startCol, endCol, lineLen int) string {
	if lineLen < 1 {
		lineLen = 1
	}
	start := clamp(startCol, 1, lineLen)
	end := endCol
	if end < start {
		end = start
	}
	lim := lineLen + 1
	if end > lim {
		end = lim
	}
	spaces := strings.Repeat(" ", start-1)
	carets := strings.Repeat("^", max(1, end-start))
	return spaces + vMag + carets + vReset
}

func printSpanStyled(lines []string, sp tokens.TokenSpan, label string) {
	startLine := clamp(int(sp.StartLine), 1, len(lines))
	endLine := clamp(int(sp.EndLine), startLine, len(lines))
	startCol := int(sp.StartColumn)
	endCol := int(sp.EndColumn)

	gutterNum, gutterEmpty := makeGutters(startLine, endLine)

	if startLine == endLine {
		line := lines[startLine-1]
		fmt.Println(gutterNum(startLine) + line)
		u := underline(startCol, endCol, len(line))
		fmt.Println(gutterEmpty() + u + "  " + vMag + label + vReset)
		return
	}
	// First line with opening corner
	first := lines[startLine-1]
	fmt.Println(gutterNum(startLine) + first)
	firstPrefix := strings.Repeat(" ", max(0, startCol-1))
	firstTrail := strings.Repeat("─", max(0, len(first)-startCol+1))
	fmt.Println(gutterEmpty() + firstPrefix + vMag + "╭" + firstTrail + vReset)
	// Middles with vertical bar
	for ln := startLine + 1; ln < endLine; ln++ {
		mid := lines[ln-1]
		fmt.Println(gutterNum(ln) + mid)
		midPrefix := strings.Repeat(" ", max(0, startCol-1))
		fmt.Println(gutterEmpty() + midPrefix + vMag + "│" + vReset)
	}
	// Last line with closing corner and label
	last := lines[endLine-1]
	fmt.Println(gutterNum(endLine) + last)
	lastPrefix := strings.Repeat(" ", max(0, startCol-1))
	lastLineLen := len(last)
	spanLen := max(0, min(endCol-1, lastLineLen)-(startCol-1))
	lastTrail := strings.Repeat("─", spanLen)
	fmt.Println(gutterEmpty() + lastPrefix + vMag + "╰" + lastTrail + "  " + label + vReset)
}

func makeGutters(startLine, endLine int) (func(int) string, func() string) {
	width := len(strconv.Itoa(endLine))
	if w2 := len(strconv.Itoa(startLine)); w2 > width {
		width = w2
	}
	pipe := vGray + "│" + vReset + " "
	gutterNum := func(n int) string {
		return fmt.Sprintf(" %s%*d%s %s", vGray, width, n, vReset, pipe)
	}
	gutterEmpty := func() string {
		return fmt.Sprintf(" %s%*s%s %s", vGray, width, "", vReset, pipe)
	}
	return gutterNum, gutterEmpty
}

func splitLines(src string) []string {
	return strings.Split(src, "\n")
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
