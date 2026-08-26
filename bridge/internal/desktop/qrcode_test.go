package desktop

import (
	"bytes"
	"image/png"
	"testing"

	"github.com/makiuchi-d/gozxing"
	"github.com/makiuchi-d/gozxing/qrcode"
)

func TestQRCodeRoundTripsInvitation(t *testing.T) {
	invitation := "codex-remote://pair?baseUrl=http%3A%2F%2F100.88.10.20%3A8787&token=secret-value"

	encoded, err := RenderQRCode(invitation)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(encoded, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}) {
		t.Fatal("二维码不是 PNG")
	}
	image, err := png.Decode(bytes.NewReader(encoded))
	if err != nil {
		t.Fatal(err)
	}
	if got := image.Bounds().Size(); got.X != 320 || got.Y != 320 {
		t.Fatalf("二维码尺寸 = %v", got)
	}
	bitmap, err := gozxing.NewBinaryBitmapFromImage(image)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := qrcode.NewQRCodeReader().Decode(bitmap, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := decoded.GetText(); got != invitation {
		t.Fatalf("解码结果 = %q, want = %q", got, invitation)
	}
}

func TestQRCodeRejectsEmptyInvitation(t *testing.T) {
	if _, err := RenderQRCode(""); err == nil {
		t.Fatal("空邀请应被拒绝")
	}
}
