# Image resizer verification

This directory contains every test asset required to validate the open-source
`libgojni.so` against the original Android library.

## Included assets

- `fixtures/no_network_test_image_2.jpg`: fixed source image.
- `golden/android_jpeg_q100.jpg`: the Android image pipeline's intermediate JPEG.
- `golden/original_libgojni_output.jpg`: original library output.
- `golden/baseline.json`: byte lengths and SHA-256 values.
- `frida/`: Python capture controller and a precompiled Frida 17 Java agent.

## Host-side Go test

After configuring `app/src/main/go/toolchain.properties`, run:

```powershell
.\tools\verification\verify-gojni-host.ps1
```

This feeds the checked-in Android JPEG-100 intermediate to the Go source and
compares its final JPEG byte-for-byte against the checked-in original output.

## Device end-to-end test

1. Install the app version to test, start it manually, and keep it running.
   The provided Frida transport may not support spawning this app.
2. Ensure `frida-server` runs on the rooted device and the Python environment
   has the `frida` package.
3. Capture the app's full Android-plus-JNI output:

```powershell
$venv = 'D:\Amateur Radio\HandheldRadio\at2\at2'
$project = 'D:\MyProjects\AndroidStudioProjects\AT2HT'

& "$venv\Scripts\python.exe" "$project\tools\verification\frida\capture_gojni_baseline.py" `
  "$project\tools\verification\fixtures\no_network_test_image_2.jpg" `
  --out-dir "$project\tools\verification\captures\current"
```

4. Compare the capture with the original-library golden files:

```powershell
& "$project\tools\verification\compare-frida-output.ps1" `
  -CaptureDirectory "$project\tools\verification\captures\current"
```

The comparison checks both the Android JPEG quality-100 intermediate and final
JPEG. It reports success only if both files are byte-for-byte identical.

## Updating the Frida agent

The checked-in `gojni_baseline_agent.js` is ready to use. Only after modifying
the TypeScript source do you need `frida-compile` and `frida-java-bridge`:

```powershell
cd .\tools\verification\frida
frida-compile .\gojni_baseline_agent.ts -o .\gojni_baseline_agent.js
```
