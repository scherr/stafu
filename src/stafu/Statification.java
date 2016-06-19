package stafu;

import javassist.*;
import javassist.bytecode.BootstrapMethodsAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Descriptor;
import sun.misc.Unsafe;

import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.*;
import java.lang.reflect.Constructor;
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
    private static final MethodHandles.Lookup TRUSTED_LOOKUP;

    private static final CtClass OBJECT_CT_CLASS;
    private static final CtClass SERIALIZABLE_CT_CLASS;

    private static final CtMethod LAMBDA_METAFACTORY_PROXY;

    static {
        try {
            UNSAFE = getUnsafe().orElseGet(() -> null);
            TRUSTED_LOOKUP = getTrustedLookup().orElseGet(() -> null);

            OBJECT_CT_CLASS = ClassPool.getDefault().get("java.lang.Object");
            SERIALIZABLE_CT_CLASS = ClassPool.getDefault().get("java.io.Serializable");

            LAMBDA_METAFACTORY_PROXY = ClassPool.getDefault().getMethod("stafu.Statification", "lambdaMetafactoryProxy");
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

                    // CtClass statifiedClass = cp.makeClass("stafu.Statified");
                    String implClassPackageName = implClass.getPackageName();
                    CtClass statifiedClass;
                    if (implClassPackageName == null) {
                        statifiedClass = cp.makeClass("Statified");
                    } else {
                        statifiedClass = cp.makeClass(implClassPackageName + ".Statified");
                    }
                    // It seems the later (anonymized) renaming mechanism is able to avoid class-name conflicts

                    if (!importBootstrapMethods(implClass, statifiedClass)) {
                        return Optional.empty();
                    }

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
                    lambdaMethod.setName(statifiedClass.makeUniqueName(lambdaMethod.getName()));
                    statifiedClass.addMethod(lambdaMethod);

                    CtClass[] fimParamTypes = Descriptor.getParameterTypes(sl.getFunctionalInterfaceMethodSignature(), cp);
                    CtClass fimReturnType = Descriptor.getReturnType(sl.getFunctionalInterfaceMethodSignature(), cp);
                    CtMethod fim = new CtMethod(fimReturnType, sl.getFunctionalInterfaceMethodName(), fimParamTypes, statifiedClass);
                    statifiedClass.addMethod(fim);

                    /*
                    // This would allow us to simply call the original method but it seems the JIT compiler will
                    // not like this. Copying is necessary!
                    implMethod.setModifiers(Modifier.PUBLIC | Modifier.STATIC);
                    exp.append(implClass.getName() + "." + implMethod.getName() + "(");
                    // Of course copying opens another can of worms (bootstrap methods, accessibility issues, etc.)
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
                        Class<?> definedClass = UNSAFE.defineAnonymousClass(Class.forName(implClass.getName()), bytes, new Object[0]);

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

    private static boolean importBootstrapMethods(CtClass implClass, CtClass statifiedClass) throws NotFoundException {
        // Statified functionals, or rather their implementation methods might contain lambda expressions themselves.
        // So it is necessary to import the bootstrap method attribute from the implementation class.
        // However, this attribute will contain method handles to inaccessible, private implementation methods.
        // While the anonymous class definition grants enough rights to access these via invokestatic instructions
        // this seems not to be the case for method handles in the bootstrap methods (or rather their static arguments).
        // This seems to be an issue at the class-loading and internal verification level, so not too much can be done.

        // We manage to solve the issue by changing both the static argument ("implMethod") as well as the bootstrap
        // method for lambda expressions. The former is encoded as constant strings and the latter is realized through
        // a proxy method that circumvents the accessibility issues reflectively.
        // Note that we only cover lambda expressions as they are translated in Java 8.
        // Any other use of invokedynamic or other sources of such issues will lead to failure (fallback to original
        // non-statified functional if possible).

        // First we copy the entire bootstrap method attribute and in this process fill the constant pool with new data.
        // This is a very conservative approach. In practice it is likely that none of the bootstrap methods is
        // actually used in the statified class. A more fine-grained solution would involve scanning imported
        // implementation methods for invokedynamic instructions and some tinkering with a selective copying mechanism
        // for the bootstrap method attribute. Adjusting the invokedynamic instructions themselves might also become
        // necessary. By copying and adjusting only the constant pool we can avoid all of this at the cost of slightly
        // larger class files.
        ConstPool constPool = statifiedClass.getClassFile2().getConstPool();
        BootstrapMethodsAttribute bma = (BootstrapMethodsAttribute) implClass.getClassFile2().getAttribute(BootstrapMethodsAttribute.tag).copy(constPool, null);

        BootstrapMethodsAttribute.BootstrapMethod[] bms = bma.getMethods();
        for (int i = 0; i < bms.length; i++) {
            BootstrapMethodsAttribute.BootstrapMethod bm = bms[i];
            int bmIndex = constPool.getMethodHandleIndex(bm.methodRef);
            int bmKind = constPool.getMethodHandleKind(bm.methodRef);
            if (bmKind == ConstPool.REF_invokeStatic) { // First check whether we can handle this
                String bmClassName = constPool.getMethodrefClassName(bmIndex);
                String bmMethodName = constPool.getMethodrefName(bmIndex);
                // String bmMethodType = constPool.getMethodrefType(bmIndex);
                if (bmClassName.equals("java.lang.invoke.LambdaMetafactory") &&
                        (bmMethodName.equals("metafactory") || bmMethodName.equals("altMetafactory"))) {
                    // We reroute both types of metafactory calls to the same proxy
                    int[] args = bms[i].arguments;
                    if (constPool.getTag(args[1]) == ConstPool.CONST_MethodHandle) {
                        // The second argument is the "implMethod" method handle!
                        int argIndex = constPool.getMethodHandleIndex(args[1]);
                        // int argKind = constPool.getMethodHandleKind(args[1]);

                        String argClassName = constPool.getMethodrefClassName(argIndex);
                        String argMethodName = constPool.getMethodrefName(argIndex);
                        String argMethodType = constPool.getMethodrefType(argIndex);

                        // The first argument remains unchanged, the second is encoded using three strings, ...
                        int[] encodedArgs = new int[args.length + 2];
                        encodedArgs[0] = args[0];
                        encodedArgs[1] = constPool.addStringInfo(argClassName);
                        encodedArgs[2] = constPool.addStringInfo(argMethodName);
                        encodedArgs[3] = constPool.addStringInfo(argMethodType);
                        System.arraycopy(args, 2, encodedArgs, 4, args.length - 2);

                        // We need to actually change the bootstrap method, i.e. assign a new one to bms[i]
                        int newBmClassInfo = constPool.addClassInfo(Statification.class.getName());
                        int newBmMethodRefInfo = constPool.addMethodrefInfo(newBmClassInfo, LAMBDA_METAFACTORY_PROXY.getName(), LAMBDA_METAFACTORY_PROXY.getSignature());
                        int newBmIndex = constPool.addMethodHandleInfo(bmKind, newBmMethodRefInfo);
                        bms[i] = new BootstrapMethodsAttribute.BootstrapMethod(newBmIndex, encodedArgs);
                    } else {
                        System.out.println("Failure!");
                        return false;
                    }
                }
            } else {
                System.out.println("Failure!");
                return false;
            }
        }

        // Add the copied bootstrap method attribute to the statified class
        statifiedClass.getClassFile2().addAttribute(new BootstrapMethodsAttribute(constPool, bms));
        return true;
    }

    public static CallSite lambdaMetafactoryProxy(MethodHandles.Lookup caller, String invokedName, MethodType invokedType, MethodType samMethodType, String implClass, String implMethodName, String implMethodSignature, Object... args) throws LambdaConversionException {
        // This is a proxy for calls both to LambdaMetafactory.metafactory as well as LambdaMetafactory.altMetafactory.
        // Its characteristics are:
        //   a) The original "caller" argument is ignored since it would lead to access issues
        //   b) The method handle of the implementation method (called "implMethod" in LambdaMetafactory.metafactory)
        //      does not come pre-created, instead it arrives encoded as three strings
        //   c) The special trusted lookup object is used to build the "implMethod" handle argument
        //   d) The "caller" passed to LambdaMetafactory.altMetafactory is made to appear as if it originated from
        //      the implementation class
        try {
            // The trick is that here we can use reflection to get the handle
            Class<?> c = Class.forName(implClass);
            MethodType mt = MethodType.fromMethodDescriptorString(implMethodSignature, c.getClassLoader());
            Method m = c.getDeclaredMethod(implMethodName, mt.parameterArray());
            m.setAccessible(true);

            // We need to turn ..., samMethodType, implClass, implMethodName, implMethodSignature, { arg0, arg1, ..., argn }
            // into { samMethodType, implMethod, arg0, arg1, ..., argn }
            Object[] factoryArgs = new Object[args.length + 2];
            factoryArgs[0] = samMethodType;
            factoryArgs[1] = TRUSTED_LOOKUP.unreflect(m);
            System.arraycopy(args, 0, factoryArgs, 2, args.length);

            // To LambdaMetafactory methods this should be indistinguishable from a "proper" metafactory
            // call via invokedynamic in the implementation class.
            MethodHandles.Lookup adjustedCaller = TRUSTED_LOOKUP.in(c);
            if (factoryArgs.length > 3) {
                return LambdaMetafactory.altMetafactory(adjustedCaller, invokedName, invokedType, factoryArgs);
            } else {
                return LambdaMetafactory.metafactory(adjustedCaller, invokedName, invokedType, (MethodType) factoryArgs[0], (MethodHandle) factoryArgs[1], (MethodType) factoryArgs[2]);
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Optional<MethodHandles.Lookup> getTrustedLookup() {
        try {
            Constructor constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            constructor.setAccessible(true);
            // See source for MethodHandles.Lookup: The value -1 stands for TRUSTED lookup, which has no restrictions
            return Optional.of(((MethodHandles.Lookup) constructor.newInstance(Statification.class, -1)));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | InstantiationException e) {
            return Optional.empty();
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

    public static Optional<SerializedLambda> getSerializedLambda(Serializable serializable) {
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
