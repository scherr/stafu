package stafu.example;

import java.io.Serializable;

public final class PseudoQuoting {
    private PseudoQuoting() { }

    @FunctionalInterface
    private interface Function<T, R> extends java.util.function.Function<T, R>, Serializable { }
    private static <T, R> Function<T, R> statify(Function<T, R> f) {
        return stafu.Statification.statify(f);
    }

    @FunctionalInterface
    private interface BiFunction<T, U, R> extends java.util.function.BiFunction<T, U, R>, Serializable { }
    private static <T, U, R> BiFunction<T, U, R> statify(BiFunction<T, U, R> f) {
        return stafu.Statification.statify(f);
    }

    @FunctionalInterface
    private interface IntUnaryOperator extends java.util.function.IntUnaryOperator, Serializable { }
    private static IntUnaryOperator statify(IntUnaryOperator f) {
        return stafu.Statification.statify(f);
    }

    @FunctionalInterface
    private interface IntBinaryOperator extends java.util.function.IntBinaryOperator, Serializable { }
    private static IntBinaryOperator statify(IntBinaryOperator f) {
        return stafu.Statification.statify(f);
    }

    @FunctionalInterface
    private interface ToIntFunction<T> extends java.util.function.ToIntFunction<T>, Serializable { }
    private static <T> ToIntFunction<T> statify2(ToIntFunction<T> f) {
        return stafu.Statification.statify(f);
    }

    private static class Q {
        private final ToIntFunction<int[]> function;

        private Q(ToIntFunction<int[]> function) {
            this.function = function;
        }
    }

    private static Q lift(int i) {
        return new Q(statify2(is -> i));
    }
    private static Q quote(Q q0, IntUnaryOperator f) {
        ToIntFunction<int[]> q0F = q0.function;
        IntUnaryOperator temp = statify(f);
        return new Q(statify2(is -> temp.applyAsInt(q0F.applyAsInt(is))));
    }
    private static Q quote(Q q0, Q q1, IntBinaryOperator f) {
        ToIntFunction<int[]> q0F = q0.function;
        ToIntFunction<int[]> q1F = q1.function;
        IntBinaryOperator temp = statify(f);
        return new Q(statify2(is -> temp.applyAsInt(q0F.applyAsInt(is), q1F.applyAsInt(is))));
    }
    private static IntUnaryOperator run(Function<Q, Q> f) {
        ToIntFunction<int[]> temp = f.apply(new Q(statify2(is -> is[0]))).function;
        return statify((int x) -> {
            int[] is = {x};
            return temp.applyAsInt(is);
        });
    }
    private static IntBinaryOperator run(BiFunction<Q, Q, Q> f) {
        ToIntFunction<int[]> temp = f.apply(new Q(statify2(is -> is[0])), new Q(statify2(is -> is[1]))).function;
        return statify((int x, int y) -> {
            int[] is = {x, y};
            return temp.applyAsInt(is);
        });
    }

    private static int power(int x, int y) {
        if (y == 0) {
            return 1;
        } else if (y == 1) {
            return x;
        } else {
            return x * power(x, y - 1);
        }
    }

    private static Q powerQ(Q x, int y) {
        if (y == 0) {
            return lift(1);
        } else if (y == 1) {
            return x;
        } else {
            return quote(x, powerQ(x, y - 1), (z, w) -> z * w);
        }
    }

    public static void main(String[] args) {
        IntUnaryOperator power30;

        power30 = x -> power(x, 30);
        power30 = run(x -> powerQ(x, 30));

        int res = 0;
        for (int i = 0; i < 100000000; i++) {
            res += power30.applyAsInt(i);
        }

        long start;
        long end;

        start = System.currentTimeMillis();
        for (int i = 0; i < 100000000; i++) {
            res += power30.applyAsInt(i);
        }
        end = System.currentTimeMillis();

        System.out.println(end - start);
        System.out.println(res);
    }
}
