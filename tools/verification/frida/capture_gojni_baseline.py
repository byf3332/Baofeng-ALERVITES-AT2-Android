import argparse
import base64
import hashlib
from pathlib import Path

import frida


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('image', type=Path)
    parser.add_argument('--package', default='com.byf3332.at2ht')
    parser.add_argument('--out-dir', type=Path, required=True)
    args = parser.parse_args()

    source = args.image.read_bytes()
    device = frida.get_usb_device(timeout=10)
    running = next((app for app in device.enumerate_applications() if app.identifier == args.package and app.pid), None)
    if running is not None:
        pid = running.pid
        spawned = False
    else:
        pid = device.spawn([args.package])
        spawned = True
    session = device.attach(pid)
    agent = Path(__file__).with_name('gojni_baseline_agent.js').read_text(encoding='utf-8')
    script = session.create_script(agent)
    script.load()
    if spawned:
        device.resume(pid)
    result = script.exports_sync.run(base64.b64encode(source).decode('ascii'))

    args.out_dir.mkdir(parents=True, exist_ok=True)
    official_input = base64.b64decode(result['officialInput'])
    output = base64.b64decode(result['output'])
    (args.out_dir / 'android_jpeg_q100.jpg').write_bytes(official_input)
    (args.out_dir / 'app_output.jpg').write_bytes(output)
    print(f"orientation={result['orientation']} size={result['width']}x{result['height']}")
    print(f"android_jpeg_q100 bytes={len(official_input)} sha256={sha256(official_input)}")
    print(f"app_output bytes={len(output)} sha256={sha256(output)}")
    session.detach()


if __name__ == '__main__':
    main()
