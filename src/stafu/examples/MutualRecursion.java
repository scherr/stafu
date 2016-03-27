package stafu.examples;

import stafu.Statification;

import java.io.Serializable;

public final class MutualRecursion {
    private MutualRecursion() { }

    @FunctionalInterface
    private interface IntUnaryOperator extends java.util.function.IntUnaryOperator, Serializable { }
    private static IntUnaryOperator statify(IntUnaryOperator f) {
        return Statification.statify(f);
    }

    private static int isEven(int n) {
        if (n == 0) {
            return 1;
        } else {
            return isOdd(n - 1);
        }
    }
    private static int isOdd(int n) {
        if (n == 0) {
            return 0;
        } else {
            return isEven(n - 1);
        }
    }

    public static void main(String[] args) throws NoSuchFieldException, IllegalAccessException {
        IntUnaryOperator isOdd;

        isOdd = x -> isOdd(x);
        class Temp {
            private final IntUnaryOperator isEven;
            private final IntUnaryOperator isOdd;
            Temp(IntUnaryOperator isEven, IntUnaryOperator isOdd) {
                this.isEven = isEven;
                this.isOdd = isOdd;
            }
        }
        isOdd = Statification.<Temp>fix(s -> {
            IntUnaryOperator e = statify(n -> n == 0 ? 1 : s.get().isOdd.applyAsInt(n - 1));
            IntUnaryOperator o = statify(n -> n == 0 ? 0 : s.get().isEven.applyAsInt(n - 1));
            return new Temp(e, o);
        }).isOdd;
        // This is slow... TODO: fix2 with Function<Tuple<Supplier<F1>, Tuple<Supplier<F2>, Tuple<F1, F2>>


        int res = 0;
        for (int i = 0; i < 1000000000; i++) {
            res += isOdd.applyAsInt(i % 10);
        }

        long start;
        long end;

        start = System.currentTimeMillis();
        for (int i = 0; i < 1000000000; i++) {
            res += isOdd.applyAsInt(i % 10);
        }
        end = System.currentTimeMillis();

        System.out.println(end - start);
        System.out.println(res);
    }
}
