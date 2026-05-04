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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.rptools.maptool.model.Asset;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VideoFrameSourceTest {

  private static final int CLIP_WIDTH = 64;
  private static final int CLIP_HEIGHT = 48;
  private static final int CLIP_FRAME_COUNT = 10;
  private static final int CLIP_FPS = 10;

  private static byte[] clipBytes;

  @BeforeAll
  static void synthesizeClip() throws Exception {
    Path tmp = Files.createTempFile("maptool-test-clip-", ".mp4");
    try (FFmpegFrameRecorder recorder =
        new FFmpegFrameRecorder(tmp.toFile(), CLIP_WIDTH, CLIP_HEIGHT)) {
      recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
      recorder.setFormat("mp4");
      recorder.setFrameRate(CLIP_FPS);
      recorder.start();
      try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
        BufferedImage img = new BufferedImage(CLIP_WIDTH, CLIP_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
          for (int i = 0; i < CLIP_FRAME_COUNT; i++) {
            g.setColor(new Color(i * 25 % 256, 0, 0));
            g.fillRect(0, 0, CLIP_WIDTH, CLIP_HEIGHT);
            recorder.record(converter.convert(img));
          }
        } finally {
          g.dispose();
        }
      }
      recorder.stop();
    }
    clipBytes = Files.readAllBytes(tmp);
    Files.deleteIfExists(tmp);
  }

  private Asset clipAsset() {
    return Asset.createVideoAsset("clip.mp4", clipBytes);
  }

  @Test
  void opensVideoAndReportsDimensions() throws IOException {
    try (VideoFrameSource source = VideoFrameSource.open(clipAsset())) {
      assertEquals(CLIP_WIDTH, source.width());
      assertEquals(CLIP_HEIGHT, source.height());
      assertTrue(source.durationMicros() > 0, "duration should be positive");
      assertTrue(source.frameRate() > 0, "frame rate should be positive");
    }
  }

  @Test
  void returnsDecodedFrameAtZero() throws IOException {
    try (VideoFrameSource source = VideoFrameSource.open(clipAsset())) {
      BufferedImage frame = source.frameAt(0);
      assertNotNull(frame);
      assertEquals(CLIP_WIDTH, frame.getWidth());
      assertEquals(CLIP_HEIGHT, frame.getHeight());
    }
  }

  @Test
  void framesAreIndependentCopies() throws IOException {
    try (VideoFrameSource source = VideoFrameSource.open(clipAsset())) {
      BufferedImage first = source.frameAt(0);
      BufferedImage second = source.frameAt(500_000); // 0.5s
      assertNotNull(first);
      assertNotNull(second);
      assertNotSame(first, second);
    }
  }

  @Test
  void seekingPastEndDoesNotThrow() throws IOException {
    try (VideoFrameSource source = VideoFrameSource.open(clipAsset())) {
      assertDoesNotThrow(() -> source.frameAt(Long.MAX_VALUE / 2));
    }
  }

  @Test
  void closeIsIdempotent() throws IOException {
    VideoFrameSource source = VideoFrameSource.open(clipAsset());
    source.close();
    assertDoesNotThrow(source::close);
  }

  @Test
  void rejectsNonVideoAsset() {
    Asset notVideo = Asset.createImageAsset("img.png", new byte[] {1, 2, 3});
    assertThrows(IllegalArgumentException.class, () -> VideoFrameSource.open(notVideo));
  }

  @Test
  void cacheReusesSamePerKey() throws IOException {
    try (VideoFrameSourceCache cache = new VideoFrameSourceCache(4)) {
      Asset a = clipAsset();
      VideoFrameSource first = cache.get(a);
      VideoFrameSource second = cache.get(a);
      assertSame(first, second);
      assertEquals(1, cache.size());
    }
  }

  @Test
  void cacheEvictsOldestPastCapacity() throws IOException {
    try (VideoFrameSourceCache cache = new VideoFrameSourceCache(2)) {
      // Three distinct assets — same bytes but different names won't help (MD5 is on bytes).
      // Build distinct byte sequences by appending a marker (FFmpeg ignores trailing junk in mp4).
      Asset a1 = Asset.createVideoAsset("a.mp4", appendMarker(clipBytes, (byte) 1));
      Asset a2 = Asset.createVideoAsset("b.mp4", appendMarker(clipBytes, (byte) 2));
      Asset a3 = Asset.createVideoAsset("c.mp4", appendMarker(clipBytes, (byte) 3));

      VideoFrameSource s1 = cache.get(a1);
      cache.get(a2);
      cache.get(a3);

      assertEquals(2, cache.size());
      // s1 was evicted and closed; opening a1 again should yield a fresh, different instance.
      VideoFrameSource s1Again = cache.get(a1);
      assertNotSame(s1, s1Again);
    }
  }

  private static byte[] appendMarker(byte[] base, byte marker) {
    byte[] out = new byte[base.length + 1];
    System.arraycopy(base, 0, out, 0, base.length);
    out[base.length] = marker;
    return out;
  }
}
