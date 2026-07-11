package resizer

import (
	"bytes"
	"crypto/sha256"
	"fmt"
	"os"
	"testing"
)

func TestDeviceGoldenOutput(t *testing.T) {
	inputPath := os.Getenv("AT2HT_RESIZER_TEST_INPUT")
	wantPath := os.Getenv("AT2HT_RESIZER_TEST_GOLDEN")
	if inputPath == "" || wantPath == "" {
		t.Skip("set AT2HT_RESIZER_TEST_INPUT and AT2HT_RESIZER_TEST_GOLDEN")
	}
	input, err := os.ReadFile(inputPath)
	if err != nil {
		t.Fatal(err)
	}
	want, err := os.ReadFile(wantPath)
	if err != nil {
		t.Fatal(err)
	}
	got, err := ResizeImage(input, 300, 75)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("not bit exact: got bytes=%d sha256=%x; want bytes=%d sha256=%x; first difference=%s",
			len(got), sha256.Sum256(got), len(want), sha256.Sum256(want), firstDifference(got, want))
	}
	t.Logf("bit exact: bytes=%d sha256=%x", len(got), sha256.Sum256(got))
}

func firstDifference(a, b []byte) string {
	limit := len(a)
	if len(b) < limit {
		limit = len(b)
	}
	for i := 0; i < limit; i++ {
		if a[i] != b[i] {
			return fmt.Sprintf("offset %d: got %02x want %02x", i, a[i], b[i])
		}
	}
	return fmt.Sprintf("common prefix %d bytes", limit)
}
