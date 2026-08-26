package desktop

import (
	"context"
	"sync"
)

type backgroundActions struct {
	ctx    context.Context
	mu     sync.Mutex
	closed bool
	wg     sync.WaitGroup
}

func newBackgroundActions(ctx context.Context) *backgroundActions {
	return &backgroundActions{ctx: ctx}
}

func (a *backgroundActions) Go(action func(context.Context)) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.closed {
		return false
	}
	a.wg.Add(1)
	go func() {
		defer a.wg.Done()
		action(a.ctx)
	}()
	return true
}

func (a *backgroundActions) CloseAndWait() {
	a.mu.Lock()
	a.closed = true
	a.mu.Unlock()
	a.wg.Wait()
}
