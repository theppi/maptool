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
import java.io.IOException;
import net.rptools.maptool.model.Asset;

/**
 * On-demand source of decoded video frames for a single asset. Implementations are not required to
 * be thread-safe — callers should serialize access to a single {@code VideoFrameSource}.
 *
 * <p>Returned {@link BufferedImage} instances are owned by the caller and safe to retain past the
 * next {@link #frameAt} call.
 */
public interface VideoFrameSource extends AutoCloseable {

  /**
   * Returns the decoded frame at or just before the given playhead position. Returns {@code null}
   * if no frame is available (e.g. seeking past end of stream).
   */
  BufferedImage frameAt(long timeMicros);

  /** Total media duration in microseconds, or {@code 0} if the underlying decoder doesn't know. */
  long durationMicros();

  int width();

  int height();

  /** Nominal frame rate in frames per second; falls back to a sane default if unknown. */
  double frameRate();

  @Override
  void close();

  /**
   * Opens a frame source for the given video asset. The asset's bytes are materialized so that
   * seeking works; the resources are released by {@link #close()}.
   *
   * @throws IllegalArgumentException if the asset is not a video
   * @throws IOException if the decoder can't be initialized
   */
  static VideoFrameSource open(Asset asset) throws IOException {
    return JavaCvVideoFrameSource.open(asset);
  }
}
