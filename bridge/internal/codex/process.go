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
	ID     uint64 `json:"id"`
	Method string `json:"method"`
	Params any    `json:"params,omitempty"`
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
	ID     *uint64
	Method string
	Params json.RawMessage
}

type scanResult struct {
	message rpcMessage
	err     error
}

type RPCProcess struct {
	cmd          *exec.Cmd
	stdin        io.WriteCloser
	scan         *bufio.Scanner
	callMu       sync.Mutex
	writeMu      sync.Mutex
	nextID       uint64
	notify       chan Notification
	responses    chan scanResult
	done         chan error
	waitComplete chan struct{}
	closeOnce    sync.Once
}

func StartRPCProcess(ctx context.Context, command string, args, environment []string) (*RPCProcess, error) {
	cmd := exec.CommandContext(ctx, command, args...)
	configureChildProcess(cmd)
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
	process := &RPCProcess{
		cmd: cmd, stdin: stdin, scan: bufio.NewScanner(stdout), notify: make(chan Notification, 64),
		responses: make(chan scanResult, 64), done: make(chan error, 1), waitComplete: make(chan struct{}),
	}
	go process.readLoop()
	go func() {
		err := cmd.Wait()
		process.done <- err
		close(process.waitComplete)
	}()
	return process, nil
}

func (p *RPCProcess) readLoop() {
	defer close(p.notify)
	for p.scan.Scan() {
		var message rpcMessage
		if err := json.Unmarshal(p.scan.Bytes(), &message); err != nil {
			p.responses <- scanResult{err: err}
			return
		}
		if message.Method != "" {
			if message.Method == "mcpServer/elicitation/request" && message.ID != nil {
				if err := p.respond(*message.ID, map[string]any{
					"action": "cancel", "content": nil,
				}); err != nil {
					p.responses <- scanResult{err: err}
					return
				}
			}
			select {
			case p.notify <- Notification{ID: message.ID, Method: message.Method, Params: message.Params}:
			default:
			}
			continue
		}
		if message.ID != nil {
			p.responses <- scanResult{message: message}
		}
	}
	p.responses <- scanResult{err: fmt.Errorf("app-server output closed: %w", p.scan.Err())}
}

func (p *RPCProcess) Notifications() <-chan Notification { return p.notify }

func (p *RPCProcess) Done() <-chan error { return p.done }

func (p *RPCProcess) Notify(_ context.Context, method string, params any) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	message := struct {
		Method string `json:"method"`
		Params any    `json:"params,omitempty"`
	}{Method: method, Params: params}
	encoded, err := json.Marshal(message)
	if err != nil {
		return err
	}
	_, err = p.stdin.Write(append(encoded, '\n'))
	return err
}

func (p *RPCProcess) respond(id uint64, result any) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	message := struct {
		ID     uint64 `json:"id"`
		Result any    `json:"result"`
	}{ID: id, Result: result}
	encoded, err := json.Marshal(message)
	if err != nil {
		return err
	}
	_, err = p.stdin.Write(append(encoded, '\n'))
	return err
}

func (p *RPCProcess) Call(ctx context.Context, method string, params, result any) error {
	p.callMu.Lock()
	defer p.callMu.Unlock()
	p.nextID++
	request := rpcRequest{ID: p.nextID, Method: method, Params: params}
	encoded, err := json.Marshal(request)
	if err != nil {
		return err
	}
	p.writeMu.Lock()
	if _, err := p.stdin.Write(append(encoded, '\n')); err != nil {
		p.writeMu.Unlock()
		return err
	}
	p.writeMu.Unlock()

	for {
		select {
		case <-ctx.Done():
			if p.cmd.Process != nil {
				_ = p.cmd.Process.Kill()
			}
			return ctx.Err()
		case scanned := <-p.responses:
			if scanned.err != nil {
				return scanned.err
			}
			if scanned.message.ID == nil || *scanned.message.ID != request.ID {
				continue
			}
			if scanned.message.Error != nil {
				return fmt.Errorf("app-server error %d: %s", scanned.message.Error.Code, scanned.message.Error.Message)
			}
			return json.Unmarshal(scanned.message.Result, result)
		}
	}
}

func (p *RPCProcess) Close() error {
	p.closeOnce.Do(func() {
		_ = p.stdin.Close()
		if p.cmd.Process != nil {
			_ = p.cmd.Process.Kill()
		}
	})
	if p.waitComplete != nil {
		<-p.waitComplete
	}
	return nil
}
