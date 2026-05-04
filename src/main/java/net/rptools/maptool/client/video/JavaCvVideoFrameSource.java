/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.client.video;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.rptools.maptool.model.Asset;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * {@link VideoFrameSource} backed by FFmpeg via JavaCV. The asset's bytes are materialized to a
 * temp file on open so that the underlying {@link FFmpegFrameGrabber} can seek freely; the file is
 * deleted on {@link #close()}.
 *
 * <p>Not thread-safe across concurrent {@link #frameAt} calls — instances synchronize internally to
 * prevent concurrent grabs from corrupting decoder state, but callers should still treat a source
 * as owned by one consumer.
 */
final class JavaCvVideoFrameSource implements VideoFrameSource {

  private static final double DEFAULT_FRAME_RATE = 30.0;

  private final Path tempFile;
  private final FFmpegFrameGrabber grabber;
  private final Java2DFrameConverter converter;
  private final long durationMicros;
  private final int width;
  private final int height;
  private final double frameRate;
  private boolean closed;

  static JavaCvVideoFrameSource open(Asset asset) throws IOException {
    if (asset.getType() != Asset.Type.VIDEO) {
      throw new IllegalArgumentException("Asset is not a video: " + asset.getType());
    }
    String ext = asset.getExtension();
    if (ext == null || ext.isBlank()) {
      ext = "mp4";
    }
    Path tmp = Files.createTempFile("maptool-video-", "." + ext);
    Files.write(tmp, asset.getData());
    try {
      var grabber = new FFmpegFrameGrabber(tmp.toFile());
      grabber.start();
      return new JavaCvVideoFrameSource(tmp, grabber);
    } catch (FrameGrabber.Exception e) {
      Files.deleteIfExists(tmp);
      throw new IOException("Failed to open video: " + asset.getName(), e);
    }
  }

  private JavaCvVideoFrameSource(Path tempFile, FFmpegFrameGrabber grabber) {
    this.tempFile = tempFile;
    this.grabber = grabber;
    this.converter = new Java2DFrameConverter();
    this.durationMicros = grabber.getLengthInTime();
    this.width = grabber.getImageWidth();
    this.height = grabber.getImageHeight();
    double fr = grabber.getFrameRate();
    this.frameRate = fr > 0 ? fr : DEFAULT_FRAME_RATE;
  }

  @Override
  public synchronized BufferedImage frameAt(long timeMicros) {
    if (closed) {
      throw new IllegalStateException("VideoFrameSource is closed");
    }
    long t = Math.max(0, timeMicros);
    if (durationMicros > 0 && t >= durationMicros) {
      t = durationMicros - 1;
    }
    try {
      grabber.setTimestamp(t);
      Frame frame = grabber.grabImage();
      if (frame == null) {
        return null;
      }
      BufferedImage decoded = converter.convert(frame);
      return decoded == null ? null : deepCopy(decoded);
    } catch (FrameGrabber.Exception e) {
      throw new VideoDecodeException("Failed to grab frame at " + t + "us", e);
    }
  }

  @Override
  public long durationMicros() {
    return durationMicros;
  }

  @Override
  public int width() {
    return width;
  }

  @Override
  public int height() {
    return height;
  }

  @Override
  public double frameRate() {
    return frameRate;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      grabber.stop();
    } catch (FrameGrabber.Exception ignored) {
      // best-effort shutdown
    }
    try {
      grabber.release();
    } catch (FrameGrabber.Exception ignored) {
      // best-effort shutdown
    }
    converter.close();
    try {
      Files.deleteIfExists(tempFile);
    } catch (IOException ignored) {
      // best-effort cleanup
    }
  }

  // Java2DFrameConverter reuses one BufferedImage that aliases the decoder's pixel buffer; copy
  // before handing the frame to the caller so the next grab doesn't mutate it.
  private static BufferedImage deepCopy(BufferedImage src) {
    ColorModel cm = src.getColorModel();
    WritableRaster raster = src.copyData(null);
    return new BufferedImage(cm, raster, cm.isAlphaPremultiplied(), null);
  }
}
