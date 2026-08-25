package codex

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"sync"
)

type rpcRequest struct {
	JSONRPC string `json:"jsonrpc"`
	ID      uint64 `json:"id"`
	Method  string `json:"method"`
	Params  any    `json:"params,omitempty"`
}

type rpcResponse struct {
	ID     uint64          `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

type rpcMessage struct {
	ID     *uint64         `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

type Notification struct {
	Method string
	Params json.RawMessage
}

type RPCProcess struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	scan   *bufio.Scanner
	mu     sync.Mutex
	nextID uint64
	notify chan Notification
}

func StartRPCProcess(ctx context.Context, command string, args, environment []string) (*RPCProcess, error) {
	cmd := exec.CommandContext(ctx, command, args...)
	cmd.Env = append(cmd.Environ(), environment...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	return &RPCProcess{
		cmd: cmd, stdin: stdin, scan: bufio.NewScanner(stdout), notify: make(chan Notification, 64),
	}, nil
}

func (p *RPCProcess) Notifications() <-chan Notification { return p.notify }

func (p *RPCProcess) Notify(_ context.Context, method string, params any) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	message := struct {
		JSONRPC string `json:"jsonrpc"`
		Method  string `json:"method"`
		Params  any    `json:"params,omitempty"`
	}{JSONRPC: "2.0", Method: method, Params: params}
	encoded, err := json.Marshal(message)
	if err != nil {
		return err
	}
	_, err = p.stdin.Write(append(encoded, '\n'))
	return err
}

func (p *RPCProcess) Call(ctx context.Context, method string, params, result any) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.nextID++
	request := rpcRequest{JSONRPC: "2.0", ID: p.nextID, Method: method, Params: params}
	encoded, err := json.Marshal(request)
	if err != nil {
		return err
	}
	if _, err := p.stdin.Write(append(encoded, '\n')); err != nil {
		return err
	}

	type scanResult struct {
		message rpcMessage
		err     error
	}
	responseChannel := make(chan scanResult, 1)
	go func() {
		for p.scan.Scan() {
			var message rpcMessage
			if err := json.Unmarshal(p.scan.Bytes(), &message); err != nil {
				responseChannel <- scanResult{err: err}
				return
			}
			if message.Method != "" {
				select {
				case p.notify <- Notification{Method: message.Method, Params: message.Params}:
				default:
				}
				continue
			}
			if message.ID == nil || *message.ID != request.ID {
				continue
			}
			responseChannel <- scanResult{message: message}
			return
		}
		responseChannel <- scanResult{err: fmt.Errorf("app-server output closed: %w", p.scan.Err())}
	}()

	select {
	case <-ctx.Done():
		if p.cmd.Process != nil {
			_ = p.cmd.Process.Kill()
		}
		return ctx.Err()
	case scanned := <-responseChannel:
		if scanned.err != nil {
			return scanned.err
		}
		if scanned.message.Error != nil {
			return fmt.Errorf("app-server error %d: %s", scanned.message.Error.Code, scanned.message.Error.Message)
		}
		return json.Unmarshal(scanned.message.Result, result)
	}
}

func (p *RPCProcess) Close() error {
	_ = p.stdin.Close()
	if p.cmd.Process != nil {
		_ = p.cmd.Process.Kill()
	}
	return p.cmd.Wait()
}
