package desktop

import (
	"context"
	"testing"
	"time"
)

func TestBackgroundActionsCancelAndWaitForRunningWork(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	actions := newBackgroundActions(ctx)
	started := make(chan struct{})
	finished := make(chan struct{})
	if !actions.Go(func(actionCtx context.Context) {
		close(started)
		<-actionCtx.Done()
		close(finished)
	}) {
		t.Fatal("退出前应接受后台操作")
	}
	<-started

	cancel()
	actions.CloseAndWait()
	select {
	case <-finished:
	default:
		t.Fatal("CloseAndWait 返回前应等待后台操作退出")
	}
	if actions.Go(func(context.Context) {}) {
		t.Fatal("退出后不应再接受后台操作")
	}
}

func TestBackgroundActionsClosePreventsWaitRace(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	actions := newBackgroundActions(ctx)
	cancel()
	done := make(chan struct{})
	go func() {
		actions.CloseAndWait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("无任务时 CloseAndWait 未及时返回")
	}
	if actions.Go(func(context.Context) { t.Error("关闭后不应执行") }) {
		t.Fatal("关闭后不应注册新操作")
	}
}
