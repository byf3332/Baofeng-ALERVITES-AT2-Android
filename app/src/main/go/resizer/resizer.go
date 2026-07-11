// Package resizer implements the image transform exported to Android via gomobile.
package resizer

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"

	"github.com/nfnt/resize"
)

// ResizeImage decodes an image, scales it down so its longest edge is at most
// maxSize pixels, and returns JPEG data encoded at quality.
func ResizeImage(imageBytes []byte, maxSize, quality int64) ([]byte, error) {
	if maxSize <= 0 {
		return nil, fmt.Errorf("maxSize must be positive")
	}
	if quality < 1 || quality > 100 {
		return nil, fmt.Errorf("quality must be between 1 and 100")
	}

	source, _, err := image.Decode(bytes.NewReader(imageBytes))
	if err != nil {
		return nil, err
	}

	bounds := source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	if width > int(maxSize) || height > int(maxSize) {
		if width >= height {
			source = resize.Resize(uint(maxSize), 0, source, resize.Lanczos3)
		} else {
			source = resize.Resize(0, uint(maxSize), source, resize.Lanczos3)
		}
	}

	var output bytes.Buffer
	if err := jpeg.Encode(&output, source, &jpeg.Options{Quality: int(quality)}); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}
