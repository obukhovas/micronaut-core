/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.ast.groovy

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import io.micronaut.aop.Adapter
import io.micronaut.aop.Around
import io.micronaut.aop.Interceptor
import io.micronaut.aop.InterceptorBinding
import io.micronaut.aop.InterceptorKind
import io.micronaut.aop.Introduction
import io.micronaut.aop.internal.intercepted.InterceptedMethodUtil
import io.micronaut.aop.writer.AopProxyWriter
import io.micronaut.ast.groovy.annotation.GroovyAnnotationMetadataBuilder
import io.micronaut.ast.groovy.utils.AstAnnotationUtils
import io.micronaut.ast.groovy.utils.AstGenericUtils
import io.micronaut.ast.groovy.utils.AstMessageUtils
import io.micronaut.ast.groovy.utils.PublicAbstractMethodVisitor
import io.micronaut.ast.groovy.utils.PublicMethodVisitor
import io.micronaut.ast.groovy.visitor.GroovyElementFactory
import io.micronaut.ast.groovy.visitor.GroovyVisitorContext
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.ConfigurationBuilder
import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.ConfigurationReader
import io.micronaut.context.annotation.DefaultScope
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Value
import io.micronaut.core.annotation.AnnotationClassValue
import io.micronaut.core.annotation.AnnotationMetadata
import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.core.annotation.Internal
import io.micronaut.core.bind.annotation.Bindable
import io.micronaut.core.naming.NameUtils
import io.micronaut.core.reflect.ClassUtils
import io.micronaut.core.util.ArrayUtils
import io.micronaut.core.util.StringUtils
import io.micronaut.core.value.OptionalValues
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy
import io.micronaut.inject.annotation.AnnotationMetadataReference
import io.micronaut.inject.annotation.DefaultAnnotationMetadata
import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.Element
import io.micronaut.inject.ast.FieldElement
import io.micronaut.inject.ast.MethodElement
import io.micronaut.inject.ast.ParameterElement
import io.micronaut.inject.ast.PrimitiveElement
import io.micronaut.inject.configuration.ConfigurationMetadata
import io.micronaut.inject.configuration.ConfigurationMetadataBuilder
import io.micronaut.inject.configuration.PropertyMetadata
import io.micronaut.inject.visitor.VisitorConfiguration
import io.micronaut.inject.writer.BeanDefinitionReferenceWriter
import io.micronaut.inject.writer.BeanDefinitionVisitor
import io.micronaut.inject.writer.BeanDefinitionWriter
import io.micronaut.inject.writer.OriginatingElements
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.tools.GeneralUtils
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException

import java.lang.reflect.Modifier
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

import static org.codehaus.groovy.ast.ClassHelper.makeCached
import static org.codehaus.groovy.ast.tools.GeneralUtils.getGetterName
import static org.codehaus.groovy.ast.tools.GeneralUtils.getSetterName

@CompileStatic
final class InjectVisitor extends ClassCodeVisitorSupport {
    public static final String AROUND_TYPE = AnnotationUtil.ANN_AROUND
    public static final String INTRODUCTION_TYPE = AnnotationUtil.ANN_INTRODUCTION
    final SourceUnit sourceUnit
    final ClassNode concreteClass
    final ClassElement concreteClassElement
    AnnotationMetadata concreteClassAnnotationMetadata
    final ClassElement originatingElement
    final boolean isConfigurationProperties
    final boolean isFactoryClass
    final boolean isExecutableType
    final boolean isAopProxyType
    final boolean isDeclaredBean
    final ConfigurationMetadataBuilder<ClassNode> configurationMetadataBuilder
    ConfigurationMetadata configurationMetadata

    final Map<AnnotatedNode, BeanDefinitionVisitor> beanDefinitionWriters = [:]
    private BeanDefinitionVisitor beanWriter
    BeanDefinitionVisitor aopProxyWriter
    final AtomicInteger adaptedMethodIndex = new AtomicInteger(0)
    final AtomicInteger factoryMethodIndex = new AtomicInteger(0)
    private final CompilationUnit compilationUnit
    private final GroovyElementFactory elementFactory
    GroovyVisitorContext groovyVisitorContext

    InjectVisitor(SourceUnit sourceUnit, CompilationUnit compilationUnit, ClassNode targetClassNode, ConfigurationMetadataBuilder<ClassNode> configurationMetadataBuilder) {
        this(sourceUnit, compilationUnit, targetClassNode, null, configurationMetadataBuilder)
    }

    InjectVisitor(SourceUnit sourceUnit, CompilationUnit compilationUnit, ClassNode targetClassNode, Boolean configurationProperties, ConfigurationMetadataBuilder<ClassNode> configurationMetadataBuilder) {
        this.compilationUnit = compilationUnit
        this.sourceUnit = sourceUnit
        groovyVisitorContext = new GroovyVisitorContext(sourceUnit, compilationUnit) {
            @Override
            VisitorConfiguration getConfiguration() {
                new VisitorConfiguration() {
                    @Override
                    boolean includeTypeLevelAnnotationsInGenericArguments() {
                        return false
                    }
                }
            }
        }
        this.elementFactory = groovyVisitorContext.getElementFactory()
        this.configurationMetadataBuilder = configurationMetadataBuilder
        this.concreteClass = targetClassNode
        def annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, targetClassNode)
        this.concreteClassAnnotationMetadata = annotationMetadata
        this.originatingElement = elementFactory.newClassElement(concreteClass, annotationMetadata)
        this.concreteClassElement = originatingElement
        this.isFactoryClass = annotationMetadata.hasStereotype(Factory)
        this.isAopProxyType = hasAroundStereotype(annotationMetadata) && !targetClassNode.isAbstract() && !concreteClassElement.isAssignable(Interceptor.class)
        this.isExecutableType = isAopProxyType || annotationMetadata.hasStereotype(Executable)
        this.isConfigurationProperties = configurationProperties != null ? configurationProperties : annotationMetadata.hasDeclaredStereotype(ConfigurationReader)
        if (isConfigurationProperties) {
            this.configurationMetadata = configurationMetadataBuilder.visitProperties(
                    concreteClass,
                    null
            )
        }

        if (isAopProxyType && Modifier.isFinal(targetClassNode.modifiers)) {
            addError("Cannot apply AOP advice to final class. Class must be made non-final to support proxying: " + targetClassNode.name, targetClassNode)
        }
        this.isDeclaredBean = isExecutableType || isConfigurationProperties || isFactoryClass || annotationMetadata.hasStereotype(AnnotationUtil.SCOPE) || annotationMetadata.hasStereotype(DefaultScope) || annotationMetadata.hasDeclaredStereotype(Bean) || concreteClass.declaredConstructors.any {
            AnnotationMetadata constructorMetadata = AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, it)
            constructorMetadata.hasStereotype(AnnotationUtil.INJECT)
        }
        if (isDeclaredBean) {
            defineBeanDefinition(concreteClass)
        }
    }

    static boolean hasAroundStereotype(AnnotationMetadata annotationMetadata) {
        if (annotationMetadata.hasStereotype(AROUND_TYPE)) {
            return true
        } else {
            if (annotationMetadata.hasStereotype(AnnotationUtil.ANN_INTERCEPTOR_BINDINGS)) {
                return annotationMetadata.getAnnotationValuesByType(InterceptorBinding)
                    .stream().anyMatch{ av ->
                    av.enumValue("kind", InterceptorKind).orElse(InterceptorKind.AROUND) == InterceptorKind.AROUND
                }
            }
        }
        return false
    }

    BeanDefinitionVisitor getBeanWriter() {
        if (this.beanWriter == null) {
            defineBeanDefinition(concreteClass)
        }
        return beanWriter
    }

    @Override
    void addError(String msg, ASTNode expr) {
        SourceUnit source = getSourceUnit()
        source.getErrorCollector().addError(
                new SyntaxErrorMessage(new SyntaxException(msg + '\n', expr.getLineNumber(), expr.getColumnNumber(), expr.getLastLineNumber(), expr.getLastColumnNumber()), source)
        )
    }

    @Override
    void visitClass(ClassNode node) {
        AnnotationMetadata annotationMetadata
        if (concreteClass == node) {
            annotationMetadata = concreteClassAnnotationMetadata
        } else {
            annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, node)
        }
        boolean isInterface = node.isInterface()
        if (isConfigurationProperties && isInterface) {
            String adviceType = InjectTransform.ANN_CONFIGURATION_ADVICE
            ((Element)concreteClassElement).annotate(adviceType) // hack to make Groovy compile
            concreteClassAnnotationMetadata = concreteClassElement.annotationMetadata
            annotationMetadata = concreteClassAnnotationMetadata
        }
        if (annotationMetadata.hasStereotype(INTRODUCTION_TYPE)) {
            String packageName = node.packageName
            String beanClassName = node.nameWithoutPackage

            AnnotationValue<?>[] aroundInterceptors = InterceptedMethodUtil
                    .resolveInterceptorBinding(annotationMetadata, InterceptorKind.AROUND)

            AnnotationValue<?>[] introductionInterceptors = InterceptedMethodUtil
                    .resolveInterceptorBinding(annotationMetadata, InterceptorKind.INTRODUCTION)


            AnnotationValue<?>[] interceptorTypes = (AnnotationValue<?>[]) ArrayUtils.concat(aroundInterceptors, introductionInterceptors)
            ClassElement[] interfaceTypes = annotationMetadata.getValue(Introduction.class, "interfaces", String[].class).orElse(new String[0])
                    .collect { ClassElement.of(it) }

            AopProxyWriter aopProxyWriter = new AopProxyWriter(
                    packageName,
                    beanClassName,
                    isInterface,
                    originatingElement,
                    annotationMetadata,
                    interfaceTypes,
                    groovyVisitorContext,
                    configurationMetadataBuilder,
                    configurationMetadata,
                    interceptorTypes
            )
            ClassElement groovyClassElement = elementFactory.newClassElement(
                    node,
                    annotationMetadata
            )
            aopProxyWriter.visitTypeArguments(groovyClassElement.getAllTypeArguments())
            populateProxyWriterConstructor(groovyClassElement, aopProxyWriter, groovyClassElement.getPrimaryConstructor().orElse(null))
            beanDefinitionWriters.put(node, aopProxyWriter)
            this.aopProxyWriter = aopProxyWriter
            visitIntroductionTypePublicMethods(aopProxyWriter, node)
            if (ArrayUtils.isNotEmpty(interfaceTypes)) {
                List<AnnotationNode> annotationNodes = node.annotations
                Set<ClassNode> interfacesToVisit = []

                populateIntroducedInterfaces(annotationNodes, interfacesToVisit)

                if (!interfacesToVisit.isEmpty()) {
                    for (ClassNode itce in interfacesToVisit as Set<ClassNode>) {
                        visitIntroductionTypePublicMethods(aopProxyWriter, itce)
                    }
                }
            }

            if (!isInterface) {
                node.visitContents(this)
            }
        } else {
            boolean isOwningClass = node == concreteClass
            if (isOwningClass && concreteClass.abstract && !isDeclaredBean) {
                return
            }

            if (annotationMetadata.hasStereotype(AROUND_TYPE)) {
                AnnotationValue<?>[] interceptorTypeReferences = InterceptedMethodUtil
                        .resolveInterceptorBinding(annotationMetadata, InterceptorKind.AROUND)
                resolveProxyWriter(annotationMetadata.getValues(AROUND_TYPE, Boolean.class), false, interceptorTypeReferences)
            }

            ClassNode superClass = node.getSuperClass()
            List<ClassNode> superClasses = []
            while (superClass != null) {
                superClasses.add(superClass)
                superClass = superClass.getSuperClass()
            }
            superClasses = superClasses.reverse()
            for (classNode in superClasses) {
                if (classNode.name != ClassHelper.OBJECT_TYPE.name && classNode.name != GroovyObjectSupport.name && classNode.name != Script.name) {
                    classNode.visitContents(this)
                }
            }
            super.visitClass(node)
        }
    }

    private void populateIntroducedInterfaces(List<AnnotationNode> annotationNodes, Set<ClassNode> interfacesToVisit) {
        for (ann in annotationNodes) {
            if (ann.classNode.name == Introduction.class.getName()) {
                Expression expression = ann.getMember("interfaces")
                if (expression instanceof ClassExpression) {
                    interfacesToVisit.add(((ClassExpression) expression).type)
                } else if (expression instanceof ListExpression) {
                    ListExpression list = (ListExpression) expression
                    for (expr in list.expressions) {
                        if (expr instanceof ClassExpression) {
                            interfacesToVisit.add(((ClassExpression) expr).type)
                        }
                    }
                }
            } else if (AstAnnotationUtils.hasStereotype(sourceUnit, compilationUnit, ann.classNode, Introduction)) {
                populateIntroducedInterfaces(ann.classNode.annotations, interfacesToVisit)
            }
        }
    }

    @CompileStatic
    protected void visitIntroductionTypePublicMethods(AopProxyWriter aopProxyWriter, ClassNode node) {
        AnnotationMetadata typeAnnotationMetadata = aopProxyWriter.getAnnotationMetadata()
        SourceUnit source = this.sourceUnit
        CompilationUnit unit = this.compilationUnit
        ClassElement concreteClassElement = this.concreteClassElement
        AnnotationMetadata concreteClassAnnotationMetadata = this.concreteClassAnnotationMetadata
        PublicMethodVisitor publicMethodVisitor = new PublicAbstractMethodVisitor(source, unit) {

            @Override
            protected boolean isAcceptableMethod(MethodNode methodNode) {
                return super.isAcceptableMethod(methodNode) || hasDeclaredAroundStereotype(AstAnnotationUtils.getAnnotationMetadata(source, unit, methodNode))
            }

            @Override
            void accept(ClassNode classNode, MethodNode methodNode) {
                AnnotationMetadata annotationMetadata
                if (AstAnnotationUtils.isAnnotated(node.name, methodNode) || AstAnnotationUtils.hasAnnotation(methodNode, Override)) {
                    annotationMetadata = AstAnnotationUtils.newBuilder(source, unit).buildForParent(node.name, node, methodNode)
                    annotationMetadata = new AnnotationMetadataHierarchy(concreteClassAnnotationMetadata, annotationMetadata)
                } else {
                    annotationMetadata = new AnnotationMetadataReference(
                            aopProxyWriter.getBeanDefinitionName() + BeanDefinitionReferenceWriter.REF_SUFFIX,
                            typeAnnotationMetadata
                    )
                }
                MethodElement groovyMethodElement = elementFactory.newMethodElement(
                        concreteClassElement,
                        methodNode,
                        annotationMetadata
                )

                ClassNode owningType = AstGenericUtils.resolveTypeReference(methodNode.declaringClass)
                ClassElement owningClassElement = elementFactory.newClassElement(
                        owningType,
                        concreteClassAnnotationMetadata
                )


                if (!annotationMetadata.hasStereotype("io.micronaut.validation.Validated") &&
                        isDeclaredBean) {
                    boolean hasConstraint
                    for (ParameterElement p: groovyMethodElement.getParameters()) {
                        AnnotationMetadata parameterMetadata = p.annotationMetadata
                        if (InjectTransform.IS_CONSTRAINT.test(parameterMetadata)) {
                            hasConstraint = true
                            break
                        }
                    }
                    if (hasConstraint) {
                        if (annotationMetadata instanceof AnnotationMetadataReference) {
                            annotationMetadata = AstAnnotationUtils.newBuilder(source, unit).buildForParent(node.name, node, methodNode)
                            groovyMethodElement = elementFactory.newMethodElement(
                                    concreteClassElement,
                                    methodNode,
                                    annotationMetadata
                            )
                        }

                        annotationMetadata = addValidated(groovyMethodElement)
                    }
                }

                if (isConfigurationProperties && methodNode.isAbstract()) {
                    if (!aopProxyWriter.isValidated()) {
                        aopProxyWriter.setValidated(InjectTransform.IS_CONSTRAINT.test(annotationMetadata))
                    }

                    if (!NameUtils.isGetterName(methodNode.name)) {
                        error("Only getter methods are allowed on @ConfigurationProperties interfaces: " + methodNode.name, classNode)
                        return
                    }

                    if (groovyMethodElement.hasParameters()) {
                        error("Only zero argument getter methods are allowed on @ConfigurationProperties interfaces: " + methodNode.name, classNode)
                        return
                    }
                    String propertyName = NameUtils.getPropertyNameForGetter(methodNode.name)
                    String propertyType = methodNode.returnType.name

                    if ("void".equals(propertyType)) {
                        error("Getter methods must return a value @ConfigurationProperties interfaces: " + methodNode.name, classNode)
                        return
                    }

                    final PropertyMetadata propertyMetadata = configurationMetadataBuilder.visitProperty(
                            current.isInterface() ? current : classNode,
                            classNode,
                            propertyType,
                            propertyName,
                            null,
                            annotationMetadata.stringValue(Bindable.class, "defaultValue").orElse(null)
                    )

                    annotationMetadata = addPropertyMetadata(
                            groovyMethodElement,
                            propertyMetadata
                    )

                    final ClassNode typeElement = !ClassUtils.isJavaBasicType(propertyType) ? methodNode.returnType : null
                    if (typeElement != null && AstAnnotationUtils.hasStereotype(source, unit, typeElement, AnnotationUtil.SCOPE)) {
                        annotationMetadata = addBeanConfigAdvise(annotationMetadata)
                    } else {
                        annotationMetadata = addAnnotation(groovyMethodElement, InjectTransform.ANN_CONFIGURATION_ADVICE)
                    }

                }

                if (hasAroundStereotype(AstAnnotationUtils.getAnnotationMetadata(source, unit, methodNode))) {
                    AnnotationValue<?>[] interceptorTypeReferences = InterceptedMethodUtil
                            .resolveInterceptorBinding(annotationMetadata, InterceptorKind.AROUND)
                    aopProxyWriter.visitInterceptorBinding(interceptorTypeReferences)
                }

                if (methodNode.isAbstract()) {
                    aopProxyWriter.visitIntroductionMethod(
                            owningClassElement,
                            groovyMethodElement
                    )
                } else {
                    aopProxyWriter.visitAroundMethod(
                            owningClassElement,
                            groovyMethodElement
                    )
                }
            }

            @CompileDynamic
            private void error(String msg, ClassNode classNode) {
                addError(msg, (ASTNode) classNode)
            }

            @CompileDynamic
            private AnnotationMetadata addBeanConfigAdvise(AnnotationMetadata annotationMetadata) {
                new GroovyAnnotationMetadataBuilder(source, compilationUnit).annotate(
                        annotationMetadata,
                        AnnotationValue.builder(InjectTransform.ANN_CONFIGURATION_ADVICE).member("bean", true).build()
                )
            }

        }
        publicMethodVisitor.accept(node)
    }

    @Override
    protected void visitConstructorOrMethod(MethodNode methodNode, boolean isConstructor) {
        if (methodNode.isSynthetic() || methodNode.name.contains('$')) return

        String methodName = methodNode.name
        ClassNode declaringClass = methodNode.declaringClass
        AnnotationMetadata methodAnnotationMetadata = getAnnotationMetadataHierarchy(
                AstAnnotationUtils.getMethodAnnotationMetadata(sourceUnit, compilationUnit, methodNode)
        )
        def declaringElement = elementFactory.newClassElement(
                declaringClass,
                AnnotationMetadata.EMPTY_METADATA
        )

        final boolean isStatic = methodNode.isStatic()
        final boolean isAbstract = methodNode.isAbstract()
        final boolean isPrivate = methodNode.isPrivate()
        final boolean isPublic = methodNode.isPublic()

        if (isFactoryClass && !isConstructor && methodAnnotationMetadata.hasDeclaredStereotype(Bean.getName(), AnnotationUtil.SCOPE)) {
            boolean isParent = declaringClass != concreteClass
            MethodNode overriddenMethod = isParent ? concreteClass.getMethod(methodName, methodNode.parameters) : methodNode
            boolean overridden = isParent && overriddenMethod.declaringClass != declaringClass
            if (!overridden) {
                methodAnnotationMetadata = new GroovyAnnotationMetadataBuilder(sourceUnit, compilationUnit).buildForParent(methodNode.returnType, methodNode, true)

                visitBeanFactoryElement(declaringClass, methodNode, methodAnnotationMetadata, methodName)
            }
        } else if (methodAnnotationMetadata.hasStereotype(AnnotationUtil.INJECT) ||
                methodAnnotationMetadata.hasDeclaredAnnotation(AnnotationUtil.POST_CONSTRUCT) ||
                methodAnnotationMetadata.hasDeclaredAnnotation(AnnotationUtil.PRE_DESTROY)) {
            if (isConstructor && methodAnnotationMetadata.hasStereotype(AnnotationUtil.INJECT)) {
                // constructor with explicit @Inject
                defineBeanDefinition(concreteClass)
            } else if (!isConstructor) {
                if (!isStatic && !isAbstract) {
                    boolean isParent = declaringClass != concreteClass
                    MethodNode overriddenMethod = isParent ? concreteClass.getMethod(methodName, methodNode.parameters) : methodNode
                    boolean overridden = isParent && overriddenMethod.declaringClass != declaringClass

                    boolean isPackagePrivate = isPackagePrivate(methodNode, methodNode.modifiers)

                    if (isParent && !isPrivate && !isPackagePrivate) {
                        if (overridden) {
                            // bail out if the method has been overridden, since it will have already been handled
                            return
                        }
                    }
                    boolean packagesDiffer = overriddenMethod.declaringClass.packageName != declaringClass.packageName
                    boolean isPackagePrivateAndPackagesDiffer = overridden && packagesDiffer && isPackagePrivate
                    boolean requiresReflection = isPrivate || isPackagePrivateAndPackagesDiffer
                    boolean overriddenInjected = overridden && AstAnnotationUtils.hasStereotype(sourceUnit, compilationUnit, overriddenMethod, AnnotationUtil.INJECT)

                    if (isParent && isPackagePrivate && !isPackagePrivateAndPackagesDiffer && overriddenInjected) {
                        // bail out if the method has been overridden by another method annotated with @INject
                        return
                    }
                    if (isParent && overridden && !overriddenInjected && !isPackagePrivateAndPackagesDiffer && !isPrivate) {
                        // bail out if the overridden method is package private and in the same package
                        // and is not annotated with @Inject
                        return
                    }
                    if (!requiresReflection && isInheritedAndNotPublic(methodNode, declaringClass, methodNode.modifiers)) {
                        requiresReflection = true
                    }

                    MethodElement groovyMethodElement = elementFactory.newMethodElement(
                            declaringElement,
                            methodNode,
                            methodAnnotationMetadata
                    )

                    if (isDeclaredBean && methodAnnotationMetadata.hasDeclaredAnnotation(AnnotationUtil.POST_CONSTRUCT)) {
                        defineBeanDefinition(concreteClass)
                        getBeanWriter().visitPostConstructMethod(
                                declaringElement,
                                groovyMethodElement,
                                requiresReflection,
                                groovyVisitorContext
                        )
                    } else if (isDeclaredBean && methodAnnotationMetadata.hasDeclaredAnnotation(AnnotationUtil.PRE_DESTROY)) {
                        defineBeanDefinition(concreteClass)
                        beanWriter.visitPreDestroyMethod(
                                declaringElement,
                                groovyMethodElement,
                                requiresReflection,
                                groovyVisitorContext
                        )
                        if (aopProxyWriter instanceof AopProxyWriter && !((AopProxyWriter)aopProxyWriter).isProxyTarget()) {
                            aopProxyWriter.visitPreDestroyMethod(
                                    declaringElement,
                                    groovyMethodElement,
                                    requiresReflection,
                                    groovyVisitorContext
                            )
                        }
                    } else if (methodAnnotationMetadata.hasStereotype(AnnotationUtil.INJECT)) {
                        defineBeanDefinition(concreteClass)
                        getBeanWriter().visitMethodInjectionPoint(
                                declaringElement,
                                groovyMethodElement,
                                requiresReflection,
                                groovyVisitorContext
                        )
                    }
                }
            }
        } else if (!isConstructor) {
            boolean hasInvalidModifiers = isStatic || isAbstract || methodNode.isSynthetic() || methodAnnotationMetadata.hasAnnotation(Internal) || isPrivate
            boolean isExecutable = ((isExecutableType && isPublic) || methodAnnotationMetadata.hasStereotype(Executable) || hasAroundStereotype(methodAnnotationMetadata))

            if (isDeclaredBean && isExecutable) {
                if (hasInvalidModifiers) {
                    if (isPrivate && (methodAnnotationMetadata.hasDeclaredStereotype(Executable) || hasDeclaredAroundStereotype(methodAnnotationMetadata))) {
                        addError("Method annotated as executable but is declared private. Change the method to be non-private in order for AOP advice to be applied.", methodNode)
                    }
                } else {
                    visitExecutableMethod(
                        declaringClass,
                        methodNode,
                        methodAnnotationMetadata,
                        methodName,
                        isPublic
                    )
                }
            } else if (isConfigurationProperties && isPublic) {
                methodAnnotationMetadata = AstAnnotationUtils.newBuilder(sourceUnit, compilationUnit).buildDeclared(methodNode)
                if (NameUtils.isSetterName(methodNode.name) && methodNode.parameters.length == 1) {
                    String propertyName = NameUtils.getPropertyNameForSetter(methodNode.name)
                    MethodElement groovyMethodElement = elementFactory.newMethodElement(
                            declaringElement,
                            methodNode,
                            methodAnnotationMetadata
                    )
                    ParameterElement parameterElement = groovyMethodElement.parameters[0]

                    if (methodAnnotationMetadata.hasStereotype(ConfigurationBuilder.class)) {
                        getBeanWriter().visitConfigBuilderMethod(
                                parameterElement.type,
                                NameUtils.getterNameFor(propertyName),
                                methodAnnotationMetadata,
                                configurationMetadataBuilder,
                                parameterElement.type.interface
                        )
                        try {
                            visitConfigurationBuilder(
                                    declaringElement,
                                    methodAnnotationMetadata,
                                    parameterElement.type,
                                    getBeanWriter()
                            )
                        } finally {
                            getBeanWriter().visitConfigBuilderEnd()
                        }
                    } else if (declaringClass.getField(propertyName) == null) {
                        if (shouldExclude(configurationMetadata, propertyName)) {
                            return
                        }
                        PropertyMetadata propertyMetadata = configurationMetadataBuilder.visitProperty(
                                concreteClass,
                                declaringClass,
                                parameterElement.type.name,
                                propertyName,
                                null,
                                null
                        )

                        methodAnnotationMetadata = addPropertyMetadata(parameterElement, propertyMetadata)

                        getBeanWriter().visitSetterValue(
                                groovyMethodElement.declaringType,
                                groovyMethodElement,
                                false,
                                true
                        )
                    }
                } else if (NameUtils.isGetterName(methodNode.name)) {
                    if (!getBeanWriter().isValidated()) {
                        getBeanWriter().setValidated(InjectTransform.IS_CONSTRAINT.test(methodAnnotationMetadata))
                    }
                }
            } else {
                def sourceUnit = sourceUnit
                def compilationUnit = this.compilationUnit
                final boolean isConstrained = isDeclaredBean &&
                        methodNode.getParameters()
                                .any { Parameter p ->
                                    AnnotationMetadata annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, p)
                                    InjectTransform.IS_CONSTRAINT.test(annotationMetadata)
                                }
                if (isConstrained) {
                    if (hasInvalidModifiers) {
                        if (isPrivate) {
                            addError("Method annotated with constraints but is declared private. Change the method to be non-private in order for AOP advice to be applied.", methodNode)
                        }
                    } else if (isPublic) {
                        visitExecutableMethod(declaringClass, methodNode, methodAnnotationMetadata, methodName, isPublic)
                    }
                }
            }
        }
    }

    private AnnotationMetadata getAnnotationMetadataHierarchy(AnnotationMetadata methodAnnotationMetadata) {
        return methodAnnotationMetadata instanceof AnnotationMetadataHierarchy ? methodAnnotationMetadata : new AnnotationMetadataHierarchy(concreteClassAnnotationMetadata, methodAnnotationMetadata)
    }

    @CompileStatic
    private void visitBeanFactoryElement(
            ClassNode declaringClass,
            AnnotatedNode annotatedNode,
            AnnotationMetadata methodAnnotationMetadata,
            String elementName) {
        if (annotatedNode instanceof MethodNode && concreteClassAnnotationMetadata.hasDeclaredStereotype(Around)) {
            visitExecutableMethod(declaringClass, annotatedNode, methodAnnotationMetadata, elementName, annotatedNode.isPublic())
        }

        ClassElement producedClassElement
        ClassNode returnType
        Map<String, Map<String, ClassElement>> allTypeArguments
        BeanDefinitionWriter beanMethodWriter
        AnnotationMetadata beanFactoryMetadata = new AnnotationMetadataHierarchy(
                concreteClassAnnotationMetadata,
                methodAnnotationMetadata
        );
        if (annotatedNode instanceof MethodNode) {

            def methodNode = (MethodNode) annotatedNode
            MethodElement factoryMethodElement = elementFactory.newMethodElement(
                    concreteClassElement,
                    methodNode,
                    beanFactoryMetadata
            )
            producedClassElement = factoryMethodElement.genericReturnType
            beanMethodWriter = new BeanDefinitionWriter(
                    factoryMethodElement,
                    OriginatingElements.of(originatingElement),
                    configurationMetadataBuilder,
                    groovyVisitorContext,
                    factoryMethodIndex.getAndIncrement()
            )

            returnType = methodNode.getReturnType()
            allTypeArguments = factoryMethodElement.returnType.allTypeArguments
            beanMethodWriter.visitTypeArguments(allTypeArguments)
            beanMethodWriter.visitBeanFactoryMethod(
                    originatingElement,
                    factoryMethodElement
            )
        } else {
            FieldNode fieldNode
            if (annotatedNode instanceof PropertyNode) {
                fieldNode = ((PropertyNode) annotatedNode).field
            } else {
                fieldNode = annotatedNode as FieldNode
            }
            FieldElement factoryField = elementFactory.newFieldElement(
                    concreteClassElement,
                    fieldNode,
                    beanFactoryMetadata
            )
            producedClassElement = factoryField.genericField
            beanMethodWriter = new BeanDefinitionWriter(
                    factoryField,
                    OriginatingElements.of(originatingElement),
                    configurationMetadataBuilder,
                    groovyVisitorContext,
                    factoryMethodIndex.getAndIncrement()
            )

            returnType = factoryField.type.nativeType as ClassNode
            allTypeArguments = factoryField.type.allTypeArguments
            beanMethodWriter.visitTypeArguments(allTypeArguments)
            beanMethodWriter.visitBeanFactoryField(
                    originatingElement,
                    factoryField
            )
        }

        if (hasAroundStereotype(methodAnnotationMetadata) && !producedClassElement.isAssignable(Interceptor.class)) {

            if (Modifier.isFinal(returnType.modifiers)) {
                addError(
                        "Cannot apply AOP advice to final class. Class must be made non-final to support proxying: $annotatedNode",
                        annotatedNode
                )
                return
            }
            MethodElement constructor = producedClassElement.getPrimaryConstructor().orElse(null)
            if (!producedClassElement.isInterface() && constructor != null && constructor.getParameters().length > 0) {
                final String proxyTargetMode = methodAnnotationMetadata.stringValue(AROUND_TYPE, "proxyTargetMode")
                        .orElseGet(() -> {
                            // temporary workaround until micronaut-test can be upgraded to 3.0
                            if (methodAnnotationMetadata.hasAnnotation("io.micronaut.test.annotation.MockBean")) {
                                return "WARN";
                            } else {
                                return "ERROR";
                            }
                        });
                switch (proxyTargetMode) {
                    case "ALLOW":
                        allowProxyConstruction(constructor)
                        break
                    case "WARN":
                        allowProxyConstruction(constructor)
                        AstMessageUtils.warning(sourceUnit, annotatedNode, "The produced type of a @Factory method has constructor arguments and is proxied. This can lead to unexpected behaviour. See the javadoc for Around.ProxyTargetConstructorMode for more information.")
                        break
                    default:
                        addError("The produced type from a factory which has AOP proxy advice specified must define an accessible no arguments constructor. Proxying types with constructor arguments can lead to unexpected behaviour. See the javadoc for for Around.ProxyTargetConstructorMode for more information and possible solutions.", annotatedNode)
                        return
                }
            }

            AnnotationValue<?>[] interceptorTypeReferences = InterceptedMethodUtil
                    .resolveInterceptorBinding(methodAnnotationMetadata, InterceptorKind.AROUND)
            OptionalValues<Boolean> aopSettings = methodAnnotationMetadata.getValues(AROUND_TYPE, Boolean)
            Map<CharSequence, Object> finalSettings = [:]
            for (key in aopSettings) {
                finalSettings.put(key, aopSettings.get(key).get())
            }
            finalSettings.put(Interceptor.PROXY_TARGET, true)

            AopProxyWriter proxyWriter = new AopProxyWriter(
                    beanMethodWriter,
                    OptionalValues.of(Boolean.class, finalSettings),
                    configurationMetadataBuilder,
                    groovyVisitorContext,
                    interceptorTypeReferences
            )
            proxyWriter.visitTypeArguments(allTypeArguments)
            if (producedClassElement.isInterface()) {
                proxyWriter.visitDefaultConstructor(AnnotationMetadata.EMPTY_METADATA, groovyVisitorContext)
            } else {
                populateProxyWriterConstructor(producedClassElement, proxyWriter, constructor)
            }
            SourceUnit source = this.sourceUnit
            CompilationUnit unit = this.compilationUnit
            ClassElement finalConcreteClassElement = this.concreteClassElement
            new PublicMethodVisitor(source) {
                @Override
                void accept(ClassNode classNode, MethodNode targetBeanMethodNode) {
                    AnnotationMetadata annotationMetadata
                    if (AstAnnotationUtils.isAnnotated(producedClassElement.name, annotatedNode)) {
                        annotationMetadata = AstAnnotationUtils.newBuilder(source, unit)
                                .buildForParent(producedClassElement.name, annotatedNode, targetBeanMethodNode)
                    } else {
                        annotationMetadata = new AnnotationMetadataReference(
                                beanMethodWriter.getBeanDefinitionName() + BeanDefinitionReferenceWriter.REF_SUFFIX,
                                methodAnnotationMetadata
                        )
                    }
                    MethodElement targetMethodElement = elementFactory.newMethodElement(
                            finalConcreteClassElement,
                            targetBeanMethodNode,
                            annotationMetadata
                    )

                    proxyWriter.visitAroundMethod(
                            targetMethodElement.declaringType,
                            targetMethodElement
                    )
                }
            }.accept(returnType)
            beanDefinitionWriters.put(new AnnotatedNode(), proxyWriter)

        }
        Optional<String> preDestroy = methodAnnotationMetadata.getValue(Bean, "preDestroy", String.class)
        if (preDestroy.isPresent()) {
            String destroyMethodName = preDestroy.get()
            MethodNode destroyMethod
            ClassNode producedClassNode = (ClassNode) producedClassElement.nativeType
            SourceUnit source = this.sourceUnit
            new PublicMethodVisitor(source) {
                @Override
                void accept(ClassNode classNode, MethodNode methodNode) {
                    destroyMethod = methodNode
                }
                @Override
                protected boolean isAcceptable(MethodNode node) {
                    return node.name == destroyMethodName && node.parameters.length == 0 && node.isPublic()
                }
            }.accept(producedClassNode)

            if (destroyMethod != null) {
                def destroyMethodElement = elementFactory.newMethodElement(
                        producedClassElement,
                        destroyMethod,
                        AnnotationMetadata.EMPTY_METADATA
                )
                beanMethodWriter.visitPreDestroyMethod(
                        producedClassElement,
                        destroyMethodElement,
                        false,
                        groovyVisitorContext
                )
            } else {
                addError("@Bean method defines a preDestroy method that does not exist or is not public: $destroyMethodName", annotatedNode)
            }
        }
        beanDefinitionWriters.put(annotatedNode, beanMethodWriter)
    }

    private static void allowProxyConstruction(MethodElement constructor) {
        final ParameterElement[] parameters = constructor.getParameters()
        for (ParameterElement parameter : parameters) {
            if (parameter.primitive && !parameter.array) {
                final String name = parameter.getType().getName()
                if ("boolean" == name) {
                    parameter.annotate(Value.class, (builder) -> builder.value(false))
                } else {
                    parameter.annotate(Value.class, (builder) -> builder.value(0))
                }
            } else {
                // allow null
                parameter.annotate(AnnotationUtil.NULLABLE)
                parameter.removeAnnotation(AnnotationUtil.NON_NULL)
            }
        }
    }

    private static AnnotationMetadata addPropertyMetadata(Element element, PropertyMetadata propertyMetadata) {
        element.annotate(
                Property.class.getName(),
                { builder ->
                    builder.member("name", propertyMetadata.path)
                }

        )
        return element.annotationMetadata
    }

    private void visitExecutableMethod(
            ClassNode declaringClass,
            MethodNode methodNode,
            AnnotationMetadata methodAnnotationMetadata,
            String methodName, boolean isPublic) {
        if (declaringClass != ClassHelper.OBJECT_TYPE) {

            boolean isOwningClass = declaringClass == concreteClass
            boolean isParent = declaringClass != concreteClass

            ClassElement declaringElement = elementFactory.newClassElement(declaringClass, concreteClassAnnotationMetadata)
            def methodElement = elementFactory.newMethodElement(concreteClassElement, methodNode, methodAnnotationMetadata)
            Parameter[] resolvedParameters = methodElement.parameters.collect { ParameterElement pe ->
                if (pe.type.isPrimitive()) {
                    return (Parameter) pe.nativeType
                } else {
                    return new Parameter((ClassNode) pe.genericType.nativeType, pe.name)
                }
            } as Parameter[]

            MethodNode overriddenMethod = isParent ? concreteClass.getMethod(methodName, resolvedParameters) : methodNode
            if (!isOwningClass && overriddenMethod != null && overriddenMethod.declaringClass != declaringClass) {
                return
            }

            defineBeanDefinition(concreteClass)

            boolean preprocess = methodAnnotationMetadata.booleanValue(Executable.class, "processOnStartup").orElse(false)
            if (preprocess) {
                getBeanWriter().setRequiresMethodProcessing(true)
            }
            final boolean hasConstraints = methodElement.parameters.any { am ->
                InjectTransform.IS_CONSTRAINT.test(am.annotationMetadata)
            }

            if (hasConstraints) {
                if (!methodAnnotationMetadata.hasStereotype("io.micronaut.validation.Validated")) {
                    methodAnnotationMetadata = addValidated(methodElement)
                }
            }

            boolean executorMethodAdded = false

            if (methodAnnotationMetadata.hasStereotype(Adapter.class)) {
                visitAdaptedMethod(methodNode, methodAnnotationMetadata)
            }

            boolean hasAround = hasConstraints || hasAroundStereotype(methodAnnotationMetadata)
            if ((isAopProxyType && isPublic) || (hasAround && !concreteClass.isAbstract() && !concreteClassElement.isAssignable(Interceptor.class))) {

                boolean hasExplicitAround = hasDeclaredAroundStereotype(methodAnnotationMetadata)

                if (methodNode.isFinal()) {
                    if (hasExplicitAround) {
                        addError("Method defines AOP advice but is declared final. Change the method to be non-final in order for AOP advice to be applied.", methodNode)
                    } else {
                        addError("Public method inherits AOP advice but is declared final. Change the method to be non-final in order for AOP advice to be applied.", methodNode)
                    }
                } else {
                    AnnotationValue<?>[] interceptorTypeReferences = InterceptedMethodUtil
                            .resolveInterceptorBinding(methodAnnotationMetadata, InterceptorKind.AROUND)
                    OptionalValues<Boolean> aopSettings = methodAnnotationMetadata.getValues(AROUND_TYPE, Boolean)
                    AopProxyWriter proxyWriter = resolveProxyWriter(
                            aopSettings,
                            false,
                            interceptorTypeReferences
                    )

                    if (proxyWriter != null && !methodNode.isFinal()) {

                        proxyWriter.visitInterceptorBinding(interceptorTypeReferences)

                        proxyWriter.visitAroundMethod(
                                declaringElement,
                                methodElement
                        )

                        executorMethodAdded = true
                    }
                }
            }

            if (!executorMethodAdded) {
                getBeanWriter().visitExecutableMethod(
                        declaringElement,
                        methodElement,
                        groovyVisitorContext
                )
            }
        }
    }

    static boolean hasDeclaredAroundStereotype(AnnotationMetadata methodAnnotationMetadata) {
        if (methodAnnotationMetadata.hasDeclaredStereotype(AROUND_TYPE)) {
            return true
        } else if (methodAnnotationMetadata.hasDeclaredStereotype(AnnotationUtil.ANN_INTERCEPTOR_BINDINGS)) {
            methodAnnotationMetadata.getDeclaredAnnotationValuesByType(InterceptorBinding)
                    .stream().anyMatch { av ->
                InterceptorKind.AROUND == av.enumValue("kind", InterceptorKind).orElse(null)
            }
        }
    }

    @CompileDynamic
    private AnnotationMetadata addValidated(Element element) {
        return addAnnotation(element, "io.micronaut.validation.Validated")
    }

    @CompileDynamic
    private AnnotationMetadata addAnnotation(Element element, String annotationName) {
        element.annotate(annotationName)
        return element.annotationMetadata
    }

    private AopProxyWriter resolveProxyWriter(
            OptionalValues<Boolean> aopSettings,
            boolean isFactoryType,
            AnnotationValue<?>[] interceptorTypeReferences) {
        AopProxyWriter proxyWriter = (AopProxyWriter) aopProxyWriter
        if (proxyWriter == null) {
            if (getBeanWriter() instanceof BeanDefinitionWriter) {
                proxyWriter = new AopProxyWriter(
                        (BeanDefinitionWriter) getBeanWriter(),
                        aopSettings,
                        configurationMetadataBuilder,
                        groovyVisitorContext,
                        interceptorTypeReferences
                )
            } else {
                // Unexpected: should be unreachable
                throw new IllegalStateException("Internal Error: bean writer not an instance of BeanDefinitionWriter")
            }

            populateProxyWriterConstructor(concreteClassElement, proxyWriter, concreteClassElement.primaryConstructor.orElse(null))
            String beanDefinitionName = getBeanWriter().getBeanDefinitionName()
            if (isFactoryType) {
                proxyWriter.visitSuperBeanDefinitionFactory(beanDefinitionName)
            } else {
                proxyWriter.visitSuperBeanDefinition(beanDefinitionName)
            }

            this.aopProxyWriter = proxyWriter

            beanDefinitionWriters.put(new AnnotatedNode(), proxyWriter)
        }
        proxyWriter
    }

    protected void populateProxyWriterConstructor(ClassElement targetClass, AopProxyWriter proxyWriter, MethodElement constructor) {
        if (constructor != null) {
            if (constructor.parameters.length == 0) {
                proxyWriter.visitDefaultConstructor(
                        AnnotationMetadata.EMPTY_METADATA,
                        groovyVisitorContext
                )
            } else {
                proxyWriter.visitBeanDefinitionConstructor(
                        constructor,
                        constructor.isPrivate(),
                        groovyVisitorContext
                )
            }
        } else {
            ClassNode cn = targetClass.nativeType as ClassNode
            if (cn.declaredConstructors.isEmpty()) {
                proxyWriter.visitDefaultConstructor(AnnotationMetadata.EMPTY_METADATA, groovyVisitorContext)
            } else {
                addError("Class must have at least one non private constructor in order to be a candidate for dependency injection", (ASTNode) targetClass.nativeType)
            }
        }
    }

    protected static boolean isPackagePrivate(AnnotatedNode annotatedNode, int modifiers) {
        return ((!Modifier.isProtected(modifiers) && !Modifier.isPublic(modifiers) && !Modifier.isPrivate(modifiers)) || !annotatedNode.getAnnotations(makeCached(PackageScope)).isEmpty())
    }

    @Override
    void visitField(FieldNode fieldNode) {
        if (fieldNode.name == 'metaClass') return
        int modifiers = fieldNode.modifiers
        if (Modifier.isStatic(modifiers)) {
            return
        }
        if (fieldNode.isSynthetic() && !isPackagePrivate(fieldNode, fieldNode.modifiers)) {
            return
        }
        ClassNode declaringClass = fieldNode.declaringClass
        AnnotationMetadata fieldAnnotationMetadata = AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, fieldNode)
        if (Modifier.isFinal(modifiers) && !fieldAnnotationMetadata.hasStereotype(ConfigurationBuilder)) {
            if (isFactoryClass && fieldAnnotationMetadata.hasDeclaredStereotype(Bean.class)) {
                // field factory for bean
                if (fieldNode.isPrivate() || fieldNode.isProtected()) {
                    AstMessageUtils.error(sourceUnit, fieldNode, "Beans produced from fields cannot be private or protected visibility")
                } else {
                    visitBeanFactoryElement(
                            concreteClass,
                            fieldNode,
                            fieldAnnotationMetadata,
                            fieldNode.name
                    )
                }
            }
            return
        } else if (isFactoryClass && fieldAnnotationMetadata.hasDeclaredStereotype(Bean.class)) {
            // field factory for bean
            if (fieldNode.isPrivate() || fieldNode.isProtected()) {
                AstMessageUtils.error(sourceUnit, fieldNode, "Beans produced from fields cannot be private or protected visibility")
            }
            return
        }
        boolean isInject = isFieldInjected(fieldNode, fieldAnnotationMetadata)
        boolean isValue = isValueInjection(fieldNode, fieldAnnotationMetadata)
        FieldElement fieldElement = elementFactory.newFieldElement(fieldNode, fieldAnnotationMetadata)

        if ((isInject || isValue) && declaringClass.getProperty(fieldNode.getName()) == null) {
            defineBeanDefinition(concreteClass)
            if (!fieldNode.isStatic()) {

                boolean isPrivate = Modifier.isPrivate(modifiers)
                boolean requiresReflection = isPrivate || isInheritedAndNotPublic(fieldNode, fieldNode.declaringClass, modifiers)
                if (!getBeanWriter().isValidated()) {
                    getBeanWriter().setValidated(InjectTransform.IS_CONSTRAINT.test(fieldAnnotationMetadata))
                }
                String fieldName = fieldNode.name
                ClassElement fieldType = fieldElement.type
                if (isValue) {
                    if (isConfigurationProperties && fieldAnnotationMetadata.hasStereotype(ConfigurationBuilder.class)) {
                        if(requiresReflection) {
                            // Using the field would throw a IllegalAccessError, use the method instead
                            String fieldGetterName = NameUtils.getterNameFor(fieldName)
                            MethodNode getterMethod = declaringClass.methods?.find { it.name == fieldGetterName}
                            if(getterMethod != null) {
                                getBeanWriter().visitConfigBuilderMethod(
                                        fieldType,
                                        getterMethod.name,
                                        fieldAnnotationMetadata,
                                        configurationMetadataBuilder,
                                        fieldType.interface
                                )
                            } else {
                                addError("ConfigurationBuilder applied to a non accessible (private or package-private/protected in a different package) field must have a corresponding non-private getter method.", fieldNode)
                            }
                        } else {
                            getBeanWriter().visitConfigBuilderField(fieldType, fieldName, fieldAnnotationMetadata, configurationMetadataBuilder, fieldNode.type.interface)
                        }
                        try {
                            visitConfigurationBuilder(
                                    fieldElement.declaringType,
                                    fieldAnnotationMetadata,
                                    fieldElement.type, getBeanWriter()
                            )
                        } finally {
                            getBeanWriter().visitConfigBuilderEnd()
                        }
                    } else {
                        if (isConfigurationProperties) {
                            if (shouldExclude(configurationMetadata, fieldName)) {
                                return
                            }
                            PropertyMetadata propertyMetadata = configurationMetadataBuilder.visitProperty(
                                    concreteClass,
                                    declaringClass,
                                    fieldNode.type.name,
                                    fieldName,
                                    null, // TODO: fix groovy doc support
                                    null
                            )
                            fieldElement.annotate(Property.class.getName(), {builder  ->
                                builder.member("name", propertyMetadata.path)
                            })
                        }
                        getBeanWriter().visitFieldValue(
                                fieldElement.declaringType,
                                fieldElement,
                                requiresReflection,
                                isConfigurationProperties
                        )
                    }
                } else {
                    getBeanWriter().visitFieldInjectionPoint(
                            fieldElement.declaringType,
                            fieldElement,
                            requiresReflection
                    )
                }
            }
        }
    }

    @Override
    @CompileDynamic
    void visitProperty(PropertyNode propertyNode) {
        FieldNode fieldNode = propertyNode.field
        if (fieldNode.name == 'metaClass') return
        def modifiers = propertyNode.getModifiers()
        if (Modifier.isStatic(modifiers)) {
            if (isFactoryClass && AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, fieldNode).hasDeclaredStereotype(Bean.class)) {
                AstMessageUtils.error(sourceUnit, propertyNode, "Beans produced from fields cannot be static")
            }
            return
        }
        AnnotationMetadata fieldAnnotationMetadata = AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, fieldNode)
        if (Modifier.isFinal(modifiers) && !fieldAnnotationMetadata.hasStereotype(ConfigurationBuilder)) {
            if (isFactoryClass && fieldAnnotationMetadata.hasDeclaredStereotype(Bean.class)) {
                // field factory for bean
                if (propertyNode.isPrivate()) {
                    AstMessageUtils.error(sourceUnit, propertyNode, "Beans produced from fields cannot be private")
                } else {
                    visitFactoryProperty(propertyNode, fieldNode, fieldAnnotationMetadata)

                }
            }
            return
        }
        boolean isInject = isFieldInjected(fieldNode, fieldAnnotationMetadata)
        boolean isValue = isValueInjection(fieldNode, fieldAnnotationMetadata)

        String propertyName = propertyNode.name
        if (!propertyNode.isStatic() && (isInject || isValue)) {
            defineBeanDefinition(concreteClass)
            FieldElement fieldElement = elementFactory.newFieldElement(
                    fieldNode,
                    fieldAnnotationMetadata
            )

            if (!getBeanWriter().isValidated()) {
                getBeanWriter().setValidated(InjectTransform.IS_CONSTRAINT.test(fieldAnnotationMetadata))
            }

            if (isInject) {
                ParameterElement parameterElement = elementFactory.newParameterElement(fieldElement, fieldAnnotationMetadata)
                MethodElement methodElement = MethodElement.of(
                        fieldElement.declaringType,
                        fieldElement,
                        PrimitiveElement.VOID,
                        PrimitiveElement.VOID,
                        getSetterName(propertyName),
                        parameterElement
                )
                getBeanWriter().visitMethodInjectionPoint(
                        fieldElement.declaringType,
                        methodElement,
                        false,
                        groovyVisitorContext
                )
            } else if (isValue) {
                if (isConfigurationProperties && fieldAnnotationMetadata.hasStereotype(ConfigurationBuilder.class)) {
                    getBeanWriter().visitConfigBuilderMethod(
                            fieldElement.type,
                            getGetterName(propertyNode),
                            fieldAnnotationMetadata,
                            configurationMetadataBuilder,
                            fieldNode.type.interface)
                    try {
                        visitConfigurationBuilder(
                                fieldElement.declaringType,
                                fieldAnnotationMetadata,
                                fieldElement.type,
                                getBeanWriter()
                        )
                    } finally {
                        getBeanWriter().visitConfigBuilderEnd()
                    }
                } else {
                    if (isConfigurationProperties) {
                        if (shouldExclude(configurationMetadata, propertyName)) {
                            return
                        }
                        PropertyMetadata propertyMetadata = configurationMetadataBuilder.visitProperty(
                                concreteClass,
                                fieldNode.declaringClass,
                                propertyNode.type.name,
                                propertyNode.name,
                                null, // TODO: fix groovy doc support
                                null
                        )
                        fieldElement.annotate(Property.class.getName(), { builder ->
                            builder.member("name", propertyMetadata.path)
                        })
                        fieldAnnotationMetadata = fieldElement.annotationMetadata
                    }
                    def setterName = GeneralUtils.getSetterName(propertyName)

                    ParameterElement parameterElement = elementFactory.newParameterElement(fieldElement, fieldAnnotationMetadata)
                    def methodElement = MethodElement.of(
                            fieldElement.declaringType,
                            fieldAnnotationMetadata,
                            PrimitiveElement.VOID,
                            PrimitiveElement.VOID,
                            setterName,
                            parameterElement
                    )
                    getBeanWriter().visitSetterValue(
                            fieldElement.declaringType,
                            methodElement,
                            false,
                            isConfigurationProperties
                    )
                }
            }
        } else if (isAopProxyType && !propertyNode.isStatic()) {
            AopProxyWriter aopWriter = (AopProxyWriter) aopProxyWriter
            if (aopProxyWriter != null) {
                AnnotationMetadata fieldMetadata = AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, propertyNode.field)
                FieldElement fieldElement = elementFactory.newFieldElement(
                        fieldNode,
                        fieldMetadata
                )
                ParameterElement parameterElement = elementFactory.newParameterElement(fieldElement, fieldAnnotationMetadata)
                def methodAnnotationMetadata = new AnnotationMetadataHierarchy(
                        concreteClassAnnotationMetadata,
                        fieldAnnotationMetadata
                )
                MethodElement setterElement = MethodElement.of(
                        fieldElement.declaringType,
                        methodAnnotationMetadata,
                        PrimitiveElement.VOID,
                        PrimitiveElement.VOID,
                        getSetterName(propertyName),
                        parameterElement
                )
                aopWriter.visitAroundMethod(
                        fieldElement.declaringType,
                        setterElement
                )

                // also visit getter to ensure proxying
                MethodElement getterElement = MethodElement.of(
                        fieldElement.declaringType,
                        methodAnnotationMetadata,
                        fieldElement.type,
                        fieldElement.genericType,
                        getGetterName(propertyNode)
                )
                aopWriter.visitAroundMethod(
                        fieldElement.declaringType,
                        getterElement
                )
            }
        } else if (isFactoryClass && fieldAnnotationMetadata.hasDeclaredStereotype(Bean.class)) {
            // field factory for bean
            if (propertyNode.isPrivate()) {
                AstMessageUtils.error(sourceUnit, propertyNode, "Beans produced from fields cannot be private");
            } else {
                visitFactoryProperty(propertyNode, fieldNode, fieldAnnotationMetadata)
            }
        }
    }

    private boolean isFieldInjected(FieldNode fieldNode, AnnotationMetadata fieldAnnotationMetadata) {
        fieldNode != null && (fieldAnnotationMetadata.hasStereotype(AnnotationUtil.INJECT) || (fieldAnnotationMetadata.hasDeclaredStereotype(AnnotationUtil.QUALIFIER)) && !fieldAnnotationMetadata.hasDeclaredAnnotation(Bean))
    }

    private void visitFactoryProperty(PropertyNode propertyNode, FieldNode fieldNode, AnnotationMetadata fieldAnnotationMetadata) {

        def getterNode = new MethodNode(
                getGetterName(propertyNode),
                Modifier.PUBLIC,
                fieldNode.type,
                new Parameter[0],
                null,
                null
        )
        getterNode.declaringClass = concreteClass
        visitBeanFactoryElement(
                concreteClass,
                getterNode,
                fieldAnnotationMetadata,
                getterNode.name
        )
    }

    private boolean isValueInjection(FieldNode fieldNode, AnnotationMetadata fieldAnnotationMetadata) {
        fieldNode != null && (
                fieldAnnotationMetadata.hasStereotype(Value) ||
                        fieldAnnotationMetadata.hasStereotype(Property) ||
                        isConfigurationProperties
        )
    }

    protected boolean isInheritedAndNotPublic(AnnotatedNode annotatedNode, ClassNode declaringClass, int modifiers) {
        return declaringClass != concreteClass &&
                declaringClass.packageName != concreteClass.packageName &&
                ((Modifier.isProtected(modifiers) || !Modifier.isPublic(modifiers)) || !annotatedNode.getAnnotations(makeCached(PackageScope)).isEmpty())
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    private void defineBeanDefinition(ClassNode classNode) {
        if (!beanDefinitionWriters.containsKey(classNode)) {
            if (classNode.packageName == null) {
                addError("Micronaut beans cannot be in the default package", classNode)
                return
            }
            AnnotationMetadata annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, classNode)
            if (configurationMetadata != null) {
                String existingPrefix = annotationMetadata.getValue(
                        ConfigurationReader.class,
                        "prefix", String.class)
                        .orElse("")

                def computedPrefix = StringUtils.isNotEmpty(existingPrefix) ? existingPrefix + "." + configurationMetadata.getName() : configurationMetadata.getName()
                annotationMetadata = DefaultAnnotationMetadata.mutateMember(
                        annotationMetadata,
                        ConfigurationReader.class.getName(),
                        "prefix",
                        computedPrefix
                )
            }

            ClassElement groovyClassElement = elementFactory.newClassElement(
                    classNode,
                    annotationMetadata
            )

            if (annotationMetadata.hasStereotype(Singleton)) {
                addError("Class annotated with groovy.lang.Singleton instead of jakarta.inject.Singleton. Import jakarta.inject.Singleton to use Micronaut Dependency Injection.", classNode)
            }

            beanWriter = new BeanDefinitionWriter(groovyClassElement, configurationMetadataBuilder, groovyVisitorContext)
            beanWriter.visitTypeArguments(groovyClassElement.allTypeArguments)
            beanDefinitionWriters.put(classNode, beanWriter)

            MethodElement constructor = groovyClassElement.getPrimaryConstructor().orElse(null)

            if (constructor != null) {
                if (constructor.parameters.length == 0) {

                    beanWriter.visitDefaultConstructor(AnnotationMetadata.EMPTY_METADATA, groovyVisitorContext)
                } else {
                    def constructorMetadata = constructor.annotationMetadata
                    final boolean isConstructBinding = constructorMetadata.hasDeclaredStereotype(ConfigurationInject.class)
                    if (isConstructBinding) {
                        this.configurationMetadata = configurationMetadataBuilder.visitProperties(
                                concreteClass,
                                null)
                    }
                    beanWriter.visitBeanDefinitionConstructor(constructor, constructor.isPrivate(), groovyVisitorContext)
                }

            } else {
                ClassNode cn = groovyClassElement.nativeType as ClassNode
                if (cn.declaredConstructors.isEmpty()) {
                    beanWriter.visitDefaultConstructor(AnnotationMetadata.EMPTY_METADATA, groovyVisitorContext)
                } else {
                    addError("Class must have at least one non private constructor in order to be a candidate for dependency injection", classNode)
                }
            }
        } else {
            beanWriter = beanDefinitionWriters.get(classNode)
        }
    }

    @CompileDynamic
    private void visitAdaptedMethod(MethodNode method, AnnotationMetadata methodAnnotationMetadata) {
        if (methodAnnotationMetadata instanceof AnnotationMetadataHierarchy) {
            methodAnnotationMetadata = ((AnnotationMetadataHierarchy) methodAnnotationMetadata).getDeclaredMetadata();
        }
        Optional<ClassNode> adaptedType = methodAnnotationMetadata.getValue(Adapter.class, String.class).flatMap({ String s ->
            ClassNode cn = sourceUnit.AST.classes.find { ClassNode cn -> cn.name == s }
            if (cn != null) {
                return Optional.of(cn)
            }
            def type = ClassUtils.forName(s, InjectTransform.classLoader).orElse(null)
            if (type != null) {
                return Optional.of(ClassHelper.make(type))
            }
            return Optional.empty()
        } as Function<String, Optional<ClassNode>>)

        if (adaptedType.isPresent()) {
            ClassNode typeToImplement = adaptedType.get()
            boolean isInterface = typeToImplement.isInterface()
            if (isInterface) {

                String packageName = concreteClass.packageName
                String declaringClassSimpleName = concreteClass.nameWithoutPackage
                String beanClassName = generateAdaptedMethodClassName(declaringClassSimpleName, typeToImplement, method)

                AopProxyWriter aopProxyWriter = new AopProxyWriter(
                        packageName,
                        beanClassName,
                        true,
                        false,
                        originatingElement,
                        new AnnotationMetadataHierarchy(concreteClassAnnotationMetadata, methodAnnotationMetadata),
                        [elementFactory.newClassElement(typeToImplement, AnnotationMetadata.EMPTY_METADATA)] as ClassElement[],
                        groovyVisitorContext,
                        configurationMetadataBuilder,
                        null
                )

                aopProxyWriter.visitDefaultConstructor(methodAnnotationMetadata, groovyVisitorContext)

                beanDefinitionWriters.put(ClassHelper.make(packageName + '.' + beanClassName), aopProxyWriter)

                ClassElement typeToImplementElement = elementFactory.newClassElement(
                        typeToImplement,
                        methodAnnotationMetadata
                )
                Map<String, ClassElement> typeVariables = typeToImplementElement.getTypeArguments();

                InjectVisitor thisVisitor = this
                SourceUnit source = this.sourceUnit
                CompilationUnit unit = this.compilationUnit
                MethodElement sourceMethod = elementFactory.newMethodElement(
                        concreteClassElement,
                        method,
                        methodAnnotationMetadata
                )
                PublicAbstractMethodVisitor visitor = new PublicAbstractMethodVisitor(source, unit) {
                    boolean first = true

                    @Override
                    void accept(ClassNode classNode, MethodNode targetMethod) {
                        if (!first) {
                            thisVisitor.addError("Interface to adapt [" + typeToImplement + "] is not a SAM type. More than one abstract method declared.", (MethodNode)method)
                            return
                        }
                        first = false
                        MethodElement targetMethodElement = elementFactory.newMethodElement(
                                typeToImplementElement,
                                targetMethod,
                                AstAnnotationUtils.getAnnotationMetadata(sourceUnit, compilationUnit, targetMethod)
                        )
                        ParameterElement[] sourceParams = sourceMethod.getParameters();
                        ParameterElement[] targetParams = targetMethodElement.getParameters();
                        Parameter[] targetParameters = targetMethod.getParameters()
                        if (targetParameters.size() == sourceParams.size()) {

                            int i = 0
                            Map<String, ClassElement> genericTypes = [:]
                            for (Parameter targetElement in targetParameters) {

                                ParameterElement sourceElement = sourceParams[i]

                                ClassElement targetType = targetParams[i].getType()
                                ClassElement sourceType = sourceElement.getType()

                                if (targetElement.type.isGenericsPlaceHolder()) {
                                    GenericsType[] targetGenerics = targetElement.type.genericsTypes

                                    if (targetGenerics) {
                                        String variableName = targetGenerics[0].name
                                        if (typeVariables.containsKey(variableName)) {
                                            targetType = typeVariables.get(variableName)

                                            genericTypes.put(variableName, sourceType)
                                        }
                                    }
                                }

                                if (!sourceType.isAssignable(targetType.getName())) {
                                    thisVisitor.addError("Cannot adapt method [${method.declaringClass.name}.$method.name(..)] to target method [${targetMethod.declaringClass.name}.$targetMethod.name(..)]. Argument type [" + sourceType.name + "] is not a subtype of type [$targetType.name] at position $i.", (MethodNode)method)
                                    return
                                }

                                i++
                            }

                            if (!genericTypes.isEmpty()) {
                                Map<String, Map<String, ClassElement>> typeData = Collections.<String, Map<String, ClassElement>>singletonMap(
                                        typeToImplement.name,
                                        genericTypes
                                )
                                aopProxyWriter.visitTypeArguments(
                                        typeData
                                )
                            }

                            String qualifier = concreteClassAnnotationMetadata.getValue(AnnotationUtil.NAMED, String.class).orElse(null)
                            MethodElement groovyMethodElement = elementFactory.newMethodElement(
                                    concreteClassElement,
                                    targetMethod,
                                    methodAnnotationMetadata
                            )

                            AnnotationClassValue[] adaptedArgumentTypes = new AnnotationClassValue[sourceParams.length]
                            int j = 0
                            for (ParameterElement ve in sourceMethod.parameters) {
                                adaptedArgumentTypes[j] = new AnnotationClassValue(ve.type.name)
                                j++
                            }
                            groovyMethodElement.annotate(Adapter.class, { builder ->
                                builder.member(Adapter.InternalAttributes.ADAPTED_BEAN, new AnnotationClassValue<>(concreteClass.name))
                                builder.member(Adapter.InternalAttributes.ADAPTED_METHOD, method.name)
                                builder.member(Adapter.InternalAttributes.ADAPTED_ARGUMENT_TYPES, adaptedArgumentTypes)
                                if (StringUtils.isNotEmpty(qualifier)) {
                                    builder.member(Adapter.InternalAttributes.ADAPTED_QUALIFIER, qualifier)
                                }
                            })

                            ClassElement declaringElement = elementFactory.newClassElement(
                                    targetMethod.declaringClass,
                                    AnnotationMetadata.EMPTY_METADATA
                            )
                            aopProxyWriter.visitAroundMethod(
                                    declaringElement,
                                    groovyMethodElement
                            )


                        } else {
                            thisVisitor.addError(
                                    "Cannot adapt method [${method.declaringClass.name}.$method.name(..)] to target method [${targetMethod.declaringClass.name}.$targetMethod.name(..)]. Argument lengths don't match.",
                                    (MethodNode) method
                            )
                        }
                    }
                }

                visitor.accept(typeToImplement)
            }

        }
    }

    private String generateAdaptedMethodClassName(String declaringClassSimpleName, ClassNode typeToImplement, MethodNode method) {
        String rootName = declaringClassSimpleName + '$' + typeToImplement.nameWithoutPackage + '$' + method.getName()
        return rootName + adaptedMethodIndex.incrementAndGet()
    }

    private void visitConfigurationBuilder(ClassElement declaringClass,
                                           AnnotationMetadata annotationMetadata,
                                           ClassElement classNode,
                                           BeanDefinitionVisitor writer) {
        Boolean allowZeroArgs = annotationMetadata.getValue(ConfigurationBuilder.class, "allowZeroArgs", Boolean.class).orElse(false)
        List<String> prefixes = Arrays.asList(annotationMetadata.getValue(ConfigurationBuilder.class, "prefixes", String[].class).orElse(["set"] as String[]))
        String configurationPrefix = annotationMetadata.getValue(ConfigurationBuilder.class, String.class)
                .map({ value -> value + "."}).orElse("")
        Set<String> includes = annotationMetadata.getValue(ConfigurationBuilder.class, "includes", Set.class).orElse(Collections.emptySet())
        Set<String> excludes = annotationMetadata.getValue(ConfigurationBuilder.class, "excludes", Set.class).orElse(Collections.emptySet())

        SourceUnit source = this.sourceUnit
        CompilationUnit compilationUnit = this.compilationUnit
        ClassElement concreteClassElement = this.concreteClassElement
        PublicMethodVisitor visitor = new PublicMethodVisitor(source) {
            @Override
            void accept(ClassNode cn, MethodNode method) {
                String name = method.getName()
                String prefix = getMethodPrefix(name)
                String propertyName = NameUtils.decapitalize(name.substring(prefix.length()))
                if (shouldExclude(includes, excludes, propertyName)) {
                    return
                }
                MethodElement groovyMethodElement = elementFactory.newMethodElement(
                        concreteClassElement,
                        method,
                        AstAnnotationUtils.getAnnotationMetadata(source, compilationUnit, method)
                )
                ParameterElement[] params = groovyMethodElement.parameters
                int paramCount = params.size()
                if (paramCount < 2) {
                    ParameterElement paramType = params.size() == 1 ? params[0] : null

                    PropertyMetadata metadata = configurationMetadataBuilder.visitProperty(
                            concreteClassElement.nativeType as ClassNode,
                            declaringClass.nativeType as ClassNode,
                            paramType?.type?.name,
                            configurationPrefix + propertyName,
                            null,
                            null
                    )

                    writer.visitConfigBuilderMethod(
                            prefix,
                            groovyMethodElement.returnType,
                            name,
                            paramType?.type,
                            paramType?.type?.typeArguments,
                            metadata.path
                    )

                } else if (paramCount == 2) {
                    // check the params are a long and a TimeUnit
                    ParameterElement first = params[0]
                    ParameterElement second = params[1]

                    PropertyMetadata metadata = configurationMetadataBuilder.visitProperty(
                            concreteClassElement.nativeType as ClassNode,
                            declaringClass.nativeType as ClassNode,
                            Duration.class.name,
                            configurationPrefix + propertyName,
                            null,
                            null
                    )

                    if (second.type.name == TimeUnit.class.name && first.type.name == "long") {
                        writer.visitConfigBuilderDurationMethod(
                                prefix,
                                groovyMethodElement.returnType,
                                name,
                                metadata.path
                        )
                    }
                }
            }

            @Override
            protected boolean isAcceptable(MethodNode node) {
                // ignore deprecated methods
                if (AstAnnotationUtils.hasStereotype(source, compilationUnit, node, Deprecated.class)) {
                    return false
                }
                int paramCount = node.getParameters().size()
                ((paramCount > 0 && paramCount < 3) || (allowZeroArgs && paramCount == 0)) &&
                        super.isAcceptable(node) &&
                        isPrefixedWith(node.getName())
            }

            private boolean isPrefixedWith(String name) {
                for (String prefix : prefixes) {
                    if (name.startsWith(prefix)) return true
                }
                return false
            }

            private String getMethodPrefix(String methodName) {
                for (String prefix : prefixes) {
                    if (methodName.startsWith(prefix)) {
                        return prefix
                    }
                }
                return methodName
            }
        }

        visitor.accept(classNode.nativeType as ClassNode)
    }

    private boolean shouldExclude(Set<String> includes, Set<String> excludes, String propertyName) {
        if (!includes.isEmpty() && !includes.contains(propertyName)) {
            return true
        }
        if (!excludes.isEmpty() && excludes.contains(propertyName)) {
            return true
        }
        return false
    }

    private boolean shouldExclude(ConfigurationMetadata configurationMetadata, String propertyName) {
        return shouldExclude(configurationMetadata.getIncludes(), configurationMetadata.getExcludes(), propertyName)
    }
}
