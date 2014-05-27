package net.bytebuddy.instrumentation;

import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.BridgeMethodResolver;
import net.bytebuddy.instrumentation.method.MethodDescription;
import net.bytebuddy.instrumentation.method.MethodLookupEngine;
import net.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender;
import net.bytebuddy.instrumentation.method.bytecode.stack.StackManipulation;
import net.bytebuddy.instrumentation.method.bytecode.stack.member.MethodInvocation;
import net.bytebuddy.instrumentation.type.InstrumentedType;
import net.bytebuddy.instrumentation.type.TypeDescription;
import net.bytebuddy.instrumentation.type.auxiliary.AuxiliaryType;
import org.objectweb.asm.MethodVisitor;

import java.util.*;

/**
 * An instrumentation is responsible for implementing (or not implementing) methods of a dynamically created type. An
 * instrumentation is applied in two stages:
 * <ol>
 * <li>The instrumentation is able to prepare an instrumented type by adding fields and/or helper methods that are
 * required for the methods implemented by this instrumentation. Furthermore,
 * {@link net.bytebuddy.instrumentation.TypeInitializer}s can be registered for an instrumented
 * type.</li>
 * <li>An instrumentation is required to supply a byte code appender that is responsible for providing the byte code
 * to the instrumented methods that were delegated to this instrumentation. This byte code appender will also
 * be responsible for providing implementations for the methods added in step <i>1</i>.</li>
 * </ol>
 * <p>&nbsp;</p>
 * An instrumentation implementation should provide meaningful implementations {@link java.lang.Object#equals(Object)}
 * and {@link Object#hashCode()} if it wants to avoid to be used twice within the creation of a dynamic type. For two
 * equal instrumentation implementations only one will be applied on the creation of a dynamic type.
 */
public interface Instrumentation {

    /**
     * During the preparation phase of an instrumentation, implementations are eligible to adding fields or methods
     * to the currently instrumented type. All methods that are added by this instrumentation are required to be
     * implemented by the {@link net.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender} that is emitted
     * on the call to
     * {@link net.bytebuddy.instrumentation.Instrumentation#appender(net.bytebuddy.instrumentation.Instrumentation.Target)}
     * call. On this method call, type initializers can also be added to the instrumented type.
     *
     * @param instrumentedType The instrumented type that is the basis of the ongoing instrumentation.
     * @return The instrumented type with any applied changes, if any.
     */
    InstrumentedType prepare(InstrumentedType instrumentedType);

    /**
     * Creates a byte code appender that determines the implementation of the instrumented type's methods.
     *
     * @param instrumentationTarget The target of the current instrumentation.
     * @return A byte code appender for implementing methods delegated to this instrumentation. This byte code appender
     * is also responsible for handling methods that were added by this instrumentation on the call to
     * {@link net.bytebuddy.instrumentation.Instrumentation#prepare(net.bytebuddy.instrumentation.type.InstrumentedType)}.
     */
    ByteCodeAppender appender(Target instrumentationTarget);

    /**
     * An instrumentation for an abstract method that does not append any code and will throw an exception if it is
     * attempted to be composed with other methods that do provide an implementation.
     */
    static enum ForAbstractMethod implements Instrumentation, ByteCodeAppender {

        /**
         * The singleton instance.
         */
        INSTANCE;

        @Override
        public InstrumentedType prepare(InstrumentedType instrumentedType) {
            return instrumentedType;
        }

        @Override
        public ByteCodeAppender appender(Target instrumentationTarget) {
            return this;
        }

        @Override
        public boolean appendsCode() {
            return false;
        }

        @Override
        public Size apply(MethodVisitor methodVisitor, Context instrumentationContext, MethodDescription instrumentedMethod) {
            throw new IllegalStateException();
        }
    }

    /**
     * Represents an type-specific method invocation on the current instrumented type which is not legal from outside
     * the type such as a super method or default method invocation. Legal instances of special method invocations must
     * be equal to one another if they represent the same invocation target.
     */
    static interface SpecialMethodInvocation extends StackManipulation {

        /**
         * Returns the method that represents this special method invocation. This method can be different even for
         * equal special method invocations, dependant on the method that was used to request such an invocation by the
         * means of a {@link net.bytebuddy.instrumentation.Instrumentation.Target}.
         *
         * @return The method description that describes this instances invocation target.
         */
        MethodDescription getMethodDescription();

        /**
         * Returns the target type the represented method is invoked on.
         *
         * @return The type the represented method is invoked on.
         */
        TypeDescription getTypeDescription();

        /**
         * A canonical implementation of an illegal {@link net.bytebuddy.instrumentation.Instrumentation.SpecialMethodInvocation}.
         */
        static enum Illegal implements SpecialMethodInvocation {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public boolean isValid() {
                return false;
            }

            @Override
            public Size apply(MethodVisitor methodVisitor, Context instrumentationContext) {
                throw new IllegalStateException();
            }

            @Override
            public MethodDescription getMethodDescription() {
                throw new IllegalStateException();
            }

            @Override
            public TypeDescription getTypeDescription() {
                throw new IllegalStateException();
            }
        }

        /**
         * A canonical implementation of a legal {@link net.bytebuddy.instrumentation.Instrumentation.SpecialMethodInvocation}.
         */
        static class Legal implements SpecialMethodInvocation {

            private final MethodDescription methodDescription;
            private final TypeDescription typeDescription;

            /**
             * Creates a new legal special method invocation.
             *
             * @param methodDescription The method that represents the special method invocation.
             * @param typeDescription   The type on which the method should be invoked on by an {@code INVOKESPECIAL}
             *                          invocation.
             */
            public Legal(MethodDescription methodDescription, TypeDescription typeDescription) {
                this.methodDescription = methodDescription;
                this.typeDescription = typeDescription;
            }

            @Override
            public MethodDescription getMethodDescription() {
                return methodDescription;
            }

            @Override
            public TypeDescription getTypeDescription() {
                return typeDescription;
            }

            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public Size apply(MethodVisitor methodVisitor, Context instrumentationContext) {
                return MethodInvocation.invoke(methodDescription)
                        .special(typeDescription)
                        .apply(methodVisitor, instrumentationContext);
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                SpecialMethodInvocation specialMethodInvocation = (SpecialMethodInvocation) other;
                return typeDescription.equals(specialMethodInvocation.getTypeDescription())
                        && methodDescription.getInternalName().equals(specialMethodInvocation.getMethodDescription().getInternalName())
                        && methodDescription.getParameterTypes().equals(specialMethodInvocation.getMethodDescription().getParameterTypes())
                        && methodDescription.getReturnType().equals(specialMethodInvocation.getMethodDescription().getReturnType());
            }

            @Override
            public int hashCode() {
                int result = methodDescription.getInternalName().hashCode();
                result = 31 * result + methodDescription.getParameterTypes().hashCode();
                result = 31 * result + methodDescription.getReturnType().hashCode();
                result = 31 * result + typeDescription.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "Instrumentation.SpecialMethodInvocation.Legal{" +
                        "typeDescription=" + typeDescription +
                        ", methodDescription=" + methodDescription +
                        '}';
            }
        }
    }

    /**
     * The target of an instrumentation. Instrumentation targets must be immutable and can be queried without altering
     * the instrumentation result. An instrumentation target provides information on the type that is to be created
     * where it is the implementation's responsibility to cache expensive computations, especially such computations
     * that require reflective look-up.
     */
    static interface Target {

        /**
         * Returns a description of the instrumented type.
         *
         * @return A description of the instrumented type.
         */
        TypeDescription getTypeDescription();

        SpecialMethodInvocation invokeSuper(MethodDescription methodDescription, MethodLookup methodLookup);

        SpecialMethodInvocation invokeDefault(TypeDescription targetType, String uniqueMethodSignature);

        static interface MethodLookup {

            MethodDescription resolve(MethodDescription methodDescription,
                                      Map<String, MethodDescription> invokableMethods,
                                      BridgeMethodResolver bridgeMethodResolver);

            static enum Default implements MethodLookup {

                EXACT {
                    @Override
                    public MethodDescription resolve(MethodDescription methodDescription,
                                                     Map<String, MethodDescription> invokableMethods,
                                                     BridgeMethodResolver bridgeMethodResolver) {
                        return methodDescription;
                    }
                },

                FOR_SUPER_TYPE {
                    @Override
                    public MethodDescription resolve(MethodDescription methodDescription,
                                                     Map<String, MethodDescription> invokableMethods,
                                                     BridgeMethodResolver bridgeMethodResolver) {
                        return bridgeMethodResolver.resolve(invokableMethods.get(methodDescription.getUniqueSignature()));
                    }
                }
            }
        }

        /**
         * A factory for creating an {@link net.bytebuddy.instrumentation.Instrumentation.Target}.
         */
        static interface Factory {

            /**
             * Creates an {@link net.bytebuddy.instrumentation.Instrumentation.Target} for the given instrumented
             * type's description.
             *
             * @return An {@link net.bytebuddy.instrumentation.Instrumentation.Target} for the given type description.
             */
            Target make(MethodLookupEngine.Finding methodLookupEngineFinding);
        }

        abstract static class AbstractBase implements Target {

            protected final TypeDescription typeDescription;
            protected final Map<String, MethodDescription> invokableMethods;
            protected final Map<TypeDescription, Map<String, MethodDescription>> defaultMethods;
            protected final BridgeMethodResolver bridgeMethodResolver;

            protected AbstractBase(MethodLookupEngine.Finding finding,
                                   BridgeMethodResolver.Factory bridgeMethodResolverFactory) {
                bridgeMethodResolver = bridgeMethodResolverFactory.make(finding.getInvokableMethods());
                typeDescription = finding.getTypeDescription();
                invokableMethods = new HashMap<String, MethodDescription>(finding.getInvokableMethods().size());
                for (MethodDescription methodDescription : finding.getInvokableMethods()) {
                    invokableMethods.put(methodDescription.getUniqueSignature(), methodDescription);
                }
                defaultMethods = new HashMap<TypeDescription, Map<String, MethodDescription>>(finding.getInvokableDefaultMethods().size());
                for (Map.Entry<TypeDescription, Set<MethodDescription>> entry : finding.getInvokableDefaultMethods().entrySet()) {
                    Map<String, MethodDescription> defaultMethods = new HashMap<String, MethodDescription>(entry.getValue().size());
                    for (MethodDescription methodDescription : entry.getValue()) {
                        defaultMethods.put(methodDescription.getUniqueSignature(), methodDescription);
                    }
                    this.defaultMethods.put(entry.getKey(), defaultMethods);
                }
            }

            @Override
            public TypeDescription getTypeDescription() {
                return typeDescription;
            }

            @Override
            public Instrumentation.SpecialMethodInvocation invokeSuper(MethodDescription methodDescription,
                                                                       MethodLookup methodLookup) {
                return invokeSuper(methodLookup.resolve(methodDescription, invokableMethods, bridgeMethodResolver));
            }

            protected abstract Instrumentation.SpecialMethodInvocation invokeSuper(MethodDescription methodDescription);

            @Override
            public Instrumentation.SpecialMethodInvocation invokeDefault(TypeDescription targetType,
                                                                         String uniqueMethodSignature) {
                Map<String, MethodDescription> defaultMethods = this.defaultMethods.get(targetType);
                if (defaultMethods != null) {
                    MethodDescription defaultMethod = defaultMethods.get(uniqueMethodSignature);
                    if (defaultMethod != null) {
                        return new Instrumentation.SpecialMethodInvocation.Legal(defaultMethod, targetType);
                    }
                }
                return Instrumentation.SpecialMethodInvocation.Illegal.INSTANCE;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                AbstractBase that = (AbstractBase) other;
                return bridgeMethodResolver.equals(that.bridgeMethodResolver)
                        && defaultMethods.equals(that.defaultMethods)
                        && typeDescription.equals(that.typeDescription);
            }

            @Override
            public int hashCode() {
                int result = typeDescription.hashCode();
                result = 31 * result + defaultMethods.hashCode();
                result = 31 * result + bridgeMethodResolver.hashCode();
                return result;
            }
        }
    }

    /**
     * The context for an instrumentation application. An instrumentation context represents a mutable data structure
     * and all queries are irrevocable. Calling methods on an instrumentation context should be considered equally
     * sensitive as calling a {@link org.objectweb.asm.MethodVisitor}. As such, an instrumentation context and a
     * {@link org.objectweb.asm.MethodVisitor} are complementary for creating an new Java type.
     */
    static interface Context {

        /**
         * Registers an auxiliary type as required for the current instrumentation. Registering a type will cause the
         * creation of this type even if this type is not effectively used for the current instrumentation.
         *
         * @param auxiliaryType The auxiliary type that is required for the current instrumentation.
         * @return A description of the registered auxiliary type.
         */
        TypeDescription register(AuxiliaryType auxiliaryType);

        static interface ExtractableView extends Context {

            List<? extends DynamicType> getRegisteredAuxiliaryTypes();
        }
    }

    /**
     * A compound instrumentation that allows to combine several instrumentations.
     * <p>&nbsp;</p>
     * Note that the combination of two instrumentations might break the contract for implementing
     * {@link java.lang.Object#equals(Object)} and {@link Object#hashCode()} as described for
     * {@link net.bytebuddy.instrumentation.Instrumentation}.
     *
     * @see net.bytebuddy.instrumentation.Instrumentation
     */
    static class Compound implements Instrumentation {

        private final Instrumentation[] instrumentation;

        /**
         * Creates a new immutable compound instrumentation.
         *
         * @param instrumentation The instrumentations to combine in their order.
         */
        public Compound(Instrumentation... instrumentation) {
            this.instrumentation = instrumentation;
        }

        @Override
        public InstrumentedType prepare(InstrumentedType instrumentedType) {
            for (Instrumentation instrumentation : this.instrumentation) {
                instrumentedType = instrumentation.prepare(instrumentedType);
            }
            return instrumentedType;
        }

        @Override
        public ByteCodeAppender appender(Target instrumentationTarget) {
            ByteCodeAppender[] byteCodeAppender = new ByteCodeAppender[instrumentation.length];
            int index = 0;
            for (Instrumentation instrumentation : this.instrumentation) {
                byteCodeAppender[index++] = instrumentation.appender(instrumentationTarget);
            }
            return new ByteCodeAppender.Compound(byteCodeAppender);
        }

        @Override
        public boolean equals(Object o) {
            return this == o || !(o == null || getClass() != o.getClass())
                    && Arrays.equals(instrumentation, ((Compound) o).instrumentation);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(instrumentation);
        }

        @Override
        public String toString() {
            return "Compound{" + Arrays.toString(instrumentation) + '}';
        }
    }
}