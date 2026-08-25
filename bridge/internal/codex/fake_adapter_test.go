package codex

import (
	"context"
	"testing"
)

func TestFakeAdapterStartsTaskAndListsIt(t *testing.T) {
	adapter := NewFakeAdapter()
	thread, err := adapter.StartTask(context.Background(), StartTaskRequest{
		ProjectID: "project-1", Prompt: "检查状态", Model: "gpt-test", Reasoning: "high",
	})
	if err != nil {
		t.Fatal(err)
	}
	threads, err := adapter.ListThreads(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(threads) != 1 || threads[0].ID != thread.ID {
		t.Fatalf("unexpected threads: %+v", threads)
	}
	if !adapter.Capabilities().StartTask || !adapter.Capabilities().Steer {
		t.Fatalf("unexpected capabilities: %+v", adapter.Capabilities())
	}
}
