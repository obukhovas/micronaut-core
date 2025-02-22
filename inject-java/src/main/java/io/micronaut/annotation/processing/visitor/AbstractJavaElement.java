/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.annotation.processing.visitor;

import io.micronaut.annotation.processing.AnnotationUtils;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataDelegate;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder;
import io.micronaut.inject.ast.*;

import javax.lang.model.element.*;
import javax.lang.model.element.Element;
import javax.lang.model.type.*;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static javax.lang.model.element.Modifier.*;

/**
 * An abstract class for other elements to extend from.
 *
 * @author James Kleeh
 * @author graemerocher
 * @since 1.0
 */
public abstract class AbstractJavaElement implements io.micronaut.inject.ast.Element, AnnotationMetadataDelegate {

    private final Element element;
    private final JavaVisitorContext visitorContext;
    private AnnotationMetadata annotationMetadata;

    /**
     * @param element            The {@link Element}
     * @param annotationMetadata The Annotation metadata
     * @param visitorContext     The Java visitor context
     */
    AbstractJavaElement(Element element, AnnotationMetadata annotationMetadata, JavaVisitorContext visitorContext) {
        this.element = element;
        this.annotationMetadata = annotationMetadata;
        this.visitorContext = visitorContext;
    }

    @NonNull
    @Override
    public <T extends Annotation> io.micronaut.inject.ast.Element annotate(@NonNull String annotationType, @NonNull Consumer<AnnotationValueBuilder<T>> consumer) {
        ArgumentUtils.requireNonNull("annotationType", annotationType);
        ArgumentUtils.requireNonNull("consumer", consumer);

        final AnnotationValueBuilder<T> builder = AnnotationValue.builder(annotationType);
        consumer.accept(builder);
        final AnnotationValue<T> av = builder.build();
        AnnotationUtils annotationUtils = visitorContext
                .getAnnotationUtils();
        this.annotationMetadata = annotationUtils
                .newAnnotationBuilder()
                .annotate(annotationMetadata, av);

        updateMetadataCaches();
        return this;
    }

    @Override
    public <T extends Annotation> io.micronaut.inject.ast.Element annotate(AnnotationValue<T> annotationValue) {
        ArgumentUtils.requireNonNull("annotationValue", annotationValue);

        AnnotationUtils annotationUtils = visitorContext
                .getAnnotationUtils();
        this.annotationMetadata = annotationUtils
                .newAnnotationBuilder()
                .annotate(annotationMetadata, annotationValue);

        updateMetadataCaches();
        return this;
    }

    @Override
    public io.micronaut.inject.ast.Element removeAnnotation(@NonNull String annotationType) {
        ArgumentUtils.requireNonNull("annotationType", annotationType);
        try {
            AnnotationUtils annotationUtils = visitorContext
                    .getAnnotationUtils();
            this.annotationMetadata = annotationUtils
                    .newAnnotationBuilder()
                    .removeAnnotation(annotationMetadata, annotationType);
            return this;
        } finally {
            updateMetadataCaches();
        }
    }

    @Override
    public <T extends Annotation> io.micronaut.inject.ast.Element removeAnnotationIf(@NonNull Predicate<AnnotationValue<T>> predicate) {
        //noinspection ConstantConditions
        if (predicate != null) {
            AnnotationUtils annotationUtils = visitorContext
                    .getAnnotationUtils();
            this.annotationMetadata = annotationUtils
                    .newAnnotationBuilder()
                    .removeAnnotationIf(annotationMetadata, predicate);
            return this;
        }
        return this;
    }

    @Override
    public io.micronaut.inject.ast.Element removeStereotype(@NonNull String annotationType) {
        ArgumentUtils.requireNonNull("annotationType", annotationType);
        try {
            AnnotationUtils annotationUtils = visitorContext
                    .getAnnotationUtils();
            this.annotationMetadata = annotationUtils
                    .newAnnotationBuilder()
                    .removeStereotype(annotationMetadata, annotationType);
            return this;
        } finally {
            updateMetadataCaches();
        }
    }

    private void updateMetadataCaches() {
        String declaringTypeName = resolveDeclaringTypeName();
        AbstractAnnotationMetadataBuilder.addMutatedMetadata(declaringTypeName, element, annotationMetadata);
        AnnotationUtils.invalidateMetadata(element);
    }

    private String resolveDeclaringTypeName() {
        String declaringTypeName;
        if (this instanceof MemberElement) {
            declaringTypeName = ((MemberElement) this).getOwningType().getName();
        } else if (this instanceof ParameterElement) {
            TypeElement typeElement = visitorContext.getModelUtils().classElementFor((Element) this.getNativeType());
            if (typeElement == null) {
                declaringTypeName = getName();
            } else {
                declaringTypeName = typeElement.getQualifiedName().toString();
            }
        } else {
            declaringTypeName = getName();
        }
        return declaringTypeName;
    }

    @Override
    public boolean isPackagePrivate() {
        Set<Modifier> modifiers = element.getModifiers();
        return !(modifiers.contains(PUBLIC)
                || modifiers.contains(PROTECTED)
                || modifiers.contains(PRIVATE));
    }

    @Override
    public String getName() {
        return element.getSimpleName().toString();
    }

    @Override
    public Set<ElementModifier> getModifiers() {
        return this.element
                .getModifiers().stream()
                .map(m -> ElementModifier.valueOf(m.name()))
                .collect(Collectors.toSet());
    }

    @Override
    public boolean isAbstract() {
        return hasModifier(Modifier.ABSTRACT);
    }

    @Override
    public boolean isStatic() {
        return hasModifier(Modifier.STATIC);
    }

    @Override
    public boolean isPublic() {
        return hasModifier(Modifier.PUBLIC);
    }

    @Override
    public boolean isPrivate() {
        return hasModifier(Modifier.PRIVATE);
    }

    @Override
    public boolean isFinal() {
        return hasModifier(Modifier.FINAL);
    }

    @Override
    public boolean isProtected() {
        return hasModifier(Modifier.PROTECTED);
    }

    @Override
    public Object getNativeType() {
        return element;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return annotationMetadata;
    }

    @Override
    public String toString() {
        return element.toString();
    }

    /**
     * Returns a class element with aligned generic information.
     * @param typeMirror The type mirror
     * @param visitorContext The visitor context
     * @param declaredGenericInfo The declared generic info
     * @return The class element
     */
    protected @NonNull ClassElement parameterizedClassElement(
            TypeMirror typeMirror,
            JavaVisitorContext visitorContext,
            Map<String, Map<String, TypeMirror>> declaredGenericInfo) {
        return mirrorToClassElement(
                typeMirror,
                visitorContext,
                declaredGenericInfo,
                true);
    }

    /**
     * Obtain the ClassElement for the given mirror.
     *
     * @param returnType The return type
     * @param visitorContext The visitor context
     * @return The class element
     */
    protected @NonNull ClassElement mirrorToClassElement(TypeMirror returnType, JavaVisitorContext visitorContext) {
        return mirrorToClassElement(returnType, visitorContext, Collections.emptyMap(), true);
    }

    /**
     * Obtain the ClassElement for the given mirror.
     *
     * @param returnType The return type
     * @param visitorContext The visitor context
     * @param genericsInfo The generic information.
     * @return The class element
     */
    protected @NonNull ClassElement mirrorToClassElement(TypeMirror returnType, JavaVisitorContext visitorContext, Map<String, Map<String, TypeMirror>> genericsInfo) {
        return mirrorToClassElement(returnType, visitorContext, genericsInfo, true);
    }

    /**
     * Obtain the ClassElement for the given mirror.
     *
     * @param returnType The return type
     * @param visitorContext The visitor context
     * @param genericsInfo The generic information.
     * @param includeTypeAnnotations Whether to include type level annotations in the metadata for the element
     * @return The class element
     */
    protected @NonNull ClassElement mirrorToClassElement(TypeMirror returnType, JavaVisitorContext visitorContext, Map<String, Map<String, TypeMirror>> genericsInfo, boolean includeTypeAnnotations) {
        return mirrorToClassElement(returnType, visitorContext, genericsInfo, includeTypeAnnotations, returnType instanceof TypeVariable);
    }

    /**
     * Obtain the ClassElement for the given mirror.
     *
     * @param returnType The return type
     * @param visitorContext The visitor context
     * @param genericsInfo The generic information.
     * @param includeTypeAnnotations Whether to include type level annotations in the metadata for the element
     * @param isTypeVariable is the type a type variable
     * @return The class element
     */
    protected @NonNull ClassElement mirrorToClassElement(
            TypeMirror returnType,
            JavaVisitorContext visitorContext,
            Map<String, Map<String, TypeMirror>> genericsInfo,
            boolean includeTypeAnnotations,
            boolean isTypeVariable) {
        if (genericsInfo == null) {
            genericsInfo = Collections.emptyMap();
        }
        if (returnType instanceof NoType) {
            return PrimitiveElement.VOID;
        } else if (returnType instanceof DeclaredType) {
            DeclaredType dt = (DeclaredType) returnType;
            Element e = dt.asElement();
            //Declared types can wrap other types, like primitives
            if (e.asType() instanceof DeclaredType) {
                List<? extends TypeMirror> typeArguments = dt.getTypeArguments();
                if (e instanceof TypeElement) {
                    TypeElement typeElement = (TypeElement) e;
                    Map<String, TypeMirror> boundGenerics = resolveBoundGenerics(visitorContext, genericsInfo);
                    AnnotationUtils annotationUtils = visitorContext
                                                        .getAnnotationUtils();
                    AnnotationMetadata newAnnotationMetadata;
                    List<? extends AnnotationMirror> annotationMirrors = dt.getAnnotationMirrors();
                    if (!annotationMirrors.isEmpty()) {
                        newAnnotationMetadata = annotationUtils.newAnnotationBuilder().buildDeclared(typeElement, annotationMirrors, includeTypeAnnotations);
                    } else {
                        newAnnotationMetadata = includeTypeAnnotations ? annotationUtils.getAnnotationMetadata(typeElement) : AnnotationMetadata.EMPTY_METADATA;
                    }
                    if (visitorContext.getModelUtils().resolveKind(typeElement, ElementKind.ENUM).isPresent()) {
                        return new JavaEnumElement(
                                typeElement,
                                newAnnotationMetadata,
                                visitorContext
                        );
                    } else {

                        genericsInfo = visitorContext.getGenericUtils().alignNewGenericsInfo(
                                typeElement,
                                typeArguments,
                                boundGenerics
                        );
                        return new JavaClassElement(
                                typeElement,
                                newAnnotationMetadata,
                                visitorContext,
                                typeArguments,
                                genericsInfo,
                                isTypeVariable
                        );
                    }
                }
            } else {
                return mirrorToClassElement(e.asType(), visitorContext, genericsInfo, includeTypeAnnotations);
            }
        } else if (returnType instanceof TypeVariable) {
            TypeVariable tv = (TypeVariable) returnType;
            TypeMirror upperBound = tv.getUpperBound();
            Map<String, TypeMirror> boundGenerics = resolveBoundGenerics(visitorContext, genericsInfo);

            TypeMirror bound = boundGenerics.get(tv.toString());
            if (bound != null && bound != tv) {
                return mirrorToClassElement(bound, visitorContext, genericsInfo, includeTypeAnnotations, true);
            } else {
                // type variable is still free.
                List<? extends TypeMirror> boundsUnresolved = upperBound instanceof IntersectionType ?
                        ((IntersectionType) upperBound).getBounds() :
                        Collections.singletonList(upperBound);
                Map<String, Map<String, TypeMirror>> finalGenericsInfo = genericsInfo;
                List<JavaClassElement> bounds = boundsUnresolved.stream()
                        .map(tm -> (JavaClassElement) mirrorToClassElement(tm, visitorContext, finalGenericsInfo, includeTypeAnnotations))
                        .collect(Collectors.toList());
                return new JavaGenericPlaceholderElement(tv, bounds, 0);
            }

        } else if (returnType instanceof ArrayType) {
            ArrayType at = (ArrayType) returnType;
            TypeMirror componentType = at.getComponentType();
            ClassElement arrayType = mirrorToClassElement(componentType, visitorContext, genericsInfo, includeTypeAnnotations);
            return arrayType.toArray();
        } else if (returnType instanceof PrimitiveType) {
            PrimitiveType pt = (PrimitiveType) returnType;
            return PrimitiveElement.valueOf(pt.getKind().name());
        } else if (returnType instanceof WildcardType) {
            WildcardType wt = (WildcardType) returnType;
            Map<String, Map<String, TypeMirror>> finalGenericsInfo = genericsInfo;
            TypeMirror superBound = wt.getSuperBound();
            Stream<? extends TypeMirror> lowerBounds;
            if (superBound instanceof UnionType) {
                lowerBounds = ((UnionType) superBound).getAlternatives().stream();
            } else if (superBound == null) {
                lowerBounds = Stream.empty();
            } else {
                lowerBounds = Stream.of(superBound);
            }
            TypeMirror extendsBound = wt.getExtendsBound();
            Stream<? extends TypeMirror> upperBounds;
            if (extendsBound instanceof IntersectionType) {
                upperBounds = ((IntersectionType) extendsBound).getBounds().stream();
            } else if (extendsBound == null) {
                upperBounds = Stream.of(visitorContext.getElements().getTypeElement("java.lang.Object").asType());
            } else {
                upperBounds = Stream.of(extendsBound);
            }
            return new JavaWildcardElement(
                    upperBounds
                            .map(tm -> (JavaClassElement) mirrorToClassElement(tm, visitorContext, finalGenericsInfo, includeTypeAnnotations))
                            .collect(Collectors.toList()),
                    lowerBounds
                            .map(tm -> (JavaClassElement) mirrorToClassElement(tm, visitorContext, finalGenericsInfo, includeTypeAnnotations))
                            .collect(Collectors.toList())
            );
        }
        return PrimitiveElement.VOID;
    }

    private Map<String, TypeMirror> resolveBoundGenerics(JavaVisitorContext visitorContext, Map<String, Map<String, TypeMirror>> genericsInfo) {
        String declaringTypeName = null;
        TypeElement typeElement = visitorContext.getModelUtils().classElementFor(element);
        if (typeElement != null) {
            declaringTypeName = typeElement.getQualifiedName().toString();
        }
        Map<String, TypeMirror> boundGenerics = genericsInfo.get(declaringTypeName);
        if (boundGenerics == null) {
            boundGenerics = Collections.emptyMap();
        }
        return boundGenerics;
    }

    private boolean hasModifier(Modifier modifier) {
        return element.getModifiers().contains(modifier);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AbstractJavaElement that = (AbstractJavaElement) o;
        return element.equals(that.element);
    }

    @Override
    public int hashCode() {
        return Objects.hash(element);
    }
}
