package me.rapierxbox.shellyelevatev2.voice;

// the last n bytes of mic pcm without allocating per chunk
// single writer. snapshot is only called on the writer thread
final class PcmRing {
    private final byte[] buf;
    private int pos;
    private boolean full;

    PcmRing(int bytes) {
        buf = new byte[bytes];
    }

    void write(byte[] src, int len) {
        int off = 0;
        if (len >= buf.length) {
            off = len - buf.length;
            len = buf.length;
        }
        int first = Math.min(len, buf.length - pos);
        System.arraycopy(src, off, buf, pos, first);
        System.arraycopy(src, off + first, buf, 0, len - first);
        int end = pos + len;
        if (end >= buf.length) full = true;
        pos = end % buf.length;
    }

    // oldest first
    byte[] snapshot() {
        if (!full) {
            byte[] out = new byte[pos];
            System.arraycopy(buf, 0, out, 0, pos);
            return out;
        }
        byte[] out = new byte[buf.length];
        System.arraycopy(buf, pos, out, 0, buf.length - pos);
        System.arraycopy(buf, 0, out, buf.length - pos, pos);
        return out;
    }

    void clear() {
        pos = 0;
        full = false;
    }
}
