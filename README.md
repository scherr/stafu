Statified Functionals
===

Maximilian Scherr, 2016-

An experiment to enable code-generating closures for higher performance when called at the cost of lower performance at closure creation.
Performance can be further improved by setting JIT-compilation options for inlining such as
`-XX:MaxInlineLevel=100 -XX:-ClipInlining`.

The code for the familiar staged power function looks like this:

```Java
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
    
// ...   
  
IntUnaryOperator power45 = powerGen(statify(x -> x), 45);
```

The same code without the `statify(...)` calls would still work (`statify` does not change the type or behavior), but the version with
statification is about six times faster (after warm up on JRE 8) due to the JVM's inlining heuristics.
The name "statification" stems from the fact that a closure is turned into a new class, with the captured values in static fields.

Its performance characteristics as well as core functionality depend on the JVM implementation and permissions.
The heavy use of `Unsafe`, reflection, `SerializedLambda`, and inlining options (though they are not necessary to see some performance improvements) may render this not very useful for
actual use in production. It is merely an interesting little experiment.
