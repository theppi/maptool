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
package net.rptools.maptool.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.tika.mime.MediaType;
import org.junit.jupiter.api.Test;

class VideoAssetTest {

  @Test
  void videoMediaTypeMapsToVideoAssetType() {
    assertEquals(Asset.Type.VIDEO, Asset.Type.fromMediaType(new MediaType("video", "mp4")));
    assertEquals(Asset.Type.VIDEO, Asset.Type.fromMediaType(new MediaType("video", "webm")));
    assertEquals(Asset.Type.VIDEO, Asset.Type.fromMediaType(new MediaType("video", "quicktime")));
  }

  @Test
  void createVideoAssetDerivesExtensionFromFilename() {
    Asset mp4 = Asset.createVideoAsset("clip.mp4", new byte[] {1, 2, 3});
    assertEquals(Asset.Type.VIDEO, mp4.getType());
    assertEquals("mp4", mp4.getExtension());

    Asset webm = Asset.createVideoAsset("clip.WebM", new byte[] {1, 2, 3});
    assertEquals("webm", webm.getExtension());

    Asset noext = Asset.createVideoAsset("clip", new byte[] {1, 2, 3});
    assertEquals("mp4", noext.getExtension());
  }

  @Test
  void videoAssetRoundTripsThroughDto() {
    Asset original = Asset.createVideoAsset("clip.mp4", new byte[] {1, 2, 3, 4, 5});
    Asset restored = Asset.fromDto(original.toDto());
    assertEquals(Asset.Type.VIDEO, restored.getType());
    assertEquals(original.getMD5Key(), restored.getMD5Key());
    assertEquals("mp4", restored.getExtension());
  }
}
