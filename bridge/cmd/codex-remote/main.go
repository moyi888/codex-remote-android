package main

import (
	"fmt"
	"os"
)

var version = "dev"

func versionText(value string) string {
	return "codex-remote " + value
}

func main() {
	if len(os.Args) == 2 && os.Args[1] == "version" {
		fmt.Println(versionText(version))
		return
	}

	fmt.Fprintln(os.Stderr, "usage: codex-remote version")
	os.Exit(2)
}
