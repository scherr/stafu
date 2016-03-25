package stafu.example;

import javassist.Modifier;

import java.io.Serializable;
import java.lang.reflect.Field;

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

    private static class Fac {
        private final static IntUnaryOperator f = null;
        static {
            IntUnaryOperator c = statify(x -> 1);
            IntUnaryOperator rec = statify(x -> x * f.applyAsInt(x - 1));

            try {
                setFinalStatic(Fac.class.getDeclaredField("f"), statify(x -> x <= 0 ? c.applyAsInt(x) : rec.applyAsInt(x)));
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    // Copied from Statification
    private static void setFinalStatic(Field field, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field modField = Field.class.getDeclaredField("modifiers");
        modField.setAccessible(true);
        modField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.setAccessible(true);
        field.set(null, value);
    }

    public static void main(String[] args) throws NoSuchFieldException, IllegalAccessException {
        IntUnaryOperator factorial = x -> factorial(x);

        IntUnaryOperator factorialGen = new Object() {
            private IntUnaryOperator f = null;
            {
                IntUnaryOperator c = statify(x -> 1);
                IntUnaryOperator rec = statify(x -> x * f.applyAsInt(x - 1));

                f = statify(x -> x <= 0 ? c.applyAsInt(x) : rec.applyAsInt(x));
            }
        }.f;
        factorialGen = Fac.f;

        int res = 0;
        for (int i = 0; i < 300000000; i++) {
            res += factorial.applyAsInt(i % 10);
            // res += factorialGen.applyAsInt(i % 10);
        }

        long start;
        long end;

        start = System.currentTimeMillis();
        for (int i = 0; i < 300000000; i++) {
            res += factorial.applyAsInt(i % 10);
            // res += factorialGen.applyAsInt(i % 10);
        }
        end = System.currentTimeMillis();

        System.out.println(end - start);
        System.out.println(res);
    }
}
