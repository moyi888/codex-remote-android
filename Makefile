.PHONY: test-bridge build-bridge

test-bridge:
	cd bridge && mise exec go@1.24.2 -- go test ./...

build-bridge:
	cd bridge && mise exec go@1.24.2 -- go build -o codex-remote.exe ./cmd/codex-remote
