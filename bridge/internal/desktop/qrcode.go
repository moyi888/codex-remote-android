package desktop

import (
	"fmt"

	qrcode "github.com/skip2/go-qrcode"
)

const pairingQRCodeSize = 320

func RenderQRCode(invitation string) ([]byte, error) {
	if invitation == "" {
		return nil, fmt.Errorf("pairing invitation is empty")
	}
	return qrcode.Encode(invitation, qrcode.Medium, pairingQRCodeSize)
}
