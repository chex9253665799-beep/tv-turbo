package ru.chex.tvturbo;

/**
 * Минимальный генератор QR-кода (байтовый режим, коррекция L, версии 1–5 — один блок,
 * до 106 символов: хватает для адреса страницы на ТВ). Без библиотек.
 */
public final class Qr {

    // версия → {всего кодовых слов, слов коррекции} для уровня L (один блок)
    private static final int[][] CAP = {{0, 0}, {26, 7}, {44, 10}, {70, 15}, {100, 20}, {134, 26}};

    /** true = чёрный модуль. */
    public static boolean[][] encode(String text) {
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Exception e) { throw new RuntimeException(e); }
        int ver = 1;
        while (ver <= 5 && (CAP[ver][0] - CAP[ver][1]) * 8 < 4 + 8 + data.length * 8) ver++;
        if (ver > 5) throw new IllegalArgumentException("слишком длинный текст для QR");
        int dataWords = CAP[ver][0] - CAP[ver][1];

        // поток бит: режим 0100, длина 8 бит, данные, терминатор, выравнивание, заполнители
        Bits b = new Bits();
        b.put(4, 4);
        b.put(data.length, 8);
        for (byte x : data) b.put(x & 0xFF, 8);
        b.put(0, Math.min(4, dataWords * 8 - b.n));
        while (b.n % 8 != 0) b.put(0, 1);
        for (int pad = 0xEC; b.n < dataWords * 8; pad ^= 0xEC ^ 0x11) b.put(pad, 8);
        byte[] words = new byte[CAP[ver][0]];
        System.arraycopy(b.bytes(), 0, words, 0, dataWords);
        byte[] ecc = reedSolomon(words, dataWords, CAP[ver][1]);
        System.arraycopy(ecc, 0, words, dataWords, ecc.length);

        int size = ver * 4 + 17;
        boolean[][] m = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];   // служебные модули

        finder(m, fn, 0, 0); finder(m, fn, size - 7, 0); finder(m, fn, 0, size - 7);
        for (int i = 8; i < size - 8; i++) { set(m, fn, 6, i, i % 2 == 0); set(m, fn, i, 6, i % 2 == 0); }
        if (ver >= 2) {
            int c = size - 7;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++)
                set(m, fn, c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        // резерв под формат + тёмный модуль
        for (int i = 0; i < 9; i++) { fn[8][i] = true; fn[i][8] = true; }
        for (int i = 0; i < 8; i++) { fn[8][size - 1 - i] = true; fn[size - 1 - i][8] = true; }
        set(m, fn, 8, size - 8, true);

        // данные зигзагом
        int bit = 0, total = words.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (fn[y][x]) continue;
                    boolean v = bit < total && ((words[bit >>> 3] >> (7 - (bit & 7))) & 1) != 0;
                    bit++;
                    m[y][x] = v ^ ((x + y) % 2 == 0);   // маска 0
                }
            }
        }

        // формат: уровень L (01), маска 0 → 01000
        int fmt = 0b01000;
        int rem = fmt;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((fmt << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) m[i][8] = bitOf(bits, i);
        m[7][8] = bitOf(bits, 6);
        m[8][8] = bitOf(bits, 7);
        m[8][7] = bitOf(bits, 8);
        for (int i = 9; i < 15; i++) m[8][14 - i] = bitOf(bits, i);
        for (int i = 0; i < 8; i++) m[8][size - 1 - i] = bitOf(bits, i);
        for (int i = 8; i < 15; i++) m[size - 15 + i][8] = bitOf(bits, i);
        m[size - 8][8] = true;
        return m;
    }

    /** Картинка для экрана: с белой рамкой. */
    public static android.graphics.Bitmap bitmap(String text, int px) {
        boolean[][] m = encode(text);
        int n = m.length + 8;
        int scale = Math.max(1, px / n);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(n * scale, n * scale, android.graphics.Bitmap.Config.RGB_565);
        bmp.eraseColor(0xFFFFFFFF);
        for (int y = 0; y < m.length; y++)
            for (int x = 0; x < m.length; x++)
                if (m[y][x])
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++)
                            bmp.setPixel((x + 4) * scale + dx, (y + 4) * scale + dy, 0xFF000000);
        return bmp;
    }

    private static boolean bitOf(int v, int i) { return ((v >>> i) & 1) != 0; }

    private static void set(boolean[][] m, boolean[][] fn, int x, int y, boolean v) { m[y][x] = v; fn[y][x] = true; }

    private static void finder(boolean[][] m, boolean[][] fn, int x0, int y0) {
        int size = m.length;
        for (int dy = -1; dy <= 7; dy++)
            for (int dx = -1; dx <= 7; dx++) {
                int x = x0 + dx, y = y0 + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int d = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
                set(m, fn, x, y, d != 2 && d != 4);
            }
    }

    private static byte[] reedSolomon(byte[] words, int n, int degree) {
        int[] gen = new int[degree];
        gen[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                gen[j] = mul(gen[j], root);
                if (j + 1 < degree) gen[j] ^= gen[j + 1];
            }
            root = mul(root, 2);
        }
        int[] res = new int[degree];
        for (int i = 0; i < n; i++) {
            int f = (words[i] & 0xFF) ^ res[0];
            System.arraycopy(res, 1, res, 0, degree - 1);
            res[degree - 1] = 0;
            for (int j = 0; j < degree; j++) res[j] ^= mul(gen[j], f);
        }
        byte[] out = new byte[degree];
        for (int i = 0; i < degree; i++) out[i] = (byte) res[i];
        return out;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static final class Bits {
        byte[] buf = new byte[256];
        int n;
        void put(int v, int len) {
            for (int i = len - 1; i >= 0; i--, n++)
                if (((v >>> i) & 1) != 0) buf[n >>> 3] |= (byte) (0x80 >>> (n & 7));
        }
        byte[] bytes() { return buf; }
    }

    private Qr() { }
}
