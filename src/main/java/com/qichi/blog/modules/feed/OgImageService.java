package com.qichi.blog.modules.feed;

import com.qichi.blog.modules.post.PostDtos;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Draws the share card with Java2D rather than a JS image library.
 *
 * <p>The reason is fonts: Satori and friends ship latin-only faces, and a Chinese title would come
 * out as tofu boxes. Java2D can use whatever CJK font the host already has, so the card renders the
 * real title. Colours are derived from the article's accent, which keeps the card on brand.
 */
@Service
public class OgImageService {

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 630;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.SIMPLIFIED_CHINESE);

    private static final String[] CJK_CANDIDATES = {
            "Microsoft YaHei", "微软雅黑", "Noto Sans CJK SC", "Source Han Sans SC",
            "PingFang SC", "Hiragino Sans GB", "SimHei", "Arial Unicode MS"};

    private static final Color PAPER = new Color(243, 242, 239);
    private static final Color INK = new Color(35, 40, 47);
    private static final Color INK_MUTED = new Color(110, 106, 98);

    private final Font regular;
    private final Font semibold;

    public OgImageService() {
        String family = pickFamily();
        this.regular = new Font(family, Font.PLAIN, 28);
        this.semibold = new Font(family, Font.BOLD, 28);
    }

    public byte[] render(PostDtos.PostDetailVO post, String siteName, String author, String accent) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            Color tint = accentColor(accent);
            paintBackground(g, tint);
            paintCorner(g, tint, post.slug());

            int pad = 76;
            g.setColor(tint);
            g.fillRoundRect(pad, 74, 56, 6, 6, 6);

            g.setFont(semibold.deriveFont(24f));
            g.setColor(INK);
            g.drawString(siteName, pad, 132);

            g.setFont(regular.deriveFont(20f));
            g.setColor(INK_MUTED);
            g.drawString("QICHI", pad + textWidth(g, siteName) + 18, 132);

            int titleTop = 214;
            g.setFont(semibold.deriveFont(56f));
            g.setColor(INK);
            List<String> lines = wrap(g, post.title(), WIDTH - pad * 2, 3);
            int y = titleTop;
            for (String line : lines) {
                g.drawString(line, pad, y);
                y += 74;
            }

            int metaY = HEIGHT - 96;
            g.setFont(regular.deriveFont(22f));
            g.setColor(INK_MUTED);
            StringBuilder meta = new StringBuilder();
            if (post.category() != null) meta.append(post.category().name()).append("  ·  ");
            if (post.publishedAt() != null) meta.append(DATE.format(post.publishedAt())).append("  ·  ");
            meta.append("阅读 ").append(post.readingMinutes()).append(" 分钟");
            g.drawString(meta.toString(), pad, metaY);

            g.setColor(INK);
            g.setFont(semibold.deriveFont(22f));
            g.drawString(author, pad, metaY + 42);

            g.setColor(tint);
            g.setStroke(new BasicStroke(3f));
            g.drawLine(pad, metaY + 62, WIDTH - pad, metaY + 62);
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成分享图", ex);
        }
    }

    private void paintBackground(Graphics2D g, Color tint) {
        g.setColor(PAPER);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.20f));
        g.setColor(tint);
        g.fill(new Ellipse2D.Double(WIDTH * 0.52, -HEIGHT * 0.42, WIDTH * 0.86, WIDTH * 0.86));
        g.setColor(lighten(tint, 0.45f));
        g.fill(new Ellipse2D.Double(-WIDTH * 0.16, HEIGHT * 0.42, WIDTH * 0.78, WIDTH * 0.78));
        g.setComposite(AlphaComposite.SrcOver);
    }

    /** Deterministic arc motif, so every article gets a different but repeatable card. */
    private void paintCorner(Graphics2D g, Color tint, String slug) {
        Random random = new Random(slug == null ? 7 : slug.hashCode());
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
        g.setColor(tint);
        g.setStroke(new BasicStroke(2f));
        for (int i = 0; i < 3; i++) {
            double radius = 220 + i * 130 + random.nextInt(60);
            double cx = WIDTH * (0.78 + random.nextDouble() * 0.12);
            double cy = HEIGHT * (0.22 + random.nextDouble() * 0.3);
            g.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private List<String> wrap(Graphics2D g, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String candidate = current.toString() + ch;
            if (textWidth(g, candidate) > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
                if (lines.size() == maxLines) break;
            }
            current.append(ch);
        }
        if (lines.size() < maxLines && current.length() > 0) lines.add(current.toString());
        if (lines.size() == maxLines) {
            String last = lines.get(maxLines - 1);
            if (last.length() > 1) lines.set(maxLines - 1, last.substring(0, last.length() - 1) + "…");
        }
        return lines;
    }

    private int textWidth(Graphics2D g, String text) {
        return g.getFontMetrics().stringWidth(text);
    }

    private Color accentColor(String accent) {
        if (accent == null) return new Color(110, 131, 120);
        return switch (accent) {
            case "clay" -> new Color(162, 132, 111);
            case "dust" -> new Color(168, 140, 138);
            case "haze" -> new Color(123, 132, 150);
            case "sand" -> new Color(179, 161, 132);
            default -> new Color(110, 131, 120);
        };
    }

    private Color lighten(Color color, float amount) {
        int red = Math.min(255, (int) (color.getRed() + (255 - color.getRed()) * amount));
        int green = Math.min(255, (int) (color.getGreen() + (255 - color.getGreen()) * amount));
        int blue = Math.min(255, (int) (color.getBlue() + (255 - color.getBlue()) * amount));
        return new Color(red, green, blue);
    }

    private String pickFamily() {
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String candidate : CJK_CANDIDATES) {
            for (String family : available) {
                if (family.equalsIgnoreCase(candidate)) return family;
            }
        }
        return Font.SANS_SERIF;
    }
}
