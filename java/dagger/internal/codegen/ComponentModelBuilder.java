/*
 * Copyright (C) 2015 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.auto.common.MoreElements.getLocalAndInheritedMethods;
import static com.google.auto.common.MoreTypes.asDeclared;
import static com.google.common.base.Preconditions.checkState;
import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static dagger.internal.codegen.AnnotationSpecs.Suppression.UNCHECKED;
import static dagger.internal.codegen.CodeBlocks.toParametersCodeBlock;
import static dagger.internal.codegen.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.GeneratedComponentModel.MethodSpecKind.BUILDER_METHOD;
import static dagger.internal.codegen.GeneratedComponentModel.MethodSpecKind.COMPONENT_METHOD;
import static dagger.internal.codegen.GeneratedComponentModel.MethodSpecKind.CONSTRUCTOR;
import static dagger.internal.codegen.GeneratedComponentModel.MethodSpecKind.INITIALIZE_METHOD;
import static dagger.internal.codegen.GeneratedComponentModel.TypeSpecKind.COMPONENT_BUILDER;
import static dagger.internal.codegen.GeneratedComponentModel.TypeSpecKind.SUBCOMPONENT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import com.google.auto.common.MoreTypes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;

/** Builds the model for an implementation of a component or subcomponent. */
abstract class ComponentModelBuilder {
  static GeneratedComponentModel buildComponentModel(
      DaggerTypes types,
      DaggerElements elements,
      KeyFactory keyFactory,
      CompilerOptions compilerOptions,
      ClassName name,
      BindingGraph graph) {
    GeneratedComponentModel generatedComponentModel;
    if (graph.componentDescriptor().kind().isTopLevel()) {
      generatedComponentModel = GeneratedComponentModel.forComponent(name);
    } else {
      generatedComponentModel = GeneratedComponentModel.forBaseSubcomponent(name);
    }
    SubcomponentNames subcomponentNames = new SubcomponentNames(graph, keyFactory);
    OptionalFactories optionalFactories = new OptionalFactories(generatedComponentModel);
    Optional<ComponentBuilder> builder =
        ComponentBuilder.create(generatedComponentModel, graph, subcomponentNames, elements, types);
    ComponentRequirementFields componentRequirementFields =
        new ComponentRequirementFields(graph, generatedComponentModel, builder);
    ComponentBindingExpressions bindingExpressions =
        new ComponentBindingExpressions(
            graph,
            generatedComponentModel,
            subcomponentNames,
            componentRequirementFields,
            optionalFactories,
            types,
            elements,
            compilerOptions);
    if (generatedComponentModel.isAbstract()) {
      checkState(
          compilerOptions.aheadOfTimeSubcomponents(),
          "Calling 'buildComponentModel()' on %s when not generating ahead-of-time subcomponents.",
          graph.componentDescriptor().componentDefinitionType());
      return new BaseSubcomponentModelBuilder(
              types,
              elements,
              graph,
              generatedComponentModel,
              subcomponentNames,
              optionalFactories,
              bindingExpressions,
              componentRequirementFields,
              builder)
          .build();
    } else {
      return new RootComponentModelBuilder(
              types,
              elements,
              graph,
              generatedComponentModel,
              subcomponentNames,
              optionalFactories,
              bindingExpressions,
              componentRequirementFields,
              builder)
          .build();
    }
  }

  private GeneratedComponentModel buildSubcomponentModel(BindingGraph childGraph) {
    ClassName parentName = generatedComponentModel.name();
    ClassName childName =
        parentName.nestedClass(subcomponentNames.get(childGraph.componentDescriptor()) + "Impl");
    GeneratedComponentModel childModel = GeneratedComponentModel.forSubcomponent(childName);
    Optional<ComponentBuilder> childBuilder =
        ComponentBuilder.create(childModel, childGraph, subcomponentNames, elements, types);
    ComponentRequirementFields childComponentRequirementFields =
        componentRequirementFields.forChildComponent(childGraph, childModel, childBuilder);
    ComponentBindingExpressions childBindingExpressions =
        bindingExpressions.forChildComponent(
            childGraph, childModel, childComponentRequirementFields);
    return new SubComponentModelBuilder(
            this,
            childGraph,
            childModel,
            childBindingExpressions,
            childComponentRequirementFields,
            childBuilder)
        .build();
  }

  private final Elements elements;
  private final DaggerTypes types;
  private final BindingGraph graph;
  private final SubcomponentNames subcomponentNames;
  private final ComponentBindingExpressions bindingExpressions;
  private final ComponentRequirementFields componentRequirementFields;
  private final GeneratedComponentModel generatedComponentModel;
  private final OptionalFactories optionalFactories;
  private final Optional<ComponentBuilder> builder;
  private boolean done;

  private ComponentModelBuilder(
      DaggerTypes types,
      Elements elements,
      BindingGraph graph,
      GeneratedComponentModel generatedComponentModel,
      SubcomponentNames subcomponentNames,
      OptionalFactories optionalFactories,
      ComponentBindingExpressions bindingExpressions,
      ComponentRequirementFields componentRequirementFields,
      Optional<ComponentBuilder> builder) {
    this.types = types;
    this.elements = elements;
    this.graph = graph;
    this.subcomponentNames = subcomponentNames;
    this.generatedComponentModel = generatedComponentModel;
    this.optionalFactories = optionalFactories;
    this.bindingExpressions = bindingExpressions;
    this.componentRequirementFields = componentRequirementFields;
    this.builder = builder;
  }

  /**
   * Returns a {@link GeneratedComponentModel} for this component. This is only intended to be
   * called once (and will throw on successive invocations). If the component must be regenerated,
   * use a new instance.
   */
  protected final GeneratedComponentModel build() {
    checkState(
        !done,
        "ComponentModelBuilder has already built the GeneratedComponentModel for [%s].",
        generatedComponentModel.name());
    generatedComponentModel.addSupertype(graph.componentType());
    builder.map(ComponentBuilder::typeSpec).ifPresent(this::addBuilderClass);

    getLocalAndInheritedMethods(
            graph.componentDescriptor().componentDefinitionType(), types, elements)
        .forEach(method -> generatedComponentModel.claimMethodName(method.getSimpleName()));

    addFactoryMethods();
    addInterfaceMethods();
    addSubcomponents();
    addConstructor();

    done = true;
    return generatedComponentModel;
  }

  /**
   * Adds {@code builder} as a nested builder class. Root components and subcomponents will nest
   * this in different classes.
   */
  protected abstract void addBuilderClass(TypeSpec builder);

  /** Adds component factory methods. */
  protected abstract void addFactoryMethods();

  private void addInterfaceMethods() {
    /* Each component method may have been declared by several supertypes. We want to implement only
     * one method for each distinct signature.*/
    ImmutableListMultimap<MethodSignature, ComponentMethodDescriptor> componentMethodsBySignature =
        Multimaps.index(graph.componentDescriptor().entryPointMethods(), this::getMethodSignature);
    for (List<ComponentMethodDescriptor> methodsWithSameSignature :
        Multimaps.asMap(componentMethodsBySignature).values()) {
      ComponentMethodDescriptor anyOneMethod = methodsWithSameSignature.stream().findAny().get();
      generatedComponentModel.addMethod(
          COMPONENT_METHOD, bindingExpressions.getComponentMethod(anyOneMethod));
    }
  }

  private MethodSignature getMethodSignature(ComponentMethodDescriptor method) {
    return MethodSignature.forComponentMethod(
        method, MoreTypes.asDeclared(graph.componentType().asType()), types);
  }

  protected void addSubcomponents() {
    for (BindingGraph subgraph : graph.subgraphs()) {
      generatedComponentModel.addSubcomponent(buildSubcomponentModel(subgraph));
    }
  }

  private static final int INITIALIZATIONS_PER_INITIALIZE_METHOD = 100;

  private void addConstructor() {
    List<List<CodeBlock>> partitions =
        Lists.partition(
            generatedComponentModel.getInitializations(), INITIALIZATIONS_PER_INITIALIZE_METHOD);

    ImmutableList<ParameterSpec> constructorParameters = constructorParameters();
    MethodSpec.Builder constructor =
        constructorBuilder()
            .addModifiers(generatedComponentModel.isAbstract() ? PROTECTED : PRIVATE)
            .addParameters(constructorParameters);

    ImmutableList<ParameterSpec> initializeParameters = initializeParameters();
    CodeBlock initializeParametersCodeBlock =
        constructorParameters
            .stream()
            .map(param -> CodeBlock.of("$N", param))
            .collect(toParametersCodeBlock());

    UniqueNameSet methodNames = new UniqueNameSet();
    for (List<CodeBlock> partition : partitions) {
      String methodName = methodNames.getUniqueName("initialize");
      MethodSpec.Builder initializeMethod =
          methodBuilder(methodName)
              .addModifiers(PRIVATE)
              /* TODO(gak): Strictly speaking, we only need the suppression here if we are also
               * initializing a raw field in this method, but the structure of this code makes it
               * awkward to pass that bit through.  This will be cleaned up when we no longer
               * separate fields and initilization as we do now. */
              .addAnnotation(AnnotationSpecs.suppressWarnings(UNCHECKED))
              .addCode(CodeBlocks.concat(partition));
      initializeMethod.addParameters(initializeParameters);
      constructor.addStatement("$L($L)", methodName, initializeParametersCodeBlock);
      generatedComponentModel.addMethod(INITIALIZE_METHOD, initializeMethod.build());
    }
    generatedComponentModel.addMethod(CONSTRUCTOR, constructor.build());
  }

  /** Returns the list of {@link ParameterSpec}s for the initialze methods. */
  private ImmutableList<ParameterSpec> initializeParameters() {
    return constructorParameters()
        .stream()
        .map(param -> param.toBuilder().addModifiers(FINAL).build())
        .collect(toImmutableList());
  }

  /** Returns the list of {@link ParameterSpec}s for the constructor. */
  private ImmutableList<ParameterSpec> constructorParameters() {
    if (builder.isPresent()) {
      return ImmutableList.of(ParameterSpec.builder(builder.get().name(), "builder").build());
    } else if (graph.factoryMethod().isPresent()) {
      return getFactoryMethodParameterSpecs(graph);
    } else if (generatedComponentModel.isAbstract() && !generatedComponentModel.isNested()) {
      return ImmutableList.of();
    } else {
      throw new AssertionError(
          "Expected either a component builder or factory method but found neither.");
    }
  }

  /** Builds the model for the root component. */
  private static final class RootComponentModelBuilder extends ComponentModelBuilder {
    RootComponentModelBuilder(
        DaggerTypes types,
        Elements elements,
        BindingGraph graph,
        GeneratedComponentModel generatedComponentModel,
        SubcomponentNames subcomponentNames,
        OptionalFactories optionalFactories,
        ComponentBindingExpressions bindingExpressions,
        ComponentRequirementFields componentRequirementFields,
        Optional<ComponentBuilder> builder) {
      super(
          types,
          elements,
          graph,
          generatedComponentModel,
          subcomponentNames,
          optionalFactories,
          bindingExpressions,
          componentRequirementFields,
          builder);
    }

    @Override
    protected void addBuilderClass(TypeSpec builder) {
      super.generatedComponentModel.addType(COMPONENT_BUILDER, builder);
    }

    @Override
    protected void addFactoryMethods() {
      // Only top-level components have the factory builder() method.
      // Mirror the user's builder API type if they had one.
      MethodSpec builderFactoryMethod =
          methodBuilder("builder")
              .addModifiers(PUBLIC, STATIC)
              .returns(
                  builderSpec().isPresent()
                      ? ClassName.get(builderSpec().get().builderDefinitionType())
                      : super.builder.get().name())
              .addStatement("return new $T()", super.builder.get().name())
              .build();
      super.generatedComponentModel.addMethod(BUILDER_METHOD, builderFactoryMethod);
      if (canInstantiateAllRequirements()) {
        CharSequence buildMethodName =
            builderSpec().isPresent() ? builderSpec().get().buildMethod().getSimpleName() : "build";
        super.generatedComponentModel.addMethod(
            BUILDER_METHOD,
            methodBuilder("create")
                .returns(ClassName.get(super.graph.componentType()))
                .addModifiers(PUBLIC, STATIC)
                .addStatement("return new Builder().$L()", buildMethodName)
                .build());
      }
    }

    private Optional<ComponentDescriptor.BuilderSpec> builderSpec() {
      return super.graph.componentDescriptor().builderSpec();
    }

    /** {@code true} if all of the graph's required dependencies can be automatically constructed */
    private boolean canInstantiateAllRequirements() {
      return !Iterables.any(
          super.graph.componentRequirements(),
          dependency -> dependency.requiresAPassedInstance(super.elements, super.types));
    }
  }

  /** Builds the model for a nested subcomponent. */
  private static final class SubComponentModelBuilder extends ComponentModelBuilder {
    private final ComponentModelBuilder parent;

    SubComponentModelBuilder(
        ComponentModelBuilder parent,
        BindingGraph graph,
        GeneratedComponentModel generatedComponentModel,
        ComponentBindingExpressions bindingExpressions,
        ComponentRequirementFields componentRequirementFields,
        Optional<ComponentBuilder> builder) {
      super(
          parent.types,
          parent.elements,
          graph,
          generatedComponentModel,
          parent.subcomponentNames,
          parent.optionalFactories,
          bindingExpressions,
          componentRequirementFields,
          builder);
      this.parent = parent;
    }

    @Override
    protected void addBuilderClass(TypeSpec builder) {
      parent.generatedComponentModel.addType(SUBCOMPONENT, builder);
    }

    @Override
    protected void addFactoryMethods() {
      // The parent's factory method to create this subcomponent if the
      // subcomponent was not added via {@link dagger.Module#subcomponents()}.
      super.graph.factoryMethod().ifPresent(this::createSubcomponentFactoryMethod);
    }

    private void createSubcomponentFactoryMethod(ExecutableElement factoryMethod) {
      parent.generatedComponentModel.addMethod(
          COMPONENT_METHOD,
          MethodSpec.overriding(factoryMethod, parentType(), super.types)
              .addStatement(
                  "return new $T($L)",
                  super.generatedComponentModel.name(),
                  getFactoryMethodParameterSpecs(super.graph)
                      .stream()
                      .map(param -> CodeBlock.of("$N", param))
                      .collect(toParametersCodeBlock()))
              .build());
    }

    private DeclaredType parentType() {
      return asDeclared(parent.graph.componentType().asType());
    }
  }

  /** Builds the model for a top-level abstract base implementation of a subcomponent. */
  private static final class BaseSubcomponentModelBuilder extends ComponentModelBuilder {
    private final GeneratedComponentModel generatedComponentModel;

    BaseSubcomponentModelBuilder(
        DaggerTypes types,
        Elements elements,
        BindingGraph graph,
        GeneratedComponentModel generatedComponentModel,
        SubcomponentNames subcomponentNames,
        OptionalFactories optionalFactories,
        ComponentBindingExpressions bindingExpressions,
        ComponentRequirementFields componentRequirementFields,
        Optional<ComponentBuilder> builder) {
      super(
          types,
          elements,
          graph,
          generatedComponentModel,
          subcomponentNames,
          optionalFactories,
          bindingExpressions,
          componentRequirementFields,
          builder);
      this.generatedComponentModel = generatedComponentModel;
    }

    @Override
    protected void addBuilderClass(TypeSpec builder) {
      generatedComponentModel.addType(SUBCOMPONENT, builder);
    }

    @Override
    protected void addFactoryMethods() {
      // Only construct instances of subcomponents that have concrete implementations.
    }

    @Override
    protected void addSubcomponents() {
      // TODO(b/72748365): Implement abstract inner subcomponents.
    }
  }

  /** Returns the list of {@link ParameterSpec}s for the corresponding graph's factory method. */
  private static ImmutableList<ParameterSpec> getFactoryMethodParameterSpecs(BindingGraph graph) {
    return graph
        .factoryMethodParameters()
        .values()
        .stream()
        .map(ParameterSpec::get)
        .collect(toImmutableList());
  }
}
