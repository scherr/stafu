package stafu.example;

import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.IntStream;

public final class SparseMatrixVectorProduct {
    private SparseMatrixVectorProduct() { }

    @FunctionalInterface
    private interface Function<T, R> extends java.util.function.Function<T, R>, Serializable { }
    private static <T, R> Function<T, R> statify(Function<T, R> f) {
        return stafu.Statification.statify(f);
    }

    @FunctionalInterface
    private interface ToIntFunction<T> extends java.util.function.ToIntFunction<T>, Serializable { }
    private static <T> ToIntFunction<T> statify2(ToIntFunction<T> f) {
        return stafu.Statification.statify(f);
    }

    private static int[] matVecProd(int[][] mat, int[] vec) {
        int n = mat.length;
        int[] res = new int[n];

        for (int i = 0; i < n; i++) {
            int vi = 0;
            for (int j = 0; j < n; j++) {
                vi += vec[j] * mat[i][j];
            }
            res[i] = vi;
        }

        return res;
    }

    private static Function<int[], int[]> matVecProdGen(int[][] mat, Function<int[], int[]> vec) {
        int n = mat.length;
        Function<int[], int[]> res = statify(v -> new int[n]);

        for (int i = 0; i < n; i++) {
            boolean sparse = IntStream.of(mat[i]).filter(x -> x != 0).count() < 3;
            ToIntFunction<int[]> vi;
            if (sparse) {
                vi = statify2(v -> 0);
                for (int j = 0; j < n; j++) {
                    int el = mat[i][j];
                    if (el != 0) {
                        int temp = j;
                        ToIntFunction<int[]> viTemp = vi;
                        if (el == 1) {
                            vi = statify2(v -> viTemp.applyAsInt(v) + vec.apply(v)[temp]);
                        } else {
                            vi = statify2(v -> viTemp.applyAsInt(v) + el * vec.apply(v)[temp]);
                        }
                    }
                }
            } else {
                int[] row = mat[i];
                vi = statify2(v -> {
                    int temp = 0;
                    for (int j = 0; j < n; j++) {
                        temp += vec.apply(v)[j] * row[j];
                    }
                    return temp;
                });
            }

            int temp = i;
            ToIntFunction<int[]> viTemp = vi;
            Function<int[], int[]> resTemp = res;

            res = statify(v -> {
                int[] res2 = resTemp.apply(v);
                res2[temp] = viTemp.applyAsInt(v);
                return res2;
            });
        }

        return res;
    }

    public static void main(String[] args) {
        int[][] mat = {
                {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
        };

        int[] vec = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};


        Function<int[], int[]> matVecProd = v -> matVecProd(mat, v);
        Function<int[], int[]> matVecProdGen = matVecProdGen(mat, statify(v -> v));

        /*
        matVecProdGen = v -> {
            int[] res = new int[12];

            int v2 = v[2];
            int v4 = v[2];


            int[] row = mat[0];
            int r = 0;
            for (int i = 0; i < row.length; i++) {
                r += row[i] * v[i];
            }
            res[0] = r;
            // res[1] = 0;
            res[2] = v2;
            // res[3] = 0;
            res[4] = v2 + v4;
            res[5] = v2 + v4;
            // res[4] = 0;
            // res[5] = 0;
            int[] row2 = mat[6];
            int r2 = 0;
            for (int i = 0; i < row.length; i++) {
                r2 += row2[i] * v[i];
            }
            res[6] = r;
            // res[7] = 0;
            res[8] = v2;
            // res[9] = 0;
            res[10] = v2 + v4;
            res[11] = v2 + v4;

            return res;
        };
        */

        int[] res = null;

        for (int i = 0; i < 100000000; i++) {
            // res = matVecProd.apply(vec);
            res = matVecProdGen.apply(vec);
        }

        long start;
        long end;

        start = System.currentTimeMillis();
        for (int i = 0; i < 100000000; i++) {
            // res = matVecProd.apply(vec);
            res = matVecProdGen.apply(vec);
        }
        end = System.currentTimeMillis();

        System.out.println(end - start);
        System.out.println(Arrays.toString(res));
    }
}
