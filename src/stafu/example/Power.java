package stafu.example;

import java.io.Serializable;

public final class Power {
    private Power() { }

    @FunctionalInterface
    private interface IntUnaryOperator extends java.util.function.IntUnaryOperator, Serializable { }
    private static IntUnaryOperator statify(IntUnaryOperator f) {
        return stafu.Statification.statify(f);
    }

    private static int power(int x, int y) {
        if (y == 0) {
            return 1;
        } else if (y == 1) {
            return x;
        } else if (y % 2 == 0) {
            int t = power(x, y / 2);
            return t * t;
        } else {
            return x * power(x, y - 1);
        }
    }

    private static IntUnaryOperator powerGen(IntUnaryOperator x, int y) {
        if (y == 0) {
            return statify(z -> 1);
        } else if (y == 1) {
            return x;
        } else if (y % 2 == 0) {
            IntUnaryOperator t = powerGen(x, y / 2);
            return statify(z -> { int i = t.applyAsInt(z); return i * i; });
        } else {
            IntUnaryOperator t = powerGen(x, y - 1);
            return statify(z -> x.applyAsInt(z) * t.applyAsInt(z));
        }
    }

    public static void main(String[] args) {
        IntUnaryOperator power45;

        power45 = x -> power(x, 45);
        power45 = powerGen(statify((x -> x)), 45);

        int res = 0;
        for (int i = 0; i < 1000000000; i++) {
            res += power45.applyAsInt(i);
        }

        long start;
        long end;

        start = System.currentTimeMillis();
        for (int i = 0; i < 1000000000; i++) {
            res += power45.applyAsInt(i);
        }
        end = System.currentTimeMillis();

        System.out.println(end - start);
        System.out.println(res);
    }
}
