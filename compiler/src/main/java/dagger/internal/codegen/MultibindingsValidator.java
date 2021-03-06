/*
 * Copyright (C) 2015 Google, Inc.
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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableListMultimap;
import dagger.Module;
import dagger.Multibindings;
import dagger.producers.ProducerModule;
import java.util.Collection;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

import static com.google.auto.common.MoreElements.getLocalAndInheritedMethods;
import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.auto.common.MoreTypes.asExecutable;
import static dagger.internal.codegen.ErrorMessages.DUPLICATE_SIZE_LIMIT;
import static dagger.internal.codegen.ErrorMessages.MultibindingsMessages.MUST_BE_INTERFACE;
import static dagger.internal.codegen.ErrorMessages.MultibindingsMessages.MUST_BE_IN_MODULE;
import static dagger.internal.codegen.ErrorMessages.MultibindingsMessages.MUST_NOT_HAVE_TYPE_PARAMETERS;
import static dagger.internal.codegen.ErrorMessages.MultibindingsMessages.tooManyMethodsForKey;
import static javax.lang.model.element.ElementKind.INTERFACE;

/**
 * A {@linkplain ValidationReport validator} for {@link Multibindings @Multibindings}-annotated
 * types.
 */
final class MultibindingsValidator {
  private final Elements elements;
  private final Key.Factory keyFactory;
  private final KeyFormatter keyFormatter;
  private final MethodSignatureFormatter methodSignatureFormatter;
  private final TypeElement objectElement;
  private final MultibindingsMethodValidator multibindingsMethodValidator;

  MultibindingsValidator(
      Elements elements,
      Key.Factory keyFactory,
      KeyFormatter keyFormatter,
      MethodSignatureFormatter methodSignatureFormatter,
      MultibindingsMethodValidator multibindingsMethodValidator) {
    this.elements = elements;
    this.keyFactory = keyFactory;
    this.keyFormatter = keyFormatter;
    this.methodSignatureFormatter = methodSignatureFormatter;
    this.multibindingsMethodValidator = multibindingsMethodValidator;
    this.objectElement = elements.getTypeElement(Object.class.getCanonicalName());
  }

  /**
   * Returns a report containing validation errors for a
   * {@link Multibindings @Multibindings}-annotated type.
   */
  public ValidationReport<TypeElement> validate(TypeElement multibindingsType) {
    ValidationReport.Builder<TypeElement> validation = ValidationReport.about(multibindingsType);
    if (!multibindingsType.getKind().equals(INTERFACE)) {
      validation.addError(MUST_BE_INTERFACE, multibindingsType);
    }
    if (!multibindingsType.getTypeParameters().isEmpty()) {
      validation.addError(MUST_NOT_HAVE_TYPE_PARAMETERS, multibindingsType);
    }
    Optional<BindingType> bindingType = bindingType(multibindingsType);
    if (!bindingType.isPresent()) {
      validation.addError(MUST_BE_IN_MODULE, multibindingsType);
    }

    ImmutableListMultimap.Builder<Key, ExecutableElement> methodsByKey =
        ImmutableListMultimap.builder();
    for (ExecutableElement method : getLocalAndInheritedMethods(multibindingsType, elements)) {
      // Skip methods in Object.
      if (method.getEnclosingElement().equals(objectElement)) {
        continue;
      }
      
      ValidationReport<ExecutableElement> methodReport =
          multibindingsMethodValidator.validate(method);
      validation.addItems(methodReport.items());

      if (methodReport.isClean() && bindingType.isPresent()) {
        methodsByKey.put(
            keyFactory.forMultibindsMethod(
                bindingType.get(), asExecutable(method.asType()), method),
            method);
      }
    }
    for (Map.Entry<Key, Collection<ExecutableElement>> entry :
        methodsByKey.build().asMap().entrySet()) {
      Collection<ExecutableElement> methods = entry.getValue();
      if (methods.size() > 1) {
        Key key = entry.getKey();
        validation.addError(tooManyMultibindingsMethodsForKey(key, methods), multibindingsType);
      }
    }
    return validation.build();
  }

  private String tooManyMultibindingsMethodsForKey(Key key, Collection<ExecutableElement> methods) {
    StringBuilder builder = new StringBuilder(tooManyMethodsForKey(keyFormatter.format(key)));
    builder.append(':');
    methodSignatureFormatter.formatIndentedList(builder, methods, 1, DUPLICATE_SIZE_LIMIT);
    return builder.toString();
  }

  private Optional<BindingType> bindingType(TypeElement multibindingsType) {
    if (isAnnotationPresent(multibindingsType.getEnclosingElement(), Module.class)) {
      return Optional.of(BindingType.PROVISION);
    } else if (isAnnotationPresent(multibindingsType.getEnclosingElement(), ProducerModule.class)) {
      return Optional.of(BindingType.PRODUCTION);
    } else {
      return Optional.<BindingType>absent();
    }
  }
}
