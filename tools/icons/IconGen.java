import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * FlexHarness のアイコン(A案: 縦2本と横1本の紐で H、真ん中に四角いバックル)を PNG に描く。
 *
 * Android は vector(app/src/main/res/drawable/ic_dopa.xml)、Linux のトレイは Compose の Painter で
 * 同じ形を描いているが、ブラウザ拡張と .deb のメニューは PNG しか受け取らない。
 * 画像の道具を入れずに済むよう、JDK だけで描く。ロゴを変えたら3か所とここを揃えること。
 *
 *     java -Djava.awt.headless=true tools/icons/IconGen.java .
 */
public class IconGen {

    private static final Color NAVY = new Color(0x1B1B2F);
    private static final Color GOLD = new Color(0xF5C86B);
    private static final Color PURPLE = new Color(0x7C6BF5);

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        for (int size : new int[] {16, 32, 48, 128}) {
            write(size, new File(root, "extension/icons/icon" + size + ".png"));
        }
        // jpackage がメニューの項目に使う。小さいと拡大されてぼやけるので大きめに
        write(256, new File(root, "linux/icon.png"));
    }

    private static void write(int size, File out) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // 座標は vector と同じ 108 四方で書く
        g.scale(size / 108.0, size / 108.0);

        g.setColor(NAVY);
        g.fill(new RoundRectangle2D.Double(0, 0, 108, 108, 48, 48));

        g.setColor(GOLD);
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(36, 22, 36, 86));
        g.draw(new Line2D.Double(72, 22, 72, 86));
        g.setStroke(new BasicStroke(8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new Line2D.Double(36, 54, 72, 54));

        g.setColor(PURPLE);
        g.setStroke(new BasicStroke(6f));
        g.draw(new RoundRectangle2D.Double(43, 43, 22, 22, 12, 12));

        g.dispose();
        out.getParentFile().mkdirs();
        ImageIO.write(image, "png", out);
    }
}
