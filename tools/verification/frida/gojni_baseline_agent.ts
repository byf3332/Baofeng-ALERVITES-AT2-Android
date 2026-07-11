import Java from "frida-java-bridge";

rpc.exports.run = function (sourceBase64: string) {
  return new Promise(function (resolve, reject) {
    Java.perform(function () {
      try {
        const Base64 = Java.use('android.util.Base64');
        const BitmapFactory = Java.use('android.graphics.BitmapFactory');
        const Bitmap = Java.use('android.graphics.Bitmap');
        const CompressFormat = Java.use('android.graphics.Bitmap$CompressFormat');
        const Matrix = Java.use('android.graphics.Matrix');
        const ByteArrayInputStream = Java.use('java.io.ByteArrayInputStream');
        const ByteArrayOutputStream = Java.use('java.io.ByteArrayOutputStream');
        const ExifInterface = Java.use('android.media.ExifInterface');
        const Resizer = Java.use('resizer.Resizer');

        const source = Base64.decode(sourceBase64, 0);
        const exif = ExifInterface.$new(ByteArrayInputStream.$new(source));
        const orientation = exif.getAttributeInt('Orientation', 1);
        let bitmap = BitmapFactory.decodeByteArray(source, 0, source.length);
        if (bitmap === null) throw new Error('BitmapFactory.decodeByteArray failed');

        let degrees = 0;
        if (orientation === 3) degrees = 180;
        else if (orientation === 6) degrees = 90;
        else if (orientation === 8) degrees = 270;
        if (degrees !== 0) {
          const matrix = Matrix.$new();
          matrix.postRotate(degrees);
          bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }

        const rotatedOut = ByteArrayOutputStream.$new();
        bitmap.compress(CompressFormat.JPEG.value, 100, rotatedOut);
        const officialInput = rotatedOut.toByteArray();
        const output = Resizer.resizeImage(officialInput, 300, 75);
        resolve({
          orientation: orientation,
          width: bitmap.getWidth(),
          height: bitmap.getHeight(),
          officialInput: Base64.encodeToString(officialInput, 2),
          output: Base64.encodeToString(output, 2)
        });
      } catch (e: any) {
        reject(e.stack || e.toString());
      }
    });
  });
};
