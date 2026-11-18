package ca.carleton.secpipe.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds small ustar streams for tests (no tar binary needed). */
final class ArtifactPromoterTars {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    ArtifactPromoterTars file(String name, String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        entry(name, '0', data, "");
        return this;
    }

    ArtifactPromoterTars symlink(String name, String target) {
        entry(name, '2', new byte[0], target);
        return this;
    }

    ArtifactPromoterTars hardlink(String name, String target) {
        entry(name, '1', new byte[0], target);
        return this;
    }

    ArtifactPromoterTars dir(String name) {
        entry(name, '5', new byte[0], "");
        return this;
    }

    /** A GNU long-name record followed by a regular file whose header name is truncated. */
    ArtifactPromoterTars gnuLongFile(String name, String content) {
        byte[] n = (name + "\0").getBytes(StandardCharsets.UTF_8);
        entry("././@LongLink", 'L', n, "");
        entry(name.substring(0, Math.min(99, name.length())), '0', content.getBytes(StandardCharsets.UTF_8), "");
        return this;
    }

    /** A pax header overriding the next entry's path. */
    ArtifactPromoterTars paxFile(String name, String content) {
        String rec = " path=" + name + "\n";
        int len = rec.length() + 1;
        while (Integer.toString(len).length() + rec.length() != len) {
            len = Integer.toString(len).length() + rec.length();
        }
        entry("PaxHeaders/x", 'x', (len + rec).getBytes(StandardCharsets.UTF_8), "");
        entry("short-name", '0', content.getBytes(StandardCharsets.UTF_8), "");
        return this;
    }

    byte[] bytes() {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        copy.writeBytes(out.toByteArray());
        copy.writeBytes(new byte[1024]);
        return copy.toByteArray();
    }

    private void entry(String name, char type, byte[] data, String link) {
        byte[] h = new byte[512];
        put(h, 0, 100, name);
        put(h, 100, 8, "0000644");
        put(h, 108, 8, "0023421");
        put(h, 116, 8, "0023421");
        put(h, 124, 12, String.format("%011o", data.length));
        put(h, 136, 12, String.format("%011o", 0));
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) type;
        put(h, 157, 100, link);
        put(h, 257, 6, "ustar");
        put(h, 263, 2, "00");
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, 8, String.format("%06o", sum) + "\0 ");
        out.writeBytes(h);
        out.writeBytes(data);
        int pad = (512 - data.length % 512) % 512;
        out.writeBytes(new byte[pad]);
    }

    private static void put(byte[] h, int off, int len, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(len, b.length));
    }
}
