package stafu;

import javassist.*;
import javassist.bytecode.BootstrapMethodsAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Descriptor;
import sun.misc.Unsafe;

import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

// -XX:+UnlockDiagnosticVMOptions -XX:MaxInlineLevel=100 -XX:-ClipInlining -XX:-PrintInlining -XX:-PrintFlagsFinal
public final class Statification {
    private static final Unsafe UNSAFE;

    private static final CtClass OBJECT_CT_CLASS;
    private static final CtClass SERIALIZABLE_CT_CLASS;
    private static final CtMethod METAFACTORY_CT_METHOD;

    static {
        try {
            UNSAFE = getUnsafe().orElseGet(() -> null);

            OBJECT_CT_CLASS = ClassPool.getDefault().get("java.lang.Object");
            SERIALIZABLE_CT_CLASS = ClassPool.getDefault().get("java.io.Serializable");
            METAFACTORY_CT_METHOD = ClassPool.getDefault().getMethod("java.lang.invoke.LambdaMetafactory", "metafactory");

        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private Statification() { }

    public static synchronized <F extends Serializable> F statify(F functional) {
        Optional<F> statifiedFunctional;
        if (UNSAFE == null) {
            statifiedFunctional = Optional.empty();
        } else {
            statifiedFunctional = getSerializedLambda(functional).flatMap(sl -> {
                if (sl.getImplMethodKind() != ConstPool.REF_invokeStatic) {
                    return Optional.empty();
                }

                try {
                    ClassPool cp = ClassPool.getDefault();

                    CtClass implClass = cp.get(Descriptor.toJavaName(sl.getImplClass()));
                    CtMethod implMethod = implClass.getMethod(sl.getImplMethodName(), sl.getImplMethodSignature());

                    CtClass fic = cp.get(Descriptor.toJavaName(sl.getFunctionalInterfaceClass()));

                    Class<?> capturingClass = Class.forName(Descriptor.toJavaName(sl.getCapturingClass()));
                    // CtClass statifiedClass = cp.makeClass("stafu.Statified");
                    String implClassPackageName = implClass.getPackageName();
                    CtClass statifiedClass;
                    if (implClassPackageName == null) {
                        statifiedClass = cp.makeClass("Statified");
                    } else {
                        statifiedClass = cp.makeClass(implClassPackageName + ".Statified");
                    }
                    // It seems the later (anonymized) renaming mechanism is able to avoid class-name conflicts

                    statifiedClass.setInterfaces(new CtClass[]{fic, SERIALIZABLE_CT_CLASS});
                    statifiedClass.addConstructor(CtNewConstructor.defaultConstructor(statifiedClass));

                    CtClass[] implMethodParamTypes;
                    CtClass implMethodReturnType;
                    implMethodParamTypes = Descriptor.getParameterTypes(sl.getImplMethodSignature(), cp);
                    implMethodReturnType = Descriptor.getReturnType(sl.getImplMethodSignature(), cp);

                    for (int i = 0; i < sl.getCapturedArgCount(); i++) {
                        CtField argField = CtField.make("private static final " + implMethodParamTypes[i].getName() + " captured" + i + ";", statifiedClass);
                        statifiedClass.addField(argField);
                    }

                    ClassMap map = new ClassMap();
                    map.fix(implClass); // This is necessary due to the copying behavior of CtMethod's constructor!
                    CtMethod lambdaMethod = new CtMethod(implMethod, statifiedClass, map);
                    lambdaMethod.setName(statifiedClass.makeUniqueName("lambda"));
                    statifiedClass.addMethod(lambdaMethod);

                    CtClass[] fimParamTypes = Descriptor.getParameterTypes(sl.getFunctionalInterfaceMethodSignature(), cp);
                    CtClass fimReturnType = Descriptor.getReturnType(sl.getFunctionalInterfaceMethodSignature(), cp);
                    CtMethod fim = new CtMethod(fimReturnType, sl.getFunctionalInterfaceMethodName(), fimParamTypes, statifiedClass);
                    statifiedClass.addMethod(fim);

                    /*
                    // This would allow us to simply call the original method but it seems the JIT compiler will
                    // not like this. Copying is necessary! TODO: Include all transitively invokedynamic targets!
                    implMethod.setModifiers(Modifier.PUBLIC | Modifier.STATIC);
                    exp.append(implClass.getName() + "." + implMethod.getName() + "(");
                     */

                    StringBuilder exp = new StringBuilder();
                    exp.append(lambdaMethod.getName() + "(");
                    for (int i = 0; i < sl.getCapturedArgCount(); i++) {
                        if (i > 0) {
                            exp.append(", ");
                        }
                        exp.append("captured" + i);
                    }
                    for (int i = sl.getCapturedArgCount(); i < implMethodParamTypes.length; i++) {
                        if (i > 0) {
                            exp.append(", ");
                        }
                        exp.append(convert("$" + (i - sl.getCapturedArgCount() + 1), fimParamTypes[i - sl.getCapturedArgCount()], implMethodParamTypes[i]));
                    }
                    exp.append(")");

                    if (fimReturnType != CtClass.voidType) {
                        fim.setBody("{ return " + convert(exp.toString(), implMethodReturnType, fimReturnType) + "; }");
                    } else {
                        fim.setBody("{" + exp.toString() + "; }");
                    }

                    statifiedClass.setModifiers(Modifier.PUBLIC | Modifier.FINAL);

                    byte[] bytes = statifiedClass.toBytecode();
                    statifiedClass.detach();

                    try {
                        Class<?> definedClass = UNSAFE.defineAnonymousClass(capturingClass, bytes, new Object[0]);

                        for (int i = 0; i < sl.getCapturedArgCount(); i++) {

                            Field field = definedClass.getDeclaredField("captured" + i);
                            setFinalStatic(field, sl.getCapturedArg(i));
                        }
                        return Optional.of((F) definedClass.newInstance());
                    } catch (IllegalAccessException | InstantiationException | NoSuchFieldException e) {
                        e.printStackTrace();
                        return Optional.empty();
                    }
                } catch (IOException | CannotCompileException | ClassNotFoundException | NotFoundException e) {
                    e.printStackTrace();
                    return Optional.empty();
                }
            });
        }

        if (statifiedFunctional.isPresent()) {
            return statifiedFunctional.get();
        } else {
            return functional;
        }
    }

    public static synchronized <F> F fix(Function<Supplier<F>, F> generator) {
        Optional<F> statifiedF;

        if (UNSAFE == null) {
            statifiedF = Optional.empty();
        } else {
            try {
                ClassPool cp = ClassPool.getDefault();
                CtClass fixClass = cp.makeClass("stafu.Fix");
                fixClass.addInterface(cp.get("java.util.function.Supplier"));

                CtField fixedPointField = CtField.make("private static final Object fixedPoint;", fixClass);
                fixClass.addField(fixedPointField);

                CtMethod getMethod = CtMethod.make(
                        "public Object get() {"
                                + "    if (stafu.Fix.fixedPoint == null) {"
                                + "        throw new RuntimeException(\"Premature fixed-point retrieval. It is not available yet!\");"
                                + "    }"
                                + "    return stafu.Fix.fixedPoint;"
                                + "}", fixClass);
                fixClass.addMethod(getMethod);

                fixClass.addConstructor(CtNewConstructor.defaultConstructor(fixClass));

                byte[] bytes = fixClass.toBytecode();
                fixClass.detach();

                statifiedF = getUnsafe().flatMap(unsafe -> {
                    try {
                        Class<?> definedClass = unsafe.defineAnonymousClass(generator.getClass(), bytes, new Object[0]);

                        Supplier<F> supplier = (Supplier<F>) definedClass.newInstance();
                        F f = generator.apply(supplier);

                        Field field = definedClass.getDeclaredField("fixedPoint");

                        setFinalStatic(field, f);
                        return Optional.of(f);
                    } catch (IllegalAccessException | NoSuchFieldException | InstantiationException e) {
                        e.printStackTrace();
                        return Optional.empty();
                    }
                });
            } catch (CannotCompileException | IOException | NotFoundException e) {
                e.printStackTrace();
                statifiedF = Optional.empty();
            }
        }

        if (statifiedF.isPresent()) {
            return statifiedF.get();
        } else {
            return new Object() {
                private F fixedPoint;
                {
                    fixedPoint = generator.apply(statify((Serializable & Supplier<F>) () -> {
                        if (fixedPoint == null) {
                            throw new RuntimeException("Premature fixed-point retrieval. It is not available yet!");
                        }
                        return fixedPoint;
                    }));
                }
            }.fixedPoint;
        }
    }

    private static void setFinalStatic(Field field, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field modField = Field.class.getDeclaredField("modifiers");
        modField.setAccessible(true);
        modField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.setAccessible(true);
        field.set(null, value);
    }

    private static Optional<Unsafe> getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return Optional.of((Unsafe) field.get(null));
        } catch (IllegalArgumentException | SecurityException | IllegalAccessException | NoSuchFieldException e) {
            return Optional.empty();
        }
    }

    private static String convert(String expression, CtClass from, CtClass to) {
        if (from.isPrimitive() && !to.isPrimitive()) {
            CtPrimitiveType t = (CtPrimitiveType) from;
            CtClass wrapper = to.getClassPool().getOrNull(t.getWrapperName());
            return wrapper.getName() + ".valueOf(" + expression + ")";
        } else if (!from.isPrimitive() && to.isPrimitive()) {
            CtPrimitiveType t = (CtPrimitiveType) to;
            return "((" + t.getWrapperName() + ")" + expression + ")." + t.getGetMethodName() + "()";
        } else if (!to.equals(OBJECT_CT_CLASS)) {
            return "((" + to.getName() + ")" + expression + ")";
        }
        return expression;
    }

    private static Optional<SerializedLambda> getSerializedLambda(Serializable serializable) {
        if (serializable != null) {
            try {
                Method writeReplace = serializable.getClass().getDeclaredMethod("writeReplace");
                writeReplace.setAccessible(true);
                return Optional.of((SerializedLambda) writeReplace.invoke(serializable));
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                // return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private static <T> Optional<Method> getSam(Class<T> c) {
        if (c.isInterface()) {
            return Arrays.stream(c.getMethods())
                    .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()) && java.lang.reflect.Modifier.isAbstract(m.getModifiers()))
                    .findFirst();
        } else {
            return Optional.empty();
        }
    }
}
