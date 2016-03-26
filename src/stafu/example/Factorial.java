package stafu.example;

import javassist.Modifier;
import stafu.Statification;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Factorial {
    private Factorial() { }

    @FunctionalInterface
    private interface IntUnaryOperator extends java.util.function.IntUnaryOperator, Serializable { }
    private static IntUnaryOperator statify(IntUnaryOperator f) {
        return stafu.Statification.statify(f);
    }

    private static int factorial(int x) {
        if (x <= 0) {
            return 1;
        } else {
            return x * factorial(x - 1);
        }
    }

    public static void main(String[] args) throws NoSuchFieldException, IllegalAccessException {
        IntUnaryOperator factorial;

        factorial = x -> factorial(x);
        factorial = new Object() {
            private IntUnaryOperator f = null;
            {
                IntUnaryOperator c = statify(x -> 1);
                IntUnaryOperator rec = statify(x -> x * f.applyAsInt(x - 1));

                f = statify(x -> x <= 0 ? c.applyAsInt(x) : rec.applyAsInt(x));
            }
        }.f;
        factorial = Statification.fix(s -> {
            // s.get();
            IntUnaryOperator c = statify(x -> 1);
            IntUnaryOperator rec = statify(x -> x * s.get().applyAsInt(x - 1));
            return statify(x -> x <= 0 ? c.applyAsInt(x) : rec.applyAsInt(x));
        });

        int res = 0;
        for (int i = 0; i < 1000000000; i++) {
            res += factorial.applyAsInt(i % 10);
        }

        long start;
        long end;

        start = System.currentTimeMillis();
        for (int i = 0; i < 1000000000; i++) {
            res += factorial.applyAsInt(i % 10);
        }
        end = System.currentTimeMillis();

        System.out.println(end - start);
        System.out.println(res);
    }
}
